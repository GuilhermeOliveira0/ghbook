package br.com.ghbook.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Toc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import br.com.ghbook.AppGraph
import br.com.ghbook.data.BookMeta
import br.com.ghbook.data.BookStatus
import br.com.ghbook.ui.theme.Accent
import br.com.ghbook.ui.theme.BrandNavyLight
import br.com.ghbook.ui.theme.Danger
import br.com.ghbook.ui.theme.Hero
import br.com.ghbook.ui.theme.OnHero
import br.com.ghbook.ui.theme.Success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BookActions {
    /** Remove o livro, o PDF copiado, o texto extraído, o progresso e todo o áudio gerado. */
    fun delete(id: String) {
        AppGraph.generator.cancel(id)
        AppGraph.importer.cancel(id)
        AppGraph.playback.onBookDeleted(id)
        AppGraph.audio.deleteAll(id)
        AppGraph.books.delete(id)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(nav: NavHostController) {
    val books by AppGraph.books.books.collectAsState()
    val progress by AppGraph.books.progress.collectAsState()
    val imports by AppGraph.importer.states.collectAsState()
    val audioVersion by AppGraph.audio.version.collectAsState()
    var toDelete by remember { mutableStateOf<BookMeta?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(30.dp)
                        Spacer(Modifier.width(10.dp))
                        Wordmark(20.sp)
                    }
                },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.audio()) }) { Icon(Icons.Outlined.DownloadForOffline, "Áudios offline") }
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Icon(Icons.Outlined.Settings, "Configurações") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            if (books.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate(Routes.IMPORT) },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text("Importar PDF") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(50),
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp, pressedElevation = 4.dp),
                )
            }
        },
        bottomBar = { MiniPlayer(onOpen = { nav.navigate(Routes.player(it)) }) },
    ) { pad ->
        if (books.isEmpty()) {
            EmptyLibrary(Modifier.padding(pad)) { nav.navigate(Routes.IMPORT) }
            return@Scaffold
        }
        // Livro com progresso mais recente (a lista vem ordenada por abertura, não por progresso).
        val continueBook = books
            .filter { it.status == BookStatus.READY && (progress[it.id]?.updatedAt ?: 0) > 0 }
            .maxByOrNull { progress[it.id]?.updatedAt ?: 0 }
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 104.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (continueBook != null) {
                item(key = "continue") {
                    ContinueCard(
                        continueBook,
                        AppGraph.books.percent(continueBook.id, progress[continueBook.id] ?: AppGraph.books.progressOf(continueBook.id)),
                        onRead = { nav.navigate(Routes.reader(continueBook.id)) },
                        onListen = {
                            AppGraph.playback.play(continueBook.id)
                            nav.navigate(Routes.player(continueBook.id))
                        },
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    Text("Seus livros", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).alignByBaseline())
                    Text(
                        if (books.size == 1) "1 livro" else "${books.size} livros",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            items(books, key = { it.id }) { book ->
                // Lido fora da thread principal: o manifesto fica em disco.
                val audioCount by produceState(0, book.id, audioVersion) {
                    value = withContext(Dispatchers.IO) { AppGraph.audio.manifest(book.id).chapters.size }
                }
                BookRow(
                    book = book,
                    percent = AppGraph.books.percent(book.id, progress[book.id] ?: AppGraph.books.progressOf(book.id)),
                    importMessage = imports[book.id]?.let { s -> s.message to (if (s.total > 0) s.done.toFloat() / s.total else null) },
                    audioChapters = audioCount,
                    onOpen = { if (book.status == BookStatus.READY) nav.navigate(Routes.reader(book.id)) },
                    onListen = {
                        AppGraph.playback.play(book.id)
                        nav.navigate(Routes.player(book.id))
                    },
                    onChapters = { nav.navigate(Routes.chapters(book.id)) },
                    onAudio = { nav.navigate(Routes.audio(book.id)) },
                    onRetry = { AppGraph.importer.process(book.id) },
                    onDelete = { toDelete = book },
                )
            }
        }
    }

    toDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Remover livro?") },
            text = { Text("\"${b.title}\" será removido do aparelho, junto com o texto extraído, o progresso e todos os áudios gerados. O PDF original no seu armazenamento não é alterado.") },
            confirmButton = {
                TextButton(onClick = { BookActions.delete(b.id); toDelete = null }) { Text("Remover", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier, onImport: () -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandMark(88.dp)
        Spacer(Modifier.height(20.dp))
        Wordmark(30.sp)
        Text("Leia e ouça seus livros", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(40.dp))
        Text("Sua biblioteca está vazia", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Importe um livro em PDF para ler com conforto e ouvir a narração em português, inclusive sem internet.",
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onImport, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)) {
            Icon(Icons.Rounded.Add, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Importar PDF")
        }
    }
}

@Composable
private fun ContinueCard(book: BookMeta, percent: Float, onRead: () -> Unit, onListen: () -> Unit) {
    Surface(color = Hero, contentColor = OnHero, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookCover(book.id, book.title, width = 72.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    SectionLabel("Continuar", color = OnHero.copy(alpha = 0.6f))
                    Spacer(Modifier.height(4.dp))
                    Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (book.author.isNotBlank()) {
                        Text(book.author, style = MaterialTheme.typography.bodySmall, color = OnHero.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(12.dp))
                    ProgressLine(percent, color = BrandNavyLight, track = OnHero.copy(alpha = 0.15f))
                    Spacer(Modifier.height(6.dp))
                    Text("${(percent * 100).toInt()}% concluído", style = MaterialTheme.typography.labelSmall, color = OnHero.copy(alpha = 0.6f))
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onListen, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = OnHero, contentColor = Hero),
                ) {
                    Icon(Icons.Rounded.Headphones, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Ouvir")
                }
                OutlinedButton(
                    onClick = onRead, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OnHero),
                    border = BorderStroke(1.dp, OnHero.copy(alpha = 0.35f)),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Ler")
                }
            }
        }
    }
}

@Composable
private fun BookRow(
    book: BookMeta,
    percent: Float,
    importMessage: Pair<String, Float?>?,
    audioChapters: Int,
    onOpen: () -> Unit,
    onListen: () -> Unit,
    onChapters: () -> Unit,
    onAudio: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(enabled = book.status == BookStatus.READY, onClickLabel = "Ler", onClick = onOpen),
    ) {
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
            BookCover(book.id, book.title, width = 58.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (book.author.isNotBlank()) {
                    Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                when (book.status) {
                    BookStatus.PROCESSING -> {
                        Text(importMessage?.first ?: "Na fila para processamento…", style = MaterialTheme.typography.bodySmall, color = Accent)
                        Spacer(Modifier.height(8.dp))
                        val f = importMessage?.second
                        if (f != null) ProgressLine(f) else IndeterminateLine()
                    }
                    BookStatus.FAILED -> {
                        Text(book.errorMessage ?: "Falha ao processar.", style = MaterialTheme.typography.bodySmall, color = Danger)
                        Row {
                            TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Tentar de novo")
                            }
                            TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Remover", color = Danger) }
                        }
                    }
                    BookStatus.READY -> {
                        val details = buildList {
                            add("${book.pageCount} pág.")
                            add("${book.chapterCount} cap.")
                            if (book.ocrPages.isNotEmpty()) add("OCR em ${book.ocrPages.size} pág.")
                        }.joinToString(" · ")
                        Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (audioChapters > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                Icon(Icons.Rounded.DownloadDone, null, tint = Success, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (audioChapters >= book.chapterCount) "Áudio offline completo" else "Áudio offline: $audioChapters de ${book.chapterCount} cap.",
                                    style = MaterialTheme.typography.bodySmall, color = Success,
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProgressLine(percent, Modifier.weight(1f))
                            Spacer(Modifier.width(10.dp))
                            Text("${(percent * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Opções de ${book.title}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(14.dp)) {
                    if (book.status == BookStatus.READY) {
                        DropdownMenuItem(text = { Text("Ler") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.MenuBook, null) }, onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text("Ouvir") }, leadingIcon = { Icon(Icons.Rounded.Headphones, null) }, onClick = { menu = false; onListen() })
                        DropdownMenuItem(text = { Text("Capítulos e progresso") }, leadingIcon = { Icon(Icons.Rounded.Toc, null) }, onClick = { menu = false; onChapters() })
                        DropdownMenuItem(text = { Text("Áudio offline") }, leadingIcon = { Icon(Icons.Rounded.DownloadForOffline, null) }, onClick = { menu = false; onAudio() })
                    }
                    DropdownMenuItem(text = { Text("Remover", color = Danger) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = Danger) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
