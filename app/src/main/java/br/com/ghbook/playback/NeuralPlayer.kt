package br.com.ghbook.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import br.com.ghbook.data.Chapter
import br.com.ghbook.neural.LiveNeuralMode
import br.com.ghbook.neural.NeuralEngine
import br.com.ghbook.neural.NeuralModel
import br.com.ghbook.neural.NeuralVoice
import br.com.ghbook.tts.SpeechSegment
import br.com.ghbook.tts.SpeechText
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Narração ao vivo com a voz neural offline (Kokoro). Uma thread sintetiza frase a frase à frente da reprodução
 * e outra escreve o áudio num AudioTrack deste processo. A velocidade é aplicada no AudioTrack (sem alterar o tom),
 * sem precisar sintetizar de novo. Se a síntese não acompanhar a reprodução (aparelho lento), o estado vira
 * "carregando" até haver áudio — nesse caso o ideal é gerar o áudio do livro antes.
 */
@OptIn(UnstableApi::class)
class NeuralPlayer(
    private val context: Context,
    private val chapterLoader: (Int) -> Chapter?,
    private val listener: TtsPlayer.Listener,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    companion object {
        private const val TAG = "NeuralPlayer"
        private const val MAX_SEGMENT = 400
    }

    private val handler = Handler(Looper.getMainLooper())
    private var bookTitle = ""
    private var artwork: ByteArray? = null
    private var items: List<TtsPlayer.ChapterItem> = emptyList()
    private var currentIndex = 0
    private var segments: List<SpeechSegment> = emptyList()
    private var segmentIndex = 0
    private var playWhenReadyFlag = false
    private var playbackStateValue = Player.STATE_IDLE
    private var speed = 1f
    private var pitch = 1f
    private var voice: NeuralVoice = NeuralEngine.defaultVoice
    private var liveMode: LiveNeuralMode = LiveNeuralMode.AUTO
    /** Modelo em uso ao vivo (null = ainda não decidido / precisa medir o aparelho). */
    private var model: NeuralModel? = null
    private var positionMs = 0L
    private var generation = 0
    private var pipeline: Pipeline? = null
    private var loading = false
    private var released = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var noisyRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) setPlayWhenReadyInternal(false)
        }
    }

    private fun msOf(seg: SpeechSegment) = seg.charOffset * NeuralEngine.MS_PER_CHAR

    // ---------------------------------------------------------------- pipeline de síntese + reprodução

    private class Chunk(val segment: Int, val samples: FloatArray)

    private inner class Pipeline(val gen: Int, val segs: List<SpeechSegment>, startSeg: Int, val model: NeuralModel) {
        val sampleRate = NeuralEngine.sampleRate(model)
        /** A Kokoro não acompanha a fala neste aparelho (medido durante a reprodução). */
        @Volatile var tooSlow = false
        @Volatile var measuredRtf = 0f
        private var synthNanos = 0L
        private var synthFrames = 0L
        val queue = LinkedBlockingQueue<Chunk>(64)
        val segStartFrame = LongArray(segs.size) { -1L }
        @Volatile var framesWritten = 0L
        @Volatile var producerDone = false
        @Volatile var finished = false
        @Volatile var cancelled = false
        @Volatile var failed: String? = null
        val track: AudioTrack

        init {
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf, sampleRate * 4 / 2)) // ~0,5 s
                .build()
            applyParams(track)

            Thread({ produce(startSeg) }, "neural-synth").start()
            Thread({ write() }, "neural-audio").start()
        }

        private fun offer(chunk: Chunk): Boolean {
            while (!cancelled) {
                if (queue.offer(chunk, 100, TimeUnit.MILLISECONDS)) return true
            }
            return false
        }

        private fun produce(startSeg: Int) {
            try {
                for (i in startSeg until segs.size) {
                    // Não sintetiza muito à frente (e não segura o motor enquanto a fila está cheia).
                    while (!cancelled && queue.size > 24) Thread.sleep(100)
                    if (cancelled) return
                    val seg = segs[i]
                    val t0 = System.nanoTime()
                    var frames = 0L
                    val ok = NeuralEngine.synthesize(context, seg.text, voice, model) { samples ->
                        frames += samples.size
                        offer(Chunk(i, samples))
                    }
                    if (model == NeuralModel.KOKORO && frames > 0) {
                        // Tempo gasto esperando a fila não conta: medimos só a síntese.
                        synthNanos += System.nanoTime() - t0
                        synthFrames += frames
                        val audioSec = synthFrames.toFloat() / sampleRate
                        if (audioSec >= 8f) {
                            measuredRtf = (synthNanos / 1e9f) / audioSec
                            if (measuredRtf > 1.0f) tooSlow = true
                        }
                    }
                    if (!ok) {
                        failed = NeuralEngine.lastError ?: "A voz neural não pôde ser carregada."
                        break
                    }
                    // Pausa natural: maior após títulos e entre parágrafos.
                    val next = segs.getOrNull(i + 1)
                    val pauseMs = when {
                        seg.isHeading -> 600
                        next != null && next.paragraph != seg.paragraph -> 280
                        else -> 60
                    }
                    if (!offer(Chunk(i, FloatArray(sampleRate * pauseMs / 1000)))) return
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Erro na síntese", t)
                failed = "Erro na voz neural: ${t.message}"
            } finally {
                producerDone = true
            }
        }

        private fun write() {
            try {
                while (!cancelled) {
                    val chunk = queue.poll(100, TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        if (producerDone && queue.isEmpty()) break
                        continue
                    }
                    if (segStartFrame[chunk.segment] < 0) segStartFrame[chunk.segment] = framesWritten
                    var off = 0
                    while (off < chunk.samples.size && !cancelled) {
                        val n = track.write(chunk.samples, off, chunk.samples.size - off, AudioTrack.WRITE_BLOCKING)
                        if (n < 0) { cancelled = true; break }
                        off += n
                    }
                    framesWritten += off
                }
                // Espera o fim do áudio já escrito antes de avisar o fim do capítulo.
                while (!cancelled && head() < framesWritten) Thread.sleep(40)
                if (!cancelled) {
                    finished = true
                    handler.post { onPipelineFinished(this) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Erro na reprodução", t)
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }

        fun head(): Long = try {
            track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        } catch (t: Throwable) {
            0L
        }

        fun play() = runCatching { track.play() }
        fun pause() = runCatching { track.pause() }

        fun cancel() {
            cancelled = true
            queue.clear()
            runCatching { track.pause() }
            runCatching { track.flush() }
        }

        /** Segmento audível agora (o último cujo início já passou pelo cabeçote de reprodução). */
        fun currentSegment(): Int {
            val h = head()
            var idx = -1
            for (i in segStartFrame.indices) {
                val s = segStartFrame[i]
                if (s in 0..h) idx = i
            }
            return idx
        }

        val underrun get() = !producerDone && head() >= framesWritten
    }

    private fun applyParams(track: AudioTrack) {
        runCatching { track.playbackParams = PlaybackParams().setSpeed(speed).setPitch(pitch) }
    }

    private fun startPipeline() {
        stopPipeline()
        if (released) return
        if (segments.isEmpty()) {
            onChapterFinished()
            return
        }
        val chosen = model ?: decideModel()
        if (chosen != null && chosen != model) {
            model = chosen
            announceVoice()
        }
        if (chosen == null || !NeuralEngine.isLoaded(chosen, voice)) {
            // Primeira utilização: carrega o modelo (1–3 s) e, no modo automático, mede o aparelho — fora da thread principal.
            playbackStateValue = Player.STATE_BUFFERING
            invalidateState()
            if (!loading) {
                loading = true
                val v = voice
                val mode = liveMode
                Thread({
                    var m = chosen
                    if (m == null) {
                        val rtf = NeuralEngine.benchmarkKokoro(context, v)
                        m = if (rtf != null && rtf <= NeuralEngine.KOKORO_LIVE_MAX_RTF) NeuralModel.KOKORO else NeuralModel.FAST
                    }
                    if (m != NeuralModel.KOKORO) NeuralEngine.releaseKokoroIfIdle()
                    val ok = NeuralEngine.load(context, m, v)
                    handler.post {
                        loading = false
                        // Player liberado durante o carregamento: não cria AudioTrack nem volta a tocar.
                        if (released) return@post
                        if (ok && v == voice && mode == liveMode) {
                            model = m
                            announceVoice()
                            if (playWhenReadyFlag) startPipeline()
                        } else if (!ok) {
                            listener.onError(NeuralEngine.lastError ?: "A voz neural não pôde ser carregada.")
                            playWhenReadyFlag = false
                            playbackStateValue = Player.STATE_READY
                        } else if (playWhenReadyFlag) {
                            startPipeline()
                        }
                        invalidateState()
                    }
                }, "neural-load").start()
            }
            return
        }
        generation++
        NeuralEngine.liveUsesKokoro = chosen == NeuralModel.KOKORO
        if (chosen != NeuralModel.KOKORO) NeuralEngine.releaseKokoroIfIdle()
        val p = Pipeline(generation, segments, segmentIndex, chosen)
        pipeline = p
        if (playWhenReadyFlag) {
            if (requestFocus()) {
                registerNoisy()
                p.play()
            } else {
                playWhenReadyFlag = false
            }
        }
        playbackStateValue = Player.STATE_BUFFERING
        scheduleTick()
    }

    /** Modelo ao vivo conforme o modo; null quando é preciso medir o aparelho primeiro (modo automático). */
    private fun decideModel(): NeuralModel? = when (liveMode) {
        LiveNeuralMode.KOKORO -> NeuralModel.KOKORO
        LiveNeuralMode.FAST -> NeuralModel.FAST
        LiveNeuralMode.AUTO -> if (NeuralEngine.kokoroRtf(context) == null) null else NeuralEngine.liveModel(context, LiveNeuralMode.AUTO)
    }

    private fun announceVoice() {
        val label = when (model) {
            NeuralModel.FAST -> "Voz neural ${voice.fastName} · rápida (par da ${voice.name})"
            NeuralModel.KOKORO -> "Voz neural ${voice.name} · Kokoro"
            null -> "Voz neural ${voice.name} · medindo o desempenho do aparelho…"
        }
        val warning = if (model == NeuralModel.FAST && liveMode == LiveNeuralMode.AUTO) {
            "Ao vivo está sendo usada a voz rápida, porque este aparelho não sintetiza a Kokoro na velocidade da fala. " +
                "Para ouvir com a ${voice.name} (Kokoro), gere o áudio do livro em \"Áudios offline\"."
        } else null
        listener.onVoice(label, true, warning)
    }

    private fun stopPipeline() {
        pipeline?.cancel()
        pipeline = null
        handler.removeCallbacks(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = pipeline ?: return
            if (p.tooSlow && liveMode == LiveNeuralMode.AUTO && p.model == NeuralModel.KOKORO) {
                // Aparelho lento demais para a Kokoro ao vivo: registra e continua com a voz rápida do mesmo trecho.
                NeuralEngine.recordKokoroRtf(context, p.measuredRtf)
                model = NeuralModel.FAST
                announceVoice()
                startPipeline()
                invalidateState()
                return
            }
            p.failed?.let { msg ->
                listener.onError(msg)
                stopPipeline()
                playWhenReadyFlag = false
                playbackStateValue = Player.STATE_READY
                invalidateState()
                return
            }
            var changed = false
            val segIdx = p.currentSegment()
            if (segIdx >= 0) {
                if (segIdx != segmentIndex) {
                    segmentIndex = segIdx
                    listener.onParagraph(items[currentIndex].index, segments[segIdx].paragraph, msOf(segments[segIdx]))
                    changed = true
                }
                val seg = segments[segIdx]
                val within = (p.head() - p.segStartFrame[segIdx]).coerceAtLeast(0) * 1000 / p.sampleRate
                val nextStart = segments.getOrNull(segIdx + 1)?.let { msOf(it) } ?: Long.MAX_VALUE
                positionMs = minOf(msOf(seg) + within, nextStart)
            }
            val newState = if (p.underrun && playWhenReadyFlag) Player.STATE_BUFFERING else Player.STATE_READY
            if (newState != playbackStateValue) {
                playbackStateValue = newState
                changed = true
            }
            if (changed) invalidateState()
            handler.postDelayed(this, 200)
        }
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun onPipelineFinished(p: Pipeline) {
        Log.d(TAG, "fim do pipeline cap=$currentIndex atual=${p === pipeline}")
        if (p !== pipeline) return
        pipeline = null
        onChapterFinished()
    }

    private fun onChapterFinished() {
        if (currentIndex + 1 < items.size) {
            loadChapter(currentIndex + 1, 0)
            startPipeline()
        } else {
            stopPipeline()
            playWhenReadyFlag = false
            playbackStateValue = Player.STATE_ENDED
            abandonFocus()
        }
        invalidateState()
    }

    // ---------------------------------------------------------------- API usada pelo PlaybackService

    fun setBook(title: String, artwork: ByteArray?, chapters: List<TtsPlayer.ChapterItem>, startChapter: Int, startParagraph: Int) {
        stopPipeline()
        bookTitle = title
        this.artwork = artwork
        items = chapters
        if (items.isEmpty()) {
            playbackStateValue = Player.STATE_IDLE
            invalidateState()
            return
        }
        loadChapter(startChapter.coerceIn(0, items.lastIndex), startParagraph)
        playbackStateValue = Player.STATE_READY
        invalidateState()
    }

    fun playFromParagraph(chapterIndex: Int, paragraph: Int) {
        val i = items.indexOfFirst { it.index == chapterIndex }.takeIf { it >= 0 } ?: return
        loadChapter(i, paragraph)
        playWhenReadyFlag = true
        startPipeline()
        invalidateState()
    }

    fun setVoicePreference(name: String, pitch: Float, mode: LiveNeuralMode) {
        val v = NeuralEngine.voiceFor(name)
        val changed = v != voice || mode != liveMode
        voice = v
        liveMode = mode
        this.pitch = pitch
        pipeline?.let { applyParams(it.track) }
        if (changed) model = null
        if (model == null) model = decideModel()
        announceVoice()
        // Voz ou modo novos exigem sintetizar de novo a partir do trecho atual.
        if (changed && pipeline != null) {
            startPipeline()
            invalidateState()
        }
    }

    private fun loadChapter(i: Int, paragraph: Int) {
        currentIndex = i
        val ch = chapterLoader(items[i].index)
        segments = if (ch != null) SpeechText.segments(ch, MAX_SEGMENT) else emptyList()
        if (ch == null) listener.onError("Não foi possível carregar o texto do capítulo.")
        segmentIndex = segments.indexOfFirst { it.paragraph >= paragraph }.takeIf { it >= 0 } ?: 0
        positionMs = segments.getOrNull(segmentIndex)?.let { msOf(it) } ?: 0
        val est = SpeechText.totalChars(segments) * NeuralEngine.MS_PER_CHAR
        items = items.toMutableList().also { it[i] = it[i].copy(estimatedDurationMs = maxOf(est, 1000)) }
        segments.getOrNull(segmentIndex)?.let { listener.onParagraph(items[i].index, it.paragraph, positionMs) }
    }

    private fun setPlayWhenReadyInternal(play: Boolean) {
        if (play == playWhenReadyFlag) return
        playWhenReadyFlag = play
        val p = pipeline
        if (play) {
            if (playbackStateValue == Player.STATE_ENDED) {
                loadChapter(0, 0)
            }
            if (p == null) {
                startPipeline()
            } else if (requestFocus()) {
                registerNoisy()
                p.play()
            } else {
                playWhenReadyFlag = false
            }
        } else {
            p?.pause()
            unregisterNoisy()
        }
        invalidateState()
    }

    // ---------------------------------------------------------------- foco de áudio

    private fun requestFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocusGain = false; setPlayWhenReadyInternal(false); abandonFocus() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        resumeOnFocusGain = playWhenReadyFlag
                        setPlayWhenReadyInternal(false)
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) {
                        resumeOnFocusGain = false
                        setPlayWhenReadyInternal(true)
                    }
                }
            }, handler)
            .build().also { focusRequest = it }
        return audioManager.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { context.unregisterReceiver(noisyReceiver) }
        noisyRegistered = false
    }

    // ---------------------------------------------------------------- SimpleBasePlayer

    override fun getState(): State {
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_PREPARE,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA, Player.COMMAND_RELEASE,
        ).build()
        val playlist = items.map { item ->
            MediaItemData.Builder("nch-${item.index}")
                .setMediaItem(
                    MediaItem.Builder().setMediaId("neural:${item.index}")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(item.title)
                                .setArtist(bookTitle)
                                .setAlbumTitle(bookTitle)
                                .setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                .setIsPlayable(true)
                                .setIsBrowsable(false)
                                .build(),
                        ).build(),
                )
                .setDurationUs(item.estimatedDurationMs * 1000)
                .setIsSeekable(true)
                .build()
        }
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(playlist)
            .setPlayWhenReady(playWhenReadyFlag, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (playlist.isEmpty()) Player.STATE_IDLE else playbackStateValue)
            .setPlaybackParameters(PlaybackParameters(speed))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
        if (playlist.isNotEmpty()) {
            builder.setCurrentMediaItemIndex(currentIndex.coerceIn(0, playlist.lastIndex))
            val p = pipeline
            val base = positionMs
            builder.setContentPositionMs(
                SimpleBasePlayer.PositionSupplier {
                    if (p == null || p !== pipeline) return@PositionSupplier base
                    val segIdx = p.currentSegment()
                    if (segIdx < 0) return@PositionSupplier base
                    val seg = p.segs[segIdx]
                    val within = (p.head() - p.segStartFrame[segIdx]).coerceAtLeast(0) * 1000 / p.sampleRate
                    val nextStart = p.segs.getOrNull(segIdx + 1)?.let { msOf(it) } ?: Long.MAX_VALUE
                    minOf(msOf(seg) + within, nextStart)
                },
            )
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        Log.d(TAG, "setPlayWhenReady($playWhenReady) atual=$playWhenReadyFlag estado=$playbackStateValue cap=$currentIndex seg=$segmentIndex")
        setPlayWhenReadyInternal(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (items.isNotEmpty() && playbackStateValue == Player.STATE_IDLE) playbackStateValue = Player.STATE_READY
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        Log.d(TAG, "stop()")
        stopPipeline()
        playWhenReadyFlag = false
        playbackStateValue = Player.STATE_IDLE
        unregisterNoisy()
        abandonFocus()
        // Parada: a Kokoro pode ser liberada da memória (volta a ser marcada ao reiniciar o pipeline).
        NeuralEngine.liveUsesKokoro = false
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        Log.d(TAG, "seek(item=$mediaItemIndex, pos=$positionMs, cmd=$seekCommand) cap=$currentIndex")
        if (items.isEmpty()) return Futures.immediateVoidFuture()
        val target = mediaItemIndex.coerceIn(0, items.lastIndex)
        if (target != currentIndex || segments.isEmpty()) loadChapter(target, 0)
        val pos = if (positionMs == C.TIME_UNSET) 0 else positionMs.coerceAtLeast(0)
        segmentIndex = segments.indexOfLast { msOf(it) <= pos }.coerceAtLeast(0)
        this.positionMs = segments.getOrNull(segmentIndex)?.let { msOf(it) } ?: 0
        segments.getOrNull(segmentIndex)?.let { listener.onParagraph(items[currentIndex].index, it.paragraph, this.positionMs) }
        if (playbackStateValue == Player.STATE_ENDED || playbackStateValue == Player.STATE_IDLE) playbackStateValue = Player.STATE_READY
        if (playWhenReadyFlag) startPipeline() else stopPipeline()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed.coerceIn(0.25f, 4f)
        pipeline?.let { applyParams(it.track) }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        playWhenReadyFlag = false
        stopPipeline()
        unregisterNoisy()
        abandonFocus()
        NeuralEngine.liveUsesKokoro = false
        return Futures.immediateVoidFuture()
    }
}
