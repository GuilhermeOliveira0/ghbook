package br.com.ghbook.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.navigation.NavHostController
import br.com.ghbook.AppGraph
import br.com.ghbook.neural.LiveNeuralMode
import br.com.ghbook.neural.NeuralEngine
import br.com.ghbook.neural.NeuralModel
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import br.com.ghbook.neural.NeuralPreview
import br.com.ghbook.tts.TtsVoices
import br.com.ghbook.tts.VoiceInfo
import br.com.ghbook.ui.theme.Success
import br.com.ghbook.ui.theme.Warning

private const val SAMPLE_TEXT = "Olá! Esta é a voz que vai narrar os seus livros. O capítulo 3 começa na página 42."

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val s by AppGraph.settings.settings.collectAsState()
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var voices by remember { mutableStateOf<List<VoiceInfo>>(emptyList()) }
    var autoLabel by remember { mutableStateOf<String?>(null) }
    var warning by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf<String?>(null) }
    var neuralError by remember { mutableStateOf<String?>(null) }
    var rtf by remember { mutableStateOf(NeuralEngine.kokoroRtf(context)) }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    LaunchedEffect(reload) {
        loading = true
        tts?.shutdown()
        val t = TtsVoices.create(context)
        tts = t
        if (t == null) {
            warning = "Nenhum mecanismo de síntese de voz (TTS) encontrado. Instale o \"Speech Services by Google\" (ou outro mecanismo) para ouvir os livros."
            voices = emptyList()
        } else {
            voices = TtsVoices.listVoices(t)
            val auto = TtsVoices.select(t, "")
            autoLabel = auto.info?.label
            warning = TtsVoices.select(t, s.voiceName).warning
        }
        loading = false
    }
    DisposableEffect(Unit) { onDispose { tts?.shutdown(); NeuralPreview.stop() } }

    fun speakSample() {
        val t = tts ?: return
        val sel = TtsVoices.select(t, s.voiceName)
        TtsVoices.apply(t, sel)
        t.setSpeechRate(s.speechRate)
        t.setPitch(s.pitch)
        t.speak(SAMPLE_TEXT, TextToSpeech.QUEUE_FLUSH, null, "sample")
    }

    fun openIntent(action: String) {
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    Scaffold(
        topBar = {
            BackTopBar("Configurações", onBack = { nav.backOrLibrary() })
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("Voz da narração", Modifier.padding(top = 8.dp))

            // Vozes neurais embutidas (Kokoro): padrão do app.
            Text("Vozes neurais · embutidas no app", style = MaterialTheme.typography.titleMedium)
            Text(
                "As mais naturais. Funcionam 100% offline, sem depender do mecanismo de voz do aparelho. " +
                    "Em aparelhos mais lentos a narração ao vivo pode fazer pequenas pausas para carregar — nesse caso, gere o áudio do livro em \"Áudios offline\".",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            neuralError?.let { InfoCard("Voz neural", it, accent = Warning, icon = Icons.Rounded.WarningAmber) }
            OutlinedCard {
                Column {
                    NeuralEngine.voices.forEachIndexed { i, v ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val selected = NeuralEngine.isNeural(s.voiceName) && NeuralEngine.voiceFor(s.voiceName) == v
                        Row(
                            Modifier.fillMaxWidth().clickable { AppGraph.settings.update { it.copy(voiceName = v.key) } }.padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = { AppGraph.settings.update { it.copy(voiceName = v.key) } })
                            Column(Modifier.weight(1f)) {
                                Text(v.name + if (v == NeuralEngine.defaultVoice) " (padrão)" else "", style = MaterialTheme.typography.bodyMedium)
                                Text(v.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (previewing == v.id) {
                                CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                            } else {
                                TextButton(onClick = {
                                    previewing = v.id
                                    neuralError = null
                                    NeuralPreview.play(context, v, NeuralModel.KOKORO, SAMPLE_TEXT, s.speechRate) { err ->
                                        mainHandler.post { previewing = null; neuralError = err; rtf = NeuralEngine.kokoroRtf(context) }
                                    }
                                }) {
                                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Testar voz ${v.name}", Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Testar")
                                }
                            }
                        }
                    }
                }
            }

            // Narração ao vivo: Kokoro só quando o aparelho acompanha a velocidade da fala.
            Text("Narração ao vivo com voz neural", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                LiveNeuralMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = s.liveNeuralMode == m,
                        onClick = { AppGraph.settings.update { it.copy(liveNeuralMode = m) } },
                        shape = SegmentedButtonDefaults.itemShape(i, LiveNeuralMode.entries.size),
                    ) { Text(m.label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                }
            }
            val r = rtf
            Text(
                when {
                    r == null -> "Automático: na primeira narração o app mede a velocidade deste aparelho. Se a Kokoro não acompanhar a fala, " +
                        "ao vivo usa a voz rápida do mesmo gênero (Dii ou Miro); o áudio gerado sempre usa a Kokoro."
                    r <= NeuralEngine.KOKORO_LIVE_MAX_RTF -> "Este aparelho sintetiza a Kokoro em %.1f× o tempo da fala: ao vivo usa a Kokoro.".format(java.util.Locale("pt", "BR"), r)
                    else -> "Este aparelho sintetiza a Kokoro em %.1f× o tempo da fala (mais lento que a fala): ao vivo usa a voz rápida. ".format(java.util.Locale("pt", "BR"), r) +
                        "Para ouvir com a Kokoro sem pausas, gere o áudio do livro."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text("Vozes do sistema · Android", style = MaterialTheme.typography.titleMedium)
            Text(
                "Usam o mecanismo de voz (TTS) instalado no aparelho. Vozes marcadas como \"offline\" funcionam sem internet; " +
                    "vozes \"requer internet\" são sintetizadas por um serviço online do fabricante do mecanismo.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!NeuralEngine.isNeural(s.voiceName)) warning?.let { InfoCard("Atenção", it, accent = Warning, icon = Icons.Rounded.WarningAmber) }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { speakSample() }, enabled = tts != null) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, null); Spacer(Modifier.padding(3.dp)); Text("Testar voz do sistema")
                }
                OutlinedButton(onClick = { reload++ }) { Text("Atualizar") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { openIntent("com.android.settings.TTS_SETTINGS") }) { Text("Configurações de TTS do Android") }
                TextButton(onClick = { openIntent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA) }) { Text("Instalar vozes") }
            }

            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            } else {
                OutlinedCard {
                    Column {
                        VoiceRow(
                            title = "Automática do sistema",
                            subtitle = "Melhor voz pt-BR offline do aparelho" + (autoLabel?.let { ": $it" } ?: ""),
                            selected = s.voiceName == TtsVoices.SYSTEM_AUTO,
                            badge = null,
                        ) { AppGraph.settings.update { it.copy(voiceName = TtsVoices.SYSTEM_AUTO) } }
                        val shown = if (showAll) voices else voices.filter { it.isPortuguese }
                        shown.forEach { v ->
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            VoiceRow(
                                title = v.label,
                                subtitle = "Qualidade ${v.quality}",
                                selected = s.voiceName == v.name,
                                badge = when {
                                    v.notInstalled -> "não instalada" to Warning
                                    v.requiresNetwork -> "requer internet" to Warning
                                    else -> "offline" to Success
                                },
                            ) { AppGraph.settings.update { it.copy(voiceName = v.name) } }
                        }
                        if (voices.none { it.isPortuguese }) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(
                                "Nenhuma voz do sistema em português encontrada. Toque em \"Instalar vozes\" e baixe Português (Brasil).",
                                Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall, color = Warning,
                            )
                        }
                    }
                }
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Mostrar só vozes em português" else "Mostrar todas as vozes (${voices.size})") }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SectionLabel("Reprodução")
            Text("Velocidade padrão: ${speedLabel(s.speechRate)}", style = MaterialTheme.typography.bodyMedium)
            BrandSlider(value = s.speechRate, onValueChange = { v -> AppGraph.playback.setSpeed(v) }, valueRange = 0.5f..3f)
            Text("Tom da voz: ${String.format(java.util.Locale("pt", "BR"), "%.2f", s.pitch)}", style = MaterialTheme.typography.bodyMedium)
            BrandSlider(value = s.pitch, onValueChange = { v -> AppGraph.settings.update { it.copy(pitch = v) } }, valueRange = 0.6f..1.6f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Preferir áudio gerado", style = MaterialTheme.typography.bodyMedium)
                    Text("Quando o capítulo já tem áudio gravado, tocar o arquivo em vez da voz ao vivo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = s.preferGeneratedAudio, onCheckedChange = { v -> AppGraph.settings.update { it.copy(preferGeneratedAudio = v) } })
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ReadingSettingsPanel()

            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SectionLabel("Como o conteúdo é usado")
            InfoCard("Texto extraído", "O texto do PDF é extraído e guardado no aparelho. Leitura, capítulos e progresso funcionam sem internet.")
            InfoCard("Narração ao vivo", "A voz (neural embutida ou do sistema) lê o texto em tempo real. A voz neural funciona sempre offline; a do sistema, só se estiver marcada como offline.")
            InfoCard("Áudio gerado", "Arquivos de áudio (.m4a) criados a partir da voz sintetizada e guardados no aparelho. Depois de gerados, tocam sem internet.")
            InfoCard(
                "Privacidade",
                "O app não tem permissão de internet: PDFs e textos nunca são enviados a servidores. O OCR de páginas digitalizadas usa um modelo local. " +
                    "As vozes neurais rodam inteiramente no aparelho. Se você escolher uma voz do sistema \"requer internet\", o texto narrado é processado pelo serviço do mecanismo de voz (ex.: Google), não por este app.",
            )

            // Rodapé com a marca e a versão instalada.
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
            }
            Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BrandMark(44.dp)
                Spacer(Modifier.height(10.dp))
                Wordmark(18.sp)
                Text("Leia e ouça seus livros", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (version.isNotEmpty()) {
                    Text("Versão $version", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun VoiceRow(title: String, subtitle: String, selected: Boolean, badge: Pair<String, androidx.compose.ui.graphics.Color>?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        badge?.let { (label, color) ->
            Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(50), modifier = Modifier.padding(end = 8.dp)) {
                Text(label, color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}
