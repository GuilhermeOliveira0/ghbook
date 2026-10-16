package br.com.ghbook.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import br.com.ghbook.AppGraph
import br.com.ghbook.MainActivity
import br.com.ghbook.data.BookStatus
import br.com.ghbook.data.ChapterAudio
import br.com.ghbook.data.PlaybackMode
import br.com.ghbook.data.ReadingProgress
import br.com.ghbook.data.TocEntry
import br.com.ghbook.tts.SpeechText
import br.com.ghbook.neural.NeuralEngine
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Serviço de mídia em primeiro plano: mantém a narração tocando com a tela bloqueada e publica
 * os controles (notificação, tela de bloqueio, fones Bluetooth) via MediaSession.
 * Alterna entre dois players: [TtsPlayer] (síntese ao vivo) e ExoPlayer (áudio gerado armazenado).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService(), PlaybackHub.Engine {

    private lateinit var ttsPlayer: TtsPlayer
    private lateinit var neuralPlayer: NeuralPlayer
    /** Voz neural embutida (Kokoro) ou voz do sistema (TextToSpeech do Android). */
    private var useNeural = true
    private lateinit var exo: ExoPlayer
    private var session: MediaSession? = null
    private val scope = MainScope()
    private val hub get() = AppGraph.playback

    private var bookId: String? = null
    private var bookTitle = ""
    private var toc: List<TocEntry> = emptyList()
    private var mode = PlaybackMode.TTS
    private var audioChapters: List<ChapterAudio> = emptyList()
    private var currentChapter = 0
    private var currentParagraph = 0
    private var lastSavedAt = 0L
    private var voiceLabel = ""
    private var voiceOffline = true
    private var voiceWarning: String? = null

    private val livePlayer: Player get() = if (useNeural) neuralPlayer else ttsPlayer
    private val activePlayer: Player get() = if (mode == PlaybackMode.AUDIO) exo else livePlayer

    private fun liveSetBook(title: String, cover: ByteArray?, items: List<TtsPlayer.ChapterItem>, ch: Int, para: Int) =
        if (useNeural) neuralPlayer.setBook(title, cover, items, ch, para) else ttsPlayer.setBook(title, cover, items, ch, para)

    private fun livePlayFrom(ch: Int, para: Int) =
        if (useNeural) neuralPlayer.playFromParagraph(ch, para) else ttsPlayer.playFromParagraph(ch, para)

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(applicationContext)
        val loader: (Int) -> br.com.ghbook.data.Chapter? = { idx -> bookId?.let { AppGraph.books.chapterBlocking(it, idx) } }
        ttsPlayer = TtsPlayer(this, loader, liveListener(neural = false))
        neuralPlayer = NeuralPlayer(this, loader, liveListener(neural = true))
        useNeural = NeuralEngine.isNeural(AppGraph.settings.current.voiceName)
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        exo.addListener(exoListener)
        ttsPlayer.addListener(commonListener)
        neuralPlayer.addListener(commonListener)
        exo.addListener(commonListener)

        val openIntent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(this, 1, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, livePlayer).setSessionActivity(pi).setCallback(sessionCallback).build()

        val s = AppGraph.settings.current
        ttsPlayer.setVoicePreference(s.voiceName, s.pitch)
        neuralPlayer.setVoicePreference(s.voiceName, s.pitch, s.liveNeuralMode)
        ttsPlayer.setPlaybackSpeed(s.speechRate)
        neuralPlayer.setPlaybackSpeed(s.speechRate)
        exo.setPlaybackSpeed(s.speechRate)
        hub.publish { it.copy(speed = s.speechRate) }

        scope.launch {
            AppGraph.settings.settings.collect { st ->
                ttsPlayer.setVoicePreference(st.voiceName, st.pitch)
                neuralPlayer.setVoicePreference(st.voiceName, st.pitch, st.liveNeuralMode)
                val neural = NeuralEngine.isNeural(st.voiceName)
                if (neural != useNeural) switchLiveEngine(neural)
            }
        }
        scope.launch {
            while (isActive) {
                delay(400)
                tick()
            }
        }
        hub.attach(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            saveProgress(force = true)
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveProgress(force = true)
        hub.detach(this)
        scope.cancel()
        session?.release()
        session = null
        ttsPlayer.release()
        neuralPlayer.release()
        exo.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ listeners

    /** Tecla de mídia/retomada com o serviço parado: continua o último livro de onde parou. */
    private val sessionCallback = object : MediaSession.Callback {
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val last = AppGraph.books.books.value
                .filter { it.status == BookStatus.READY }
                .maxByOrNull { maxOf(it.lastOpenedAt, AppGraph.books.progressOf(it.id).updatedAt) }
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nenhum livro para retomar"))
            play(last.id, null, null, null)
            val p = activePlayer
            val items = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, p.currentMediaItemIndex, p.currentPosition))
        }
    }

    /** Troca entre voz neural e voz do sistema durante a narração, continuando do mesmo trecho. */
    private fun switchLiveEngine(neural: Boolean) {
        val wasLive = mode == PlaybackMode.TTS
        val wasPlaying = wasLive && livePlayer.playWhenReady
        if (wasLive) {
            livePlayer.pause()
            livePlayer.stop()
        }
        useNeural = neural
        voiceLabel = ""
        val id = bookId
        if (wasLive && id != null) {
            if (session?.player !== livePlayer) session?.player = livePlayer
            if (wasPlaying) play(id, currentChapter, currentParagraph, PlaybackMode.TTS)
        }
        publish()
    }

    private fun liveListener(neural: Boolean) = object : TtsPlayer.Listener {
        override fun onParagraph(chapter: Int, paragraph: Int, positionMs: Long) {
            if (mode != PlaybackMode.TTS || neural != useNeural) return
            val changedChapter = chapter != currentChapter
            currentChapter = chapter
            currentParagraph = paragraph
            publish()
            saveProgress(force = changedChapter)
        }

        override fun onError(message: String) {
            if (neural != useNeural) return
            hub.publish { it.copy(error = message) }
        }

        override fun onVoice(label: String, offline: Boolean, warning: String?) {
            if (neural != useNeural) return
            voiceLabel = label
            voiceOffline = offline
            voiceWarning = warning
            publish()
        }
    }

    private val commonListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player === activePlayer) publish()
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && !player.isPlaying) saveProgress(force = true)
        }
    }

    private val exoListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mode != PlaybackMode.AUDIO || mediaItem == null) return
            // setMediaItems() em play() já definiu capítulo e parágrafo; zerar o parágrafo aqui gravaria o progresso errado.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            val newChapter = mediaItem.mediaId.removePrefix("audio:").toIntOrNull() ?: return
            val expected = currentChapter + 1
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && newChapter != expected && expected < toc.size) {
                // Próximo capítulo ainda não tem áudio gerado: continua com a voz sintetizada.
                switchToTtsFor(expected, 0)
                return
            }
            currentChapter = newChapter
            currentParagraph = 0
            publish()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (mode == PlaybackMode.AUDIO && playbackState == Player.STATE_ENDED) {
                val next = currentChapter + 1
                if (next < toc.size) switchToTtsFor(next, 0)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            hub.publish { it.copy(error = "Falha ao reproduzir o áudio gerado: ${error.errorCodeName}. Tente gerar o capítulo novamente.") }
        }
    }

    private fun switchToTtsFor(chapter: Int, paragraph: Int) {
        val id = bookId ?: return
        hub.publish { it.copy(error = "Capítulo ${chapter + 1} ainda não tem áudio gerado — continuando com a narração ao vivo.") }
        play(id, chapter, paragraph, PlaybackMode.TTS)
    }

    // ------------------------------------------------------------------ estado

    private fun tick() {
        if (mode == PlaybackMode.AUDIO && exo.isPlaying) {
            val a = audioChapters.firstOrNull { it.chapter == currentChapter }
            if (a != null) {
                val pos = exo.currentPosition
                val idx = a.paragraphStartMs.indexOfLast { it <= pos }.coerceAtLeast(0)
                if (idx != currentParagraph) currentParagraph = idx
            }
            saveProgress(force = false)
        }
        if (activePlayer.isPlaying) publish()
    }

    private fun publish() {
        val p = activePlayer
        val title = toc.getOrNull(currentChapter)?.title ?: ""
        val source = when (mode) {
            PlaybackMode.TTS -> {
                val label = voiceLabel.ifEmpty { if (useNeural) "Voz neural" else "Voz do sistema" }
                "Narração ao vivo · $label · " + if (voiceOffline) "offline" else "requer internet"
            }
            PlaybackMode.AUDIO -> "Áudio gerado armazenado no aparelho (offline)"
        }
        hub.publish {
            it.copy(
                bookId = bookId,
                bookTitle = bookTitle,
                mode = mode,
                chapterIndex = currentChapter,
                chapterTitle = title,
                chapterCount = toc.size,
                paragraphIndex = currentParagraph,
                isPlaying = p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING),
                isBuffering = p.playWhenReady && p.playbackState == Player.STATE_BUFFERING,
                positionMs = p.currentPosition.coerceAtLeast(0),
                durationMs = p.duration.takeIf { d -> d != C.TIME_UNSET && d > 0 } ?: 0,
                speed = p.playbackParameters.speed,
                sourceLabel = source,
                voiceWarning = if (mode == PlaybackMode.TTS) voiceWarning else null,
                ended = p.playbackState == Player.STATE_ENDED,
            )
        }
    }

    private fun saveProgress(force: Boolean) {
        val id = bookId ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSavedAt < 3000) return
        lastSavedAt = now
        AppGraph.books.saveProgress(
            id,
            ReadingProgress(
                chapter = currentChapter,
                paragraph = currentParagraph,
                audioPositionMs = if (mode == PlaybackMode.AUDIO) exo.currentPosition else 0,
                lastMode = mode.name,
            ),
        )
    }

    private fun usePlayer(newMode: PlaybackMode) {
        if (mode != newMode) {
            activePlayer.pause()
            mode = newMode
        }
        val target = activePlayer
        if (session?.player !== target) session?.player = target
    }

    // ------------------------------------------------------------------ comandos (PlaybackHub.Engine)

    override fun play(bookId: String, chapter: Int?, paragraph: Int?, mode: PlaybackMode?) {
        val meta = AppGraph.books.book(bookId)
        if (meta == null || meta.status != BookStatus.READY) {
            hub.publish { it.copy(error = "Livro indisponível para narração.") }
            return
        }
        if (this.bookId != bookId) saveProgress(force = true)
        val toc = AppGraph.books.toc(bookId)
        if (toc.isEmpty()) {
            hub.publish { it.copy(error = "Este livro não possui texto extraído para narrar.") }
            return
        }
        val progress = AppGraph.books.progressOf(bookId)
        val ch = (chapter ?: progress.chapter).coerceIn(0, toc.lastIndex)
        val para = paragraph ?: if (chapter == null) progress.paragraph else 0
        val manifest = AppGraph.audio.manifest(bookId)
        val settings = AppGraph.settings.current
        var chosen = mode ?: if (manifest.forChapter(ch) != null && (settings.preferGeneratedAudio || progress.lastMode == PlaybackMode.AUDIO.name)) {
            PlaybackMode.AUDIO
        } else PlaybackMode.TTS
        if (chosen == PlaybackMode.AUDIO && manifest.forChapter(ch) == null) {
            hub.publish { it.copy(error = "Este capítulo ainda não tem áudio gerado — usando a narração ao vivo.") }
            chosen = PlaybackMode.TTS
        }

        val bookChanged = this.bookId != bookId
        this.bookId = bookId
        this.bookTitle = meta.title
        this.toc = toc
        currentChapter = ch
        currentParagraph = para
        // Capa enviada como bytes: a interface do sistema não tem permissão para ler arquivos privados do app.
        val cover = AppGraph.books.coverFile(bookId).takeIf { it.exists() }?.let { runCatching { it.readBytes() }.getOrNull() }
        if (bookChanged || chosen != this.mode) hub.publish { it.copy(error = null) }
        usePlayer(chosen)

        when (chosen) {
            PlaybackMode.TTS -> {
                val live = livePlayer
                if (bookChanged || live.mediaItemCount != toc.size || live.mediaItemCount == 0) {
                    val msPerChar = if (useNeural) NeuralEngine.MS_PER_CHAR else SpeechText.MS_PER_CHAR
                    liveSetBook(
                        meta.title, cover,
                        toc.map { TtsPlayer.ChapterItem(it.index, it.title, it.charCount * msPerChar) },
                        ch, para,
                    )
                }
                livePlayFrom(ch, para)
            }
            PlaybackMode.AUDIO -> {
                audioChapters = manifest.chapters
                val items = audioChapters.map { a ->
                    MediaItem.Builder()
                        .setMediaId("audio:${a.chapter}")
                        .setUri(Uri.fromFile(File(AppGraph.audio.dir(bookId), a.file)))
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(toc.getOrNull(a.chapter)?.title ?: "Capítulo ${a.chapter + 1}")
                                .setArtist(meta.title)
                                .setAlbumTitle(meta.title)
                                .setArtworkData(cover, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                .build(),
                        ).build()
                }
                val index = audioChapters.indexOfFirst { it.chapter == ch }
                val a = audioChapters[index]
                val pos = if (chapter == null && paragraph == null && progress.lastMode == PlaybackMode.AUDIO.name && progress.chapter == ch && progress.audioPositionMs > 0) {
                    progress.audioPositionMs
                } else a.paragraphStartMs.getOrNull(para) ?: 0L
                exo.setMediaItems(items, index, pos)
                exo.prepare()
                exo.play()
            }
        }
        publish()
        saveProgress(force = true)
    }

    override fun togglePlayPause() {
        val p = activePlayer
        if (p.mediaItemCount == 0) {
            bookId?.let { play(it, null, null, null) }
            return
        }
        // Fim do livro: tocar de novo recomeça do início (no estado ENDED o player não reage a play()).
        if (p.playbackState == Player.STATE_ENDED) {
            bookId?.let { play(it, 0, 0, null) }
            return
        }
        if (p.playWhenReady && p.playbackState != Player.STATE_ENDED) p.pause() else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            if (mode == PlaybackMode.TTS) livePlayFrom(currentChapter, currentParagraph) else p.play()
        }
        publish()
    }

    override fun pause() {
        activePlayer.pause()
        publish()
    }

    override fun stop() {
        saveProgress(force = true)
        activePlayer.pause()
        activePlayer.stop()
        publish()
    }

    override fun seekBy(deltaMs: Long) {
        val p = activePlayer
        if (p.mediaItemCount == 0) return
        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0, maxOf(0, dur - 500)))
        publish()
    }

    override fun seekTo(positionMs: Long) {
        val p = activePlayer
        if (p.mediaItemCount == 0) return
        p.seekTo(positionMs.coerceAtLeast(0))
        if (mode == PlaybackMode.AUDIO) {
            audioChapters.firstOrNull { it.chapter == currentChapter }?.let { a ->
                currentParagraph = a.paragraphStartMs.indexOfLast { it <= positionMs }.coerceAtLeast(0)
            }
        }
        publish()
    }

    override fun nextChapter() {
        val id = bookId ?: return
        if (currentChapter + 1 >= toc.size) return
        val target = currentChapter + 1
        val wasPlaying = activePlayer.playWhenReady
        if (mode == PlaybackMode.AUDIO && audioChapters.none { it.chapter == target }) {
            play(id, target, 0, PlaybackMode.TTS)
        } else {
            play(id, target, 0, mode)
        }
        if (!wasPlaying) pause()
    }

    override fun previousChapter() {
        val id = bookId ?: return
        val p = activePlayer
        val wasPlaying = p.playWhenReady
        val target = if (p.currentPosition > 5000 || currentChapter == 0) currentChapter else currentChapter - 1
        val m = if (mode == PlaybackMode.AUDIO && audioChapters.none { it.chapter == target }) PlaybackMode.TTS else mode
        play(id, target, 0, m)
        if (!wasPlaying) pause()
    }

    override fun setSpeed(speed: Float) {
        val s = speed.coerceIn(0.5f, 3f)
        ttsPlayer.setPlaybackSpeed(s)
        neuralPlayer.setPlaybackSpeed(s)
        exo.setPlaybackSpeed(s)
        AppGraph.settings.update { it.copy(speechRate = s) }
        publish()
    }

    override fun setMode(mode: PlaybackMode) {
        val id = bookId ?: return
        if (mode == this.mode) return
        play(id, currentChapter, currentParagraph, mode)
    }

    override fun onBookDeleted(bookId: String) {
        if (this.bookId != bookId) return
        exo.stop()
        exo.clearMediaItems()
        ttsPlayer.stop()
        ttsPlayer.setBook("", null, emptyList(), 0, 0)
        neuralPlayer.stop()
        neuralPlayer.setBook("", null, emptyList(), 0, 0)
        this.bookId = null
        toc = emptyList()
        audioChapters = emptyList()
    }

    override fun onAudioChanged(bookId: String) {
        if (this.bookId != bookId || mode != PlaybackMode.AUDIO) return
        val m = AppGraph.audio.manifest(bookId)
        if (m.forChapter(currentChapter) == null) {
            exo.stop()
            exo.clearMediaItems()
            usePlayer(PlaybackMode.TTS)
            publish()
        }
    }
}
