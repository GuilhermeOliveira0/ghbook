package br.com.ghbook.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.rounded.WarningAmber
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
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import br.com.ghbook.AppGraph
import br.com.ghbook.IncomingEvents
import br.com.ghbook.data.BookStatus
import br.com.ghbook.importer.ImportResult
import br.com.ghbook.ui.theme.Accent
import br.com.ghbook.ui.theme.Success
import br.com.ghbook.ui.theme.Warning
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    var bookId by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var copying by rememberSaveable { mutableStateOf(false) }
    val books by AppGraph.books.books.collectAsState()
    val imports by AppGraph.importer.states.collectAsState()
    val incoming by IncomingEvents.pdf.collectAsState()

    fun start(uri: Uri) {
        error = null; notice = null; copying = true
        scope.launch {
            when (val r = AppGraph.importer.import(uri)) {
                is ImportResult.Started -> bookId = r.bookId
                is ImportResult.AlreadyImported -> { bookId = r.bookId; notice = "Este PDF já está na sua biblioteca." }
                is ImportResult.Error -> error = r.message
            }
            copying = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) start(uri)
    }

    LaunchedEffect(incoming) {
        incoming?.let {
            IncomingEvents.pdf.value = null
            start(it)
        }
    }

    Scaffold(
        topBar = {
            BackTopBar("Importar PDF", onBack = { nav.backOrLibrary() })
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.FileOpen, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Escolha um livro em PDF", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "O arquivo é copiado para o armazenamento privado do app e processado no próprio aparelho. Nada é enviado para a internet.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("application/pdf")) },
                        enabled = !copying,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) { Text(if (copying) "Copiando arquivo…" else "Selecionar PDF") }
                }
            }

            error?.let { ErrorBanner(it, onDismiss = { error = null }) }
            notice?.let { InfoCard("Aviso", it, accent = Warning, icon = Icons.Rounded.WarningAmber) }

            val id = bookId
            val book = id?.let { bid -> books.firstOrNull { it.id == bid } }
            if (book != null) {
                OutlinedCard {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        BookCover(book.id, book.title, width = 64.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(book.title, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(6.dp))
                            when (book.status) {
                                BookStatus.PROCESSING -> {
                                    val st = imports[book.id]
                                    Text(st?.message ?: "Processando…", style = MaterialTheme.typography.bodySmall, color = Accent)
                                    Spacer(Modifier.height(6.dp))
                                    if (st != null && st.total > 0) {
                                        ProgressLine(st.done.toFloat() / st.total)
                                    } else IndeterminateLine()
                                    TextButton(onClick = { AppGraph.importer.cancel(book.id) }) { Text("Cancelar importação") }
                                }
                                BookStatus.FAILED -> {
                                    ErrorBanner(book.errorMessage ?: "Falha ao processar o PDF.")
                                    TextButton(onClick = { BookActions.delete(book.id); bookId = null }) { Text("Descartar") }
                                }
                                BookStatus.READY -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.CheckCircle, null, tint = Success, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Pronto: ${book.pageCount} páginas, ${book.chapterCount} capítulos", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (book.ocrPages.isNotEmpty()) {
                                        Text("${book.ocrPages.size} página(s) digitalizada(s) lidas por OCR local.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (book.unreadablePages.isNotEmpty()) {
                                        Text(
                                            "Atenção: ${book.unreadablePages.size} página(s) digitalizada(s) sem texto reconhecível: " + book.unreadablePages.take(12).joinToString(", ") + if (book.unreadablePages.size > 12) "…" else "",
                                            style = MaterialTheme.typography.bodySmall, color = Warning,
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { nav.navigate(Routes.reader(book.id)) { popUpTo(Routes.LIBRARY) } }) {
                                            Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Ler")
                                        }
                                        OutlinedButton(onClick = {
                                            AppGraph.playback.play(book.id)
                                            nav.navigate(Routes.player(book.id)) { popUpTo(Routes.LIBRARY) }
                                        }) {
                                            Icon(Icons.Rounded.Headphones, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Ouvir")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            InfoCard(
                "O que acontece na importação",
                "1) O texto é extraído preservando ordem de leitura, parágrafos e títulos. 2) Cabeçalhos, rodapés, números de página e sumário são descartados. " +
                    "3) Páginas digitalizadas (sem texto selecionável) passam por OCR local. 4) O livro é dividido em capítulos pelo sumário do PDF ou pelos títulos detectados.",
            )
            InfoCard(
                "Limitações",
                "PDFs protegidos por senha não podem ser abertos. Em PDFs com layout complexo (várias colunas, tabelas) a ordem do texto pode ficar imperfeita. O OCR reconhece alfabeto latino.",
                accent = Warning,
                icon = Icons.Rounded.WarningAmber,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
