package br.com.ghbook.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.work.WorkInfo
import br.com.ghbook.AppGraph
import br.com.ghbook.audio.AudioGenerationWorker
import br.com.ghbook.data.BookMeta
import br.com.ghbook.data.BookStatus
import br.com.ghbook.data.PlaybackMode
import br.com.ghbook.tts.SpeechText
import br.com.ghbook.ui.theme.Accent
import br.com.ghbook.ui.theme.Danger
import br.com.ghbook.ui.theme.Success
import br.com.ghbook.ui.theme.Warning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioManagerScreen(nav: NavHostController, bookId: String?) {
    val books by AppGraph.books.books.collectAsState()
    val version by AppGraph.audio.version.collectAsState()
    val ready = books.filter { it.status == BookStatus.READY }.let { list ->
        if (bookId != null) list.sortedByDescending { it.id == bookId } else list
    }
    val free = remember(version) { AppGraph.books.booksDir.usableSpace }
    val used = remember(version, books) { ready.sumOf { AppGraph.audio.sizeBytes(it.id) } }

    Scaffold(
        topBar = {
            BackTopBar("Áudios offline", onBack = { nav.backOrLibrary() })
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                InfoCard(
                    "Áudio gerado × narração ao vivo",
                    "Ao vivo, a voz lê o texto em tempo real. O áudio gerado é gravado uma vez em arquivos .m4a " +
                        "(um por capítulo, ~0,5 MB por minuto) e depois toca sem internet e sem depender do mecanismo de voz. " +
                        "A geração usa a voz escolhida em Configurações (a voz neural Dora, por padrão) e roda em segundo plano; se for interrompida, os capítulos prontos são mantidos. " +
                        "Com a voz neural, gerar leva mais ou menos o tempo do próprio áudio, dependendo do aparelho.",
                )
            }
            item {
                Text("Usado por áudios: ${formatBytes(used)} · Livre no aparelho: ${formatBytes(free)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ready.isEmpty()) {
                item { Text("Nenhum livro pronto. Importe um PDF primeiro.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(ready, key = { it.id }) { book -> BookAudioCard(nav, book, version, highlighted = book.id == bookId) }
        }
    }
}

@Composable
private fun BookAudioCard(nav: NavHostController, book: BookMeta, version: Int, highlighted: Boolean) {
    val toc = remember(book.id) { AppGraph.books.toc(book.id) }
    val manifest = remember(book.id, version) { AppGraph.audio.manifest(book.id) }
    val work by remember(book.id) { AppGraph.generator.observe(book.id) }.collectAsState(initial = null)
    val size = remember(book.id, version) { AppGraph.audio.sizeBytes(book.id) }
    var confirmDelete by remember { mutableStateOf(false) }

    val running = work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED || work?.state == WorkInfo.State.BLOCKED
    val doneCount = manifest.chapters.count { c -> toc.any { it.index == c.chapter } }
    val complete = toc.isNotEmpty() && doneCount >= toc.size
    val estimateMs = toc.sumOf { it.charCount } * SpeechText.MS_PER_CHAR
    val generatedMs = manifest.chapters.sumOf { it.durationMs }

    OutlinedCard(highlighted = highlighted) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookCover(book.id, book.title, width = 46.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "$doneCount de ${toc.size} capítulos · ${formatBytes(size)}" + if (generatedMs > 0) " · ${formatDuration(generatedMs)}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (complete) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ProgressLine(if (toc.isEmpty()) 0f else doneCount.toFloat() / toc.size, color = if (complete) Success else Accent)
            if (running) {
                val p = work?.progress
                val msg = p?.getString(AudioGenerationWorker.KEY_MESSAGE) ?: if (work?.state == WorkInfo.State.ENQUEUED) "Na fila…" else "Gerando…"
                val frac = p?.getFloat(AudioGenerationWorker.KEY_CHAPTER_FRACTION, 0f) ?: 0f
                Text(msg, style = MaterialTheme.typography.bodySmall, color = Accent)
                ProgressLine(frac, color = Accent.copy(alpha = 0.6f), height = 3.dp)
            } else if (!complete) {
                Text(
                    "Tempo de narração estimado: ${formatDuration(estimateMs)} · espaço estimado: ${formatBytes(estimateMs * 8)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val failure = work?.takeIf { it.state == WorkInfo.State.FAILED }?.outputData?.getString(AudioGenerationWorker.KEY_ERROR) ?: manifest.lastError
            if (!running && failure != null && !complete) Text(failure, style = MaterialTheme.typography.bodySmall, color = Warning)
            if (!running && work?.state == WorkInfo.State.CANCELLED && !complete) {
                Text("Geração cancelada. Os capítulos já prontos foram mantidos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    running -> OutlinedButton(onClick = { AppGraph.generator.cancel(book.id) }) {
                        Icon(Icons.Rounded.Cancel, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Cancelar")
                    }
                    !complete -> Button(onClick = { AppGraph.generator.start(book.id) }) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(if (doneCount > 0) "Retomar geração" else "Gerar áudio")
                    }
                }
                if (doneCount > 0) {
                    OutlinedButton(onClick = {
                        AppGraph.playback.play(book.id, mode = PlaybackMode.AUDIO)
                        nav.navigate(Routes.player(book.id))
                    }) {
                        Icon(Icons.Rounded.Headphones, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Ouvir")
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { confirmDelete = true }, enabled = !running) { Icon(Icons.Rounded.DeleteOutline, "Excluir áudios", tint = Danger) }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir áudios gerados?") },
            text = { Text("Os ${formatBytes(size)} de áudio de \"${book.title}\" serão apagados. O livro e o texto continuam na biblioteca e você ainda poderá ouvir com a narração ao vivo.") },
            confirmButton = {
                TextButton(onClick = {
                    AppGraph.generator.cancel(book.id)
                    AppGraph.audio.deleteAll(book.id)
                    AppGraph.playback.onAudioChanged(book.id)
                    confirmDelete = false
                }) { Text("Excluir", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}
