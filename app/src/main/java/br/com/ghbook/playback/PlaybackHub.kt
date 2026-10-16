package br.com.ghbook.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import br.com.ghbook.data.PlaybackMode
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PlaybackUiState(
    val bookId: String? = null,
    val bookTitle: String = "",
    val mode: PlaybackMode = PlaybackMode.TTS,
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val chapterCount: Int = 0,
    val paragraphIndex: Int = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    /** Descrição da fonte do áudio exibida ao usuário. */
    val sourceLabel: String = "",
    val voiceWarning: String? = null,
    val error: String? = null,
    val ended: Boolean = false,
)

/**
 * Ponte entre a interface e o serviço de reprodução (mesmo processo).
 * A interface chama os comandos aqui; o [PlaybackService] executa e publica o estado em [state].
 * Um MediaController é mantido conectado para iniciar/vincular o serviço.
 */
class PlaybackHub(private val context: Context) {

    interface Engine {
        fun play(bookId: String, chapter: Int?, paragraph: Int?, mode: PlaybackMode?)
        fun togglePlayPause()
        fun pause()
        fun stop()
        fun seekBy(deltaMs: Long)
        fun seekTo(positionMs: Long)
        fun nextChapter()
        fun previousChapter()
        fun setSpeed(speed: Float)
        fun setMode(mode: PlaybackMode)
        fun onBookDeleted(bookId: String)
        fun onAudioChanged(bookId: String)
    }

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var engine: Engine? = null
    private val pending = ArrayList<(Engine) -> Unit>()
    private var controllerFuture: ListenableFuture<MediaController>? = null

    fun attach(e: Engine) {
        engine = e
        val actions = pending.toList()
        pending.clear()
        actions.forEach { it(e) }
    }

    fun detach(e: Engine) {
        if (engine === e) engine = null
        _state.update { it.copy(isPlaying = false, isBuffering = false) }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    fun publish(transform: (PlaybackUiState) -> PlaybackUiState) = _state.update(transform)

    /** Garante que o serviço esteja criado (vinculado por um MediaController). */
    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, token).buildAsync().also { f ->
            f.addListener({
                if (runCatching { f.get() }.isFailure) controllerFuture = null
            }, MoreExecutors.directExecutor())
        }
    }

    private fun run(action: (Engine) -> Unit) {
        val e = engine
        if (e != null) action(e) else {
            pending += action
            connect()
        }
    }

    fun play(bookId: String, chapter: Int? = null, paragraph: Int? = null, mode: PlaybackMode? = null) =
        run { it.play(bookId, chapter, paragraph, mode) }

    fun togglePlayPause() = run { it.togglePlayPause() }
    fun pause() { engine?.pause() }
    fun stop() { engine?.stop() }
    fun seekBy(deltaMs: Long) = run { it.seekBy(deltaMs) }
    fun seekTo(positionMs: Long) = run { it.seekTo(positionMs) }
    fun nextChapter() = run { it.nextChapter() }
    fun previousChapter() = run { it.previousChapter() }
    fun setSpeed(speed: Float) = run { it.setSpeed(speed) }
    fun setMode(mode: PlaybackMode) = run { it.setMode(mode) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun onBookDeleted(bookId: String) = onMain {
        engine?.onBookDeleted(bookId)
        if (_state.value.bookId == bookId) _state.value = PlaybackUiState()
    }

    /** Pode ser chamado pelo worker de geração (thread de fundo): os players só podem ser usados na thread principal. */
    fun onAudioChanged(bookId: String) = onMain { engine?.onAudioChanged(bookId) }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }
}
