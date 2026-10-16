package br.com.ghbook.ui

import androidx.compose.runtime.getValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import br.com.ghbook.ui.theme.BrandNavyLight
import br.com.ghbook.ui.theme.Hero
import br.com.ghbook.ui.theme.OnHero
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import br.com.ghbook.AppGraph
import br.com.ghbook.tts.SpeechText
import br.com.ghbook.ui.theme.Accent
import br.com.ghbook.ui.theme.Success

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersScreen(nav: NavHostController, bookId: String) {
    val meta = remember(bookId) { AppGraph.books.book(bookId) }
    val toc = remember(bookId) { AppGraph.books.toc(bookId) }
    val progressMap by AppGraph.books.progress.collectAsState()
    val playback by AppGraph.playback.state.collectAsState()
    val audioVersion by AppGraph.audio.version.collectAsState()
    val manifest = remember(bookId, audioVersion) { AppGraph.audio.manifest(bookId) }
    val progress = progressMap[bookId] ?: AppGraph.books.progressOf(bookId)
    val percent = AppGraph.books.percent(bookId, progress)
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { if (progress.chapter > 2) listState.scrollToItem(progress.chapter) }

    Scaffold(
        topBar = {
            BackTopBar("Capítulos e progresso", onBack = { nav.backOrLibrary() }, subtitle = meta?.title)
        },
    ) { pad ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Surface(color = Hero, contentColor = OnHero, shape = RoundedCornerShape(24.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        SectionLabel("Progresso", color = OnHero.copy(alpha = 0.6f))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${(percent * 100).toInt()}%", style = MaterialTheme.typography.displaySmall)
                            Spacer(Modifier.width(8.dp))
                            Text("do livro", style = MaterialTheme.typography.bodyMedium, color = OnHero.copy(alpha = 0.6f), modifier = Modifier.padding(bottom = 8.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                        ProgressLine(percent, color = BrandNavyLight, track = OnHero.copy(alpha = 0.15f))
                        Spacer(Modifier.height(12.dp))
                        val totalMs = toc.sumOf { it.charCount } * SpeechText.MS_PER_CHAR
                        Text(
                            "${toc.size} capítulos · ${meta?.pageCount ?: 0} páginas · ≈ ${formatDuration(totalMs)} de narração" +
                                if (manifest.chapters.isNotEmpty()) "\nÁudio gerado: ${manifest.chapters.size} de ${toc.size} capítulos" else "",
                            style = MaterialTheme.typography.bodySmall, color = OnHero.copy(alpha = 0.7f),
                        )
                        Text(
                            "Parou em: capítulo ${progress.chapter + 1}, parágrafo ${progress.paragraph + 1}",
                            style = MaterialTheme.typography.bodySmall, color = OnHero.copy(alpha = 0.7f),
                        )
                    }
                }
            }
            items(toc, key = { it.index }) { entry ->
                val isCurrent = entry.index == progress.chapter
                val isPlayingHere = playback.bookId == bookId && playback.chapterIndex == entry.index && playback.isPlaying
                val done = entry.index < progress.chapter
                val audio = manifest.forChapter(entry.index)
                Surface(
                    color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().clickable { nav.navigate(Routes.reader(bookId, entry.index, 0)) },
                ) {
                    Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${entry.index + 1}".padStart(2, '0'),
                            style = MaterialTheme.typography.titleMedium, color = if (isCurrent) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(34.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val pages = if (entry.startPage == entry.endPage) "pág. ${entry.startPage}" else "pág. ${entry.startPage}–${entry.endPage}"
                                val dur = audio?.durationMs ?: (entry.charCount * SpeechText.MS_PER_CHAR)
                                Text(
                                    "$pages · ${if (audio != null) "" else "≈ "}${formatDuration(dur)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (audio != null) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Rounded.DownloadDone, "Áudio gerado disponível", tint = Success, modifier = Modifier.size(14.dp))
                                }
                                if (done) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Rounded.CheckCircle, "Lido", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                                }
                            }
                            if (isCurrent && entry.paragraphCount > 0) {
                                Spacer(Modifier.height(4.dp))
                                ProgressLine(progress.paragraph.toFloat() / entry.paragraphCount, height = 3.dp)
                            }
                        }
                        IconButton(onClick = {
                            AppGraph.playback.play(bookId, entry.index, 0)
                            nav.navigate(Routes.player(bookId))
                        }) {
                            Icon(if (isPlayingHere) Icons.Rounded.GraphicEq else Icons.Rounded.PlayCircle, "Ouvir capítulo ${entry.index + 1}", tint = Accent)
                        }
                    }
                }
            }
        }
    }
}
