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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.speech.tts.UtteranceProgressListener
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
import br.com.ghbook.tts.SpeechSegment
import br.com.ghbook.tts.SpeechText
import br.com.ghbook.tts.TtsVoices
import br.com.ghbook.tts.VoiceSelection
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Player do Media3 que narra capítulos com o TextToSpeech do Android.
 * Cada capítulo é um item da playlist; a posição/duração são estimadas a partir do número de caracteres,
 * o que permite usar barra de progresso, avançar/retroceder e os controles da notificação/tela de bloqueio.
 */
@OptIn(UnstableApi::class)
class TtsPlayer(
    private val context: Context,
    private val chapterLoader: (Int) -> Chapter?,
    private val listener: Listener,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    interface Listener {
        fun onParagraph(chapter: Int, paragraph: Int, positionMs: Long)
        fun onError(message: String)
        /** Voz em uso: descrição, se funciona offline e um aviso opcional. */
        fun onVoice(label: String, offline: Boolean, warning: String?)
    }

    companion object {
        private const val TAG = "TtsPlayer"
    }

    data class ChapterItem(val index: Int, val title: String, val estimatedDurationMs: Long)

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitFailed = false
    private var maxInput = 3900

    private var bookTitle = ""
    private var artwork: ByteArray? = null
    private var items: List<ChapterItem> = emptyList()
    private var currentIndex = 0
    private var segments: List<SpeechSegment> = emptyList()
    private var segmentIndex = 0
    private var playWhenReadyFlag = false
    private var playbackStateValue = Player.STATE_IDLE
    private var speed = 1f
    private var pitch = 1f
    private var preferredVoice = ""
    private var positionMs = 0L
    private var generation = 0
    private var enqueuedUpTo = -1
    private var speaking = false
    private var segmentStartedAt = 0L
    private var released = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var noisyRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pauseInternal()
        }
    }

    init {
        initTts()
    }

    private fun initTts() {
        ttsInitFailed = false
        // Nova tentativa após falha: libera a conexão anterior com o mecanismo.
        tts?.let { old -> runCatching { old.shutdown() } }
        tts = TextToSpeech(context.applicationContext) { status ->
            handler.post {
                // Player liberado antes de o TTS terminar de inicializar: não pede foco nem começa a falar.
                if (released) return@post
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    runCatching { maxInput = TextToSpeech.getMaxSpeechInputLength() - 100 }
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    tts?.setOnUtteranceProgressListener(progressListener)
                    applyVoiceAndRate()
                    if (playWhenReadyFlag && playbackStateValue != Player.STATE_IDLE) startSpeaking()
                } else {
                    ttsInitFailed = true
                    listener.onError("Nenhum mecanismo de síntese de voz (TTS) disponível. Instale o \"Speech Services by Google\" ou outro mecanismo de TTS.")
                    playWhenReadyFlag = false
                    playbackStateValue = Player.STATE_IDLE
                    invalidateState()
                }
            }
        }
    }

    private fun applyVoiceAndRate() {
        val t = tts ?: return
        val sel = TtsVoices.select(t, preferredVoice)
        TtsVoices.apply(t, sel)
        t.setSpeechRate(speed)
        t.setPitch(pitch)
        val info = sel.info
        listener.onVoice(
            if (info == null) "Voz do sistema (TTS do aparelho)" else "Voz do sistema · ${info.label}",
            info?.isOffline == true,
            sel.warning,
        )
    }

    fun setVoicePreference(name: String, pitch: Float) {
        val changed = name != preferredVoice || pitch != this.pitch
        preferredVoice = name
        this.pitch = pitch
        if (changed && ttsReady) {
            applyVoiceAndRate()
            if (speaking) restartFromCurrentSegment()
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            handler.post { onSegmentStart(utteranceId) }
        }

        override fun onDone(utteranceId: String) {
            handler.post { onSegmentDone(utteranceId) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            handler.post { onSegmentError(utteranceId, TextToSpeech.ERROR) }
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            handler.post { onSegmentError(utteranceId, errorCode) }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {}
    }

    private fun parseId(id: String): Pair<Int, Int>? {
        val parts = id.split(':')
        if (parts.size != 3 || parts[0] != "seg") return null
        return parts[1].toIntOrNull()?.let { g -> parts[2].toIntOrNull()?.let { g to it } }
    }

    private fun onSegmentStart(id: String) {
        val (gen, idx) = parseId(id) ?: return
        if (gen != generation || idx !in segments.indices) return
        segmentIndex = idx
        val seg = segments[idx]
        positionMs = seg.charOffset * SpeechText.MS_PER_CHAR
        segmentStartedAt = android.os.SystemClock.elapsedRealtime()
        if (playbackStateValue == Player.STATE_BUFFERING) playbackStateValue = Player.STATE_READY
        invalidateState()
        listener.onParagraph(items.getOrNull(currentIndex)?.index ?: currentIndex, seg.paragraph, positionMs)
    }

    private fun onSegmentDone(id: String) {
        val (gen, idx) = parseId(id) ?: return
        if (gen != generation) return
        if (idx >= segments.size - 1) {
            onChapterFinished()
        } else {
            enqueueAhead()
        }
    }

    private fun onSegmentError(id: String, code: Int) {
        val (gen, idx) = parseId(id) ?: return
        if (gen != generation) return
        val msg = when (code) {
            TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT ->
                "A voz selecionada precisa de internet e a conexão falhou. Escolha uma voz offline em Configurações."
            TextToSpeech.ERROR_NOT_INSTALLED_YET -> "Os dados da voz ainda não foram instalados. Abra as configurações de TTS para baixar a voz."
            TextToSpeech.ERROR_SERVICE -> "O serviço de síntese de voz falhou. Tente novamente."
            TextToSpeech.ERROR_SYNTHESIS, TextToSpeech.ERROR_OUTPUT -> "Erro ao sintetizar este trecho; pulando."
            else -> "Erro na síntese de voz (código $code)."
        }
        listener.onError(msg)
        if (code == TextToSpeech.ERROR_SYNTHESIS || code == TextToSpeech.ERROR_OUTPUT) {
            // Erro no último trecho: não haverá onDone, então o capítulo precisa terminar aqui (senão a narração trava).
            if (idx >= segments.size - 1) onChapterFinished() else enqueueAhead()
        } else {
            pauseInternal()
        }
    }

    private fun onChapterFinished() {
        Log.d(TAG, "fim do capítulo $currentIndex")
        if (currentIndex + 1 < items.size) {
            loadChapter(currentIndex + 1, 0)
            if (playWhenReadyFlag) startSpeaking()
            invalidateState()
        } else {
            stopSpeaking()
            playWhenReadyFlag = false
            playbackStateValue = Player.STATE_ENDED
            abandonFocus()
            invalidateState()
        }
    }

    // ---------------------------------------------------------------- API usada pelo PlaybackEngine

    fun setBook(title: String, artwork: ByteArray?, chapters: List<ChapterItem>, startChapter: Int, startParagraph: Int) {
        stopSpeaking()
        bookTitle = title
        this.artwork = artwork
        items = chapters
        if (items.isEmpty()) {
            playbackStateValue = Player.STATE_IDLE
            invalidateState()
            return
        }
        loadChapter(startChapter.coerceIn(0, items.lastIndex), startParagraph)
        playbackStateValue = if (ttsReady) Player.STATE_READY else Player.STATE_BUFFERING
        invalidateState()
    }

    val currentChapterIndex get() = items.getOrNull(currentIndex)?.index ?: -1
    val currentParagraph get() = segments.getOrNull(segmentIndex)?.paragraph ?: 0

    fun playFromParagraph(chapterIndex: Int, paragraph: Int) {
        val i = items.indexOfFirst { it.index == chapterIndex }.takeIf { it >= 0 } ?: return
        stopSpeaking()
        loadChapter(i, paragraph)
        playWhenReadyFlag = true
        if (playbackStateValue == Player.STATE_IDLE || playbackStateValue == Player.STATE_ENDED) {
            playbackStateValue = if (ttsReady) Player.STATE_READY else Player.STATE_BUFFERING
        }
        startSpeaking()
        invalidateState()
    }

    private fun loadChapter(i: Int, paragraph: Int) {
        currentIndex = i
        val ch = chapterLoader(items[i].index)
        segments = if (ch != null) SpeechText.segments(ch, maxInput.coerceIn(200, 600)) else emptyList()
        if (ch == null) listener.onError("Não foi possível carregar o texto do capítulo.")
        segmentIndex = segments.indexOfFirst { it.paragraph >= paragraph }.takeIf { it >= 0 } ?: 0
        positionMs = segments.getOrNull(segmentIndex)?.charOffset?.times(SpeechText.MS_PER_CHAR) ?: 0
        // Atualiza a duração estimada com base nos segmentos reais.
        val est = SpeechText.totalChars(segments) * SpeechText.MS_PER_CHAR
        items = items.toMutableList().also { it[i] = it[i].copy(estimatedDurationMs = maxOf(est, 1000)) }
        segments.getOrNull(segmentIndex)?.let { listener.onParagraph(items[i].index, it.paragraph, positionMs) }
    }

    private fun startSpeaking() {
        Log.d(TAG, "startSpeaking ready=$ttsReady segs=${segments.size} idx=$segmentIndex")
        if (!ttsReady) {
            playbackStateValue = Player.STATE_BUFFERING
            if (ttsInitFailed) initTts()
            return
        }
        if (segments.isEmpty()) {
            onChapterFinished()
            return
        }
        if (!requestFocus()) {
            playWhenReadyFlag = false
            return
        }
        registerNoisy()
        generation++
        enqueuedUpTo = segmentIndex - 1
        speaking = true
        startSilence()
        enqueueAhead(first = true)
    }

    private fun enqueueAhead(first: Boolean = false) {
        val t = tts ?: return
        if (!speaking) return
        var mode = if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val target = minOf(segments.lastIndex, maxOf(segmentIndex, enqueuedUpTo) + 3)
        while (enqueuedUpTo < target) {
            val next = enqueuedUpTo + 1
            val seg = segments[next]
            val params = Bundle()
            t.speak(seg.text, mode, params, "seg:$generation:$next")
            if (seg.isHeading) t.playSilentUtterance(450, TextToSpeech.QUEUE_ADD, "sil:$generation:$next")
            mode = TextToSpeech.QUEUE_ADD
            enqueuedUpTo = next
        }
    }

    /**
     * Na síntese ao vivo o áudio é tocado pelo processo do mecanismo de TTS, e o Android entrega as teclas de mídia
     * (fones Bluetooth/cabo) ao app que está tocando áudio. Uma faixa estática de silêncio em loop no nosso processo
     * mantém a sessão do app como destino dessas teclas. Não usa CPU (modo estático com pontos de loop).
     */
    private var silentTrack: AudioTrack? = null

    private fun startSilence() {
        if (silentTrack != null) return
        runCatching {
            val rate = 8000
            val bytes = rate * 2 // 1 s, PCM 16 bits mono
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes)
                .build()
            track.write(ByteArray(bytes), 0, bytes)
            track.setLoopPoints(0, rate, -1)
            track.play()
            silentTrack = track
        }
    }

    private fun stopSilence() {
        silentTrack?.let { t -> runCatching { t.stop() }; runCatching { t.release() } }
        silentTrack = null
    }

    private fun stopSpeaking() {
        stopSilence()
        speaking = false
        generation++
        runCatching { tts?.stop() }
    }

    private fun restartFromCurrentSegment() {
        stopSpeaking()
        if (playWhenReadyFlag) startSpeaking()
    }

    private fun pauseInternal() {
        Log.d(TAG, "pauseInternal (flag=$playWhenReadyFlag)")
        if (!playWhenReadyFlag) return
        playWhenReadyFlag = false
        stopSpeaking()
        unregisterNoisy()
        invalidateState()
    }

    private fun requestFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                Log.d(TAG, "foco de áudio: $change")
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocusGain = false; pauseInternal(); abandonFocus() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        resumeOnFocusGain = playWhenReadyFlag
                        pauseInternal()
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) {
                        resumeOnFocusGain = false
                        playWhenReadyFlag = true
                        startSpeaking()
                        invalidateState()
                    }
                }
            }, handler)
            .build().also { focusRequest = it }
        val r = audioManager.requestAudioFocus(req)
        Log.d(TAG, "requestAudioFocus -> $r")
        return r != AudioManager.AUDIOFOCUS_REQUEST_FAILED
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
            MediaItemData.Builder("ch-${item.index}")
                .setMediaItem(
                    MediaItem.Builder().setMediaId("tts:${item.index}")
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
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
        if (playlist.isNotEmpty()) {
            builder.setCurrentMediaItemIndex(currentIndex.coerceIn(0, playlist.lastIndex))
            val base = positionMs
            val startedAt = segmentStartedAt
            val advancing = speaking && playWhenReadyFlag && playbackStateValue == Player.STATE_READY
            val cap = segments.getOrNull(segmentIndex + 1)?.charOffset?.times(SpeechText.MS_PER_CHAR) ?: Long.MAX_VALUE
            val rate = speed
            builder.setContentPositionMs(
                SimpleBasePlayer.PositionSupplier {
                    if (advancing) minOf(cap, base + ((android.os.SystemClock.elapsedRealtime() - startedAt) * rate).toLong()) else base
                },
            )
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        Log.d(TAG, "setPlayWhenReady($playWhenReady) atual=$playWhenReadyFlag estado=$playbackStateValue")
        if (playWhenReady == playWhenReadyFlag) return Futures.immediateVoidFuture()
        if (playWhenReady) {
            playWhenReadyFlag = true
            if (playbackStateValue == Player.STATE_ENDED) {
                loadChapter(0, 0)
                playbackStateValue = Player.STATE_READY
            }
            if (playbackStateValue != Player.STATE_IDLE) startSpeaking()
        } else {
            playWhenReadyFlag = false
            stopSpeaking()
            unregisterNoisy()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (items.isNotEmpty() && playbackStateValue == Player.STATE_IDLE) {
            playbackStateValue = if (ttsReady) Player.STATE_READY else Player.STATE_BUFFERING
            if (playWhenReadyFlag) startSpeaking()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        Log.d(TAG, "stop()")
        stopSpeaking()
        playWhenReadyFlag = false
        playbackStateValue = Player.STATE_IDLE
        unregisterNoisy()
        abandonFocus()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        Log.d(TAG, "seek(item=$mediaItemIndex, pos=$positionMs, cmd=$seekCommand)")
        if (items.isEmpty()) return Futures.immediateVoidFuture()
        val target = mediaItemIndex.coerceIn(0, items.lastIndex)
        val wasSpeaking = speaking
        stopSpeaking()
        if (target != currentIndex || segments.isEmpty()) loadChapter(target, 0)
        val pos = if (positionMs == C.TIME_UNSET) 0 else positionMs.coerceAtLeast(0)
        val chars = pos / SpeechText.MS_PER_CHAR
        segmentIndex = segments.indexOfLast { it.charOffset <= chars }.coerceAtLeast(0)
        this.positionMs = segments.getOrNull(segmentIndex)?.charOffset?.times(SpeechText.MS_PER_CHAR) ?: 0
        segments.getOrNull(segmentIndex)?.let { listener.onParagraph(items[currentIndex].index, it.paragraph, this.positionMs) }
        if (playbackStateValue == Player.STATE_ENDED) playbackStateValue = Player.STATE_READY
        if (playWhenReadyFlag || wasSpeaking) startSpeaking()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed.coerceIn(0.25f, 4f)
        tts?.setSpeechRate(speed)
        if (speaking) restartFromCurrentSegment()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        playWhenReadyFlag = false
        stopSpeaking()
        unregisterNoisy()
        abandonFocus()
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
        return Futures.immediateVoidFuture()
    }
}
