package br.com.ghbook.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Toc
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import br.com.ghbook.AppGraph
import br.com.ghbook.data.AudioManifest
import br.com.ghbook.data.PlaybackMode
import br.com.ghbook.ui.theme.Accent
import br.com.ghbook.ui.theme.Success
import br.com.ghbook.ui.theme.Warning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(nav: NavHostController, bookId: String) {
    val meta = remember(bookId) { AppGraph.books.book(bookId) }
    val toc = remember(bookId) { AppGraph.books.toc(bookId) }
    val st by AppGraph.playback.state.collectAsState()
    val audioVersion by AppGraph.audio.version.collectAsState()
    val manifest by produceState(AudioManifest(), bookId, audioVersion) {
        value = withContext(Dispatchers.IO) { AppGraph.audio.manifest(bookId) }
    }
    val isThisBook = st.bookId == bookId
    var showSpeed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { AppGraph.playback.connect() }

    if (meta == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Livro não encontrado.")
                TextButton(onClick = { nav.backOrLibrary() }) { Text("Voltar") }
            }
        }
        return
    }

    val chapterIdx = if (isThisBook) st.chapterIndex else AppGraph.books.progressOf(bookId).chapter.coerceIn(0, maxOf(0, toc.lastIndex))
    val paragraphIdx = if (isThisBook) st.paragraphIndex else AppGraph.books.progressOf(bookId).paragraph
    val currentText by produceState("", bookId, chapterIdx, paragraphIdx) {
        value = AppGraph.books.chapter(bookId, chapterIdx)?.paragraphs?.getOrNull(paragraphIdx)?.text ?: ""
    }

    Scaffold(
        topBar = {
            BackTopBar(
                title = "Ouvindo",
                onBack = { nav.backOrLibrary() },
                backIcon = Icons.Rounded.KeyboardArrowDown,
                backLabel = "Fechar player",
            ) {
                IconButton(onClick = { nav.navigate(Routes.chapters(bookId)) }) { Icon(Icons.Rounded.Toc, "Capítulos") }
                IconButton(onClick = { nav.navigate(Routes.reader(bookId, chapterIdx, paragraphIdx)) }) { Icon(Icons.AutoMirrored.Rounded.MenuBook, "Abrir texto") }
            }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            // Em telas baixas a capa encolhe para os controles caberem sem rolar.
            val compact = LocalConfiguration.current.screenHeightDp < 720
            val coverWidth = if (compact) 76.dp else 168.dp
            BookCover(
                bookId, meta.title, width = coverWidth,
                modifier = Modifier.shadow(18.dp, RoundedCornerShape(10.dp), ambientColor = Accent, spotColor = Accent),
            )
            Spacer(Modifier.height(if (compact) 14.dp else 24.dp))
            Text(meta.title, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                (toc.getOrNull(chapterIdx)?.title?.let { "$it · " } ?: "") + "${chapterIdx + 1} de ${toc.size}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(if (compact) 12.dp else 20.dp))

            // Trecho sendo narrado (sincronização aproximada com o texto).
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    currentText.ifEmpty { "Toque em reproduzir para ouvir." },
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif, fontStyle = if (currentText.isEmpty()) FontStyle.Italic else FontStyle.Normal),
                    color = if (currentText.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = if (compact) 3 else 4, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))

            // Progresso do capítulo
            var dragging by remember { mutableStateOf<Float?>(null) }
            val dur = if (isThisBook) st.durationMs else 0
            val pos = if (isThisBook) st.positionMs else 0
            BrandSlider(
                value = dragging ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { f -> if (dur > 0) AppGraph.playback.seekTo((f * dur).toLong()) }
                    dragging = null
                },
                enabled = isThisBook && dur > 0,
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatTime(dragging?.let { (it * dur).toLong() } ?: pos), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                // Progresso do livro
                val bookPercent = if (isThisBook) {
                    val before = toc.take(chapterIdx).sumOf { it.charCount }
                    val total = toc.sumOf { it.charCount }.coerceAtLeast(1)
                    val within = if (dur > 0) (toc.getOrNull(chapterIdx)?.charCount ?: 0) * pos / dur else 0
                    ((before + within).toFloat() / total).coerceIn(0f, 1f)
                } else AppGraph.books.percent(bookId)
                Text("Livro ${(bookPercent * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(
                    (if (st.mode == PlaybackMode.TTS) "≈ " else "") + formatTime(dur),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { AppGraph.playback.previousChapter() }, enabled = isThisBook) { Icon(Icons.Rounded.SkipPrevious, "Capítulo anterior", Modifier.size(28.dp)) }
                IconButton(onClick = { AppGraph.playback.seekBy(-10_000) }, enabled = isThisBook, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.Replay10, "Voltar 10 segundos", Modifier.size(30.dp)) }
                Spacer(Modifier.width(8.dp))
                PlayPauseButton(
                    playing = isThisBook && st.isPlaying,
                    buffering = isThisBook && st.isBuffering,
                    onClick = { if (isThisBook) AppGraph.playback.togglePlayPause() else AppGraph.playback.play(bookId) },
                    size = 76.dp,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { AppGraph.playback.seekBy(30_000) }, enabled = isThisBook, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.Forward30, "Avançar 30 segundos", Modifier.size(30.dp)) }
                IconButton(onClick = { AppGraph.playback.nextChapter() }, enabled = isThisBook && chapterIdx < toc.lastIndex) { Icon(Icons.Rounded.SkipNext, "Próximo capítulo", Modifier.size(28.dp)) }
            }

            Spacer(Modifier.height(16.dp))
            val speed = if (isThisBook) st.speed else AppGraph.settings.current.speechRate
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { showSpeed = true },
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.height(40.dp),
                ) {
                    Icon(Icons.Rounded.Speed, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Velocidade ${speedLabel(speed)}")
                }
                OutlinedButton(
                    onClick = { AppGraph.playback.stop() },
                    enabled = isThisBook && (st.isPlaying || st.positionMs > 0),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.height(40.dp),
                ) {
                    Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Parar")
                }
            }

            Spacer(Modifier.height(28.dp))
            SectionLabel("Fonte do áudio", Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            val hasAudioHere = manifest.forChapter(chapterIdx) != null
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !isThisBook || st.mode == PlaybackMode.TTS,
                    onClick = { if (isThisBook) AppGraph.playback.setMode(PlaybackMode.TTS) else AppGraph.playback.play(bookId, mode = PlaybackMode.TTS) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Ao vivo") }
                SegmentedButton(
                    selected = isThisBook && st.mode == PlaybackMode.AUDIO,
                    onClick = { if (isThisBook) AppGraph.playback.setMode(PlaybackMode.AUDIO) else AppGraph.playback.play(bookId, mode = PlaybackMode.AUDIO) },
                    enabled = hasAudioHere,
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Áudio gerado") }
            }
            if (isThisBook && st.sourceLabel.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    st.sourceLabel, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    color = if (st.mode == PlaybackMode.AUDIO) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!hasAudioHere) {
                TextButton(onClick = { nav.navigate(Routes.audio(bookId)) }) {
                    Text(
                        if (manifest.chapters.isEmpty()) "Gerar áudio offline deste livro" else "Este capítulo ainda não tem áudio gerado · gerenciar",
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (isThisBook) {
                st.voiceWarning?.let { Spacer(Modifier.height(8.dp)); InfoCard("Voz", it, accent = Warning, icon = Icons.Rounded.WarningAmber) }
                st.error?.let { Spacer(Modifier.height(8.dp)); ErrorBanner(it, onDismiss = { AppGraph.playback.clearError() }) }
                if (st.ended) {
                    Spacer(Modifier.height(12.dp))
                    Text("Fim do livro.", style = MaterialTheme.typography.titleSmall, color = Accent)
                    TextButton(onClick = { AppGraph.playback.play(bookId, 0, 0) }) { Text("Ouvir do início") }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showSpeed) {
        val current = if (isThisBook) st.speed else AppGraph.settings.current.speechRate
        SpeedSheet(current, onDismiss = { showSpeed = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSheet(speed: Float, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            var fine by remember(speed) { mutableStateOf(speed) }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Velocidade", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(speedLabel(fine), style = MaterialTheme.typography.headlineSmall, color = Accent)
            }
            // Grade 3 × 2 de pílulas com largura igual (cabe em telas pequenas sem quebrar linha sozinha).
            SPEEDS.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { s ->
                        val selected = kotlin.math.abs(speed - s) < 0.01f
                        Surface(
                            onClick = { AppGraph.playback.setSpeed(s) },
                            selected = selected,
                            shape = RoundedCornerShape(50),
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) { Text(speedLabel(s), style = MaterialTheme.typography.labelLarge, maxLines = 1) }
                        }
                    }
                }
            }
            BrandSlider(
                value = fine, onValueChange = { fine = it }, onValueChangeFinished = { AppGraph.playback.setSpeed(fine) },
                valueRange = 0.5f..3f,
            )
            Row {
                Text("0,5×", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("3×", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
