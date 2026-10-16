package br.com.ghbook.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

data class VoiceInfo(
    val name: String,
    val locale: Locale,
    val quality: Int,
    val requiresNetwork: Boolean,
    val notInstalled: Boolean,
) {
    val isPortuguese get() = locale.language == "pt" || locale.language == "por"
    val isBrazil get() = isPortuguese && (locale.country.equals("BR", true) || locale.country.equals("BRA", true))
    val isOffline get() = !requiresNetwork && !notInstalled
    val label: String
        get() {
            val country = locale.getDisplayCountry(Locale("pt", "BR")).ifBlank { locale.country }
            val lang = locale.getDisplayLanguage(Locale("pt", "BR")).replaceFirstChar { it.uppercase() }
            return "$lang${if (country.isNotBlank()) " ($country)" else ""} · ${name.substringAfterLast('#').take(40)}"
        }
}

data class VoiceSelection(
    val voice: Voice?,
    val info: VoiceInfo?,
    /** Aviso exibido ao usuário (ex.: voz depende de internet, sem voz em português). */
    val warning: String?,
)

object TtsVoices {

    /** Cria e inicializa um TextToSpeech. Retorna null se nenhum mecanismo de TTS estiver disponível. */
    suspend fun create(context: Context): TextToSpeech? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            var tts: TextToSpeech? = null
            tts = TextToSpeech(context.applicationContext) { status ->
                if (cont.isActive) {
                    if (status == TextToSpeech.SUCCESS) cont.resume(tts) else {
                        runCatching { tts?.shutdown() }
                        cont.resume(null)
                    }
                }
            }
            cont.invokeOnCancellation { runCatching { tts?.shutdown() } }
        }
    }

    fun info(v: Voice) = VoiceInfo(
        name = v.name,
        locale = v.locale,
        quality = v.quality,
        requiresNetwork = v.isNetworkConnectionRequired ||
            v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS) == true &&
            v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS) != true,
        notInstalled = v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true,
    )

    fun listVoices(tts: TextToSpeech): List<VoiceInfo> =
        runCatching { tts.voices?.toList() ?: emptyList() }.getOrDefault(emptyList())
            .map(::info)
            .sortedWith(
                compareByDescending<VoiceInfo> { it.isBrazil }
                    .thenByDescending { it.isPortuguese }
                    .thenByDescending { it.isOffline }
                    .thenByDescending { it.quality }
                    .thenBy { it.name },
            )

    /**
     * Escolhe a voz: a preferida pelo usuário se existir; senão a melhor voz pt-BR offline,
     * depois pt offline, depois qualquer pt; se nada em português existir, mantém a padrão com aviso.
     */
    /** Valor salvo para "voz automática do sistema" (vazio significa a voz neural padrão). */
    const val SYSTEM_AUTO = "system:auto"

    fun select(tts: TextToSpeech, preferredRaw: String, requireOffline: Boolean = false): VoiceSelection {
        // Preferências de voz neural ou "automática do sistema" não se referem a uma voz do TTS.
        val preferredName = if (preferredRaw.startsWith("neural:") || preferredRaw == SYSTEM_AUTO) "" else preferredRaw
        val voices = runCatching { tts.voices?.toList() ?: emptyList() }.getOrDefault(emptyList())
        val infos = voices.associateWith(::info)
        val preferred = voices.firstOrNull { it.name == preferredName && preferredName.isNotEmpty() }
        if (preferred != null) {
            val i = infos.getValue(preferred)
            if (i.notInstalled) {
                // cai para a escolha automática abaixo
            } else if (i.requiresNetwork && requireOffline) {
                // idem
            } else {
                return VoiceSelection(
                    preferred, i,
                    if (i.requiresNetwork) "A voz escolhida depende de internet. Sem conexão a narração falhará — escolha uma voz offline nas Configurações." else null,
                )
            }
        }
        val ranked = voices.filter { !infos.getValue(it).notInstalled }.sortedWith(
            compareByDescending<Voice> { infos.getValue(it).isBrazil }
                .thenByDescending { infos.getValue(it).isPortuguese }
                .thenByDescending { infos.getValue(it).isOffline }
                .thenByDescending { it.quality },
        )
        val bestPt = ranked.firstOrNull { infos.getValue(it).isPortuguese && (infos.getValue(it).isOffline || !requireOffline) }
        if (bestPt != null) {
            val i = infos.getValue(bestPt)
            val warn = buildString {
                if (preferredName.isNotEmpty() && preferred == null) append("A voz escolhida não está mais disponível; usando outra voz em português. ")
                if (preferred != null && infos.getValue(preferred).notInstalled) append("A voz escolhida não está instalada; usando outra voz em português. ")
                if (i.requiresNetwork) append("Nenhuma voz em português offline encontrada: esta voz usa internet. Instale os dados de voz pt-BR nas configurações de TTS do Android para ouvir offline.")
                else if (!i.isBrazil) append("Nenhuma voz pt-BR instalada; usando ${i.locale.displayName}.")
            }.trim().ifEmpty { null }
            return VoiceSelection(bestPt, i, warn)
        }
        // Tentativa via idioma, para mecanismos que não expõem a lista de vozes.
        val langResult = runCatching { tts.setLanguage(Locale("pt", "BR")) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (langResult >= TextToSpeech.LANG_AVAILABLE) {
            val v = runCatching { tts.voice }.getOrNull()
            val i = v?.let(::info)
            val warn = when {
                i == null || i.notInstalled ->
                    "Os dados da voz em português ainda não estão instalados no aparelho. O mecanismo de voz pode baixá-los na primeira narração " +
                        "(requer internet uma única vez) — ou toque em \"Instalar vozes\" nas Configurações e baixe Português (Brasil). Depois disso a narração funciona offline."
                i.requiresNetwork -> "A voz em português disponível depende de internet."
                else -> null
            }
            return VoiceSelection(v, i, warn)
        }
        if (langResult == TextToSpeech.LANG_MISSING_DATA) {
            return VoiceSelection(
                null, null,
                "A voz em português precisa ser baixada: toque em \"Instalar vozes\" nas Configurações e instale Português (Brasil).",
            )
        }
        val fallback = runCatching { tts.defaultVoice }.getOrNull()
        return VoiceSelection(
            fallback, fallback?.let(::info),
            "Nenhuma voz em português está instalada neste aparelho. A narração usará a voz padrão (${fallback?.locale?.displayName ?: "desconhecida"}). " +
                "Instale a voz pt-BR em Configurações > Sistema > Idiomas > Saída de conversão de texto em voz.",
        )
    }

    fun apply(tts: TextToSpeech, selection: VoiceSelection) {
        selection.voice?.let { v -> runCatching { tts.voice = v } }
    }
}
