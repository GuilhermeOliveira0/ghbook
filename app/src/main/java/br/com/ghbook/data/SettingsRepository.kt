package br.com.ghbook.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReaderTheme(val label: String) { DARK("Escuro"), BLACK("Preto"), SEPIA("Sépia"), LIGHT("Claro") }
enum class ReaderFont(val label: String) { SERIF("Serifada"), SANS("Sem serifa") }

data class AppSettings(
    val fontSizeSp: Float = 19f,
    val lineHeight: Float = 1.6f,
    val paragraphSpacingDp: Float = 14f,
    val readerTheme: ReaderTheme = ReaderTheme.DARK,
    val readerFont: ReaderFont = ReaderFont.SERIF,
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    /** Nome da voz do TextToSpeech escolhida (vazio = automática, preferindo pt-BR offline). */
    val voiceName: String = "",
    val followNarration: Boolean = true,
    val preferGeneratedAudio: Boolean = true,
    /** Narração ao vivo com voz neural: automático (mede o aparelho), sempre Kokoro ou sempre a voz rápida. */
    val liveNeuralMode: br.com.ghbook.neural.LiveNeuralMode = br.com.ghbook.neural.LiveNeuralMode.AUTO,
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val current get() = _settings.value

    private fun load() = AppSettings(
        fontSizeSp = prefs.getFloat("fontSize", 19f),
        lineHeight = prefs.getFloat("lineHeight", 1.6f),
        paragraphSpacingDp = prefs.getFloat("paragraphSpacing", 14f),
        readerTheme = runCatching { ReaderTheme.valueOf(prefs.getString("readerTheme", null) ?: "DARK") }.getOrDefault(ReaderTheme.DARK),
        readerFont = runCatching { ReaderFont.valueOf(prefs.getString("readerFont", null) ?: "SERIF") }.getOrDefault(ReaderFont.SERIF),
        speechRate = prefs.getFloat("speechRate", 1.0f),
        pitch = prefs.getFloat("pitch", 1.0f),
        voiceName = prefs.getString("voiceName", "") ?: "",
        followNarration = prefs.getBoolean("followNarration", true),
        preferGeneratedAudio = prefs.getBoolean("preferGeneratedAudio", true),
        liveNeuralMode = runCatching { br.com.ghbook.neural.LiveNeuralMode.valueOf(prefs.getString("liveNeuralMode", null) ?: "AUTO") }
            .getOrDefault(br.com.ghbook.neural.LiveNeuralMode.AUTO),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_settings.value)
        _settings.value = s
        prefs.edit()
            .putFloat("fontSize", s.fontSizeSp)
            .putFloat("lineHeight", s.lineHeight)
            .putFloat("paragraphSpacing", s.paragraphSpacingDp)
            .putString("readerTheme", s.readerTheme.name)
            .putString("readerFont", s.readerFont.name)
            .putFloat("speechRate", s.speechRate)
            .putFloat("pitch", s.pitch)
            .putString("voiceName", s.voiceName)
            .putBoolean("followNarration", s.followNarration)
            .putBoolean("preferGeneratedAudio", s.preferGeneratedAudio)
            .putString("liveNeuralMode", s.liveNeuralMode.name)
            .apply()
    }
}
