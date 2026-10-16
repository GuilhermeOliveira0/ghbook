package br.com.ghbook.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Toc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import br.com.ghbook.AppGraph
import br.com.ghbook.data.Chapter
import br.com.ghbook.data.ParagraphKind
import br.com.ghbook.data.ReaderFont
import br.com.ghbook.data.ReaderTheme
import br.com.ghbook.data.ReadingProgress
import br.com.ghbook.ui.theme.readerColors
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun ReaderScreen(nav: NavHostController, bookId: String, startChapter: Int, startParagraph: Int) {
    val meta = remember(bookId) { AppGraph.books.book(bookId) }
    val toc = remember(bookId) { AppGraph.books.toc(bookId) }
    if (meta == null || toc.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Livro indisponível ou sem texto extraído.")
                TextButton(onClick = { nav.backOrLibrary() }) { Text("Voltar") }
            }
        }
        return
    }
    LaunchedEffect(bookId) { AppGraph.books.markOpened(bookId) }

    val settings by AppGraph.settings.settings.collectAsState()
    val playback by AppGraph.playback.state.collectAsState()
    val colors = readerColors(settings.readerTheme)
    val scope = rememberCoroutineScope()

    // Ícones da barra de status/navegação legíveis sobre o fundo do tema de leitura (claro ou escuro).
    val view = LocalView.current
    // Com o mini player aberto, a área da barra de navegação usa as cores do app, não as do tema de leitura.
    val navLight = if (playback.bookId != null) MaterialTheme.colorScheme.surface.luminance() > 0.5f else colors.isLight
    DisposableEffect(colors.isLight, navLight) {
        var ctx = view.context
        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        val window = (ctx as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val prevStatus = controller?.isAppearanceLightStatusBars
        val prevNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = colors.isLight
        controller?.isAppearanceLightNavigationBars = navLight
        onDispose {
            prevStatus?.let { controller.isAppearanceLightStatusBars = it }
            prevNav?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }

    val saved = remember(bookId) { AppGraph.books.progressOf(bookId) }
    var chapterIndex by rememberSaveable(bookId) {
        mutableIntStateOf((if (startChapter >= 0) startChapter else saved.chapter).coerceIn(0, toc.lastIndex))
    }
    // Parágrafo-alvo para posicionar a lista depois de carregar o capítulo.
    var pendingParagraph by rememberSaveable(bookId) {
        mutableIntStateOf(if (startChapter >= 0) maxOf(0, startParagraph) else saved.paragraph)
    }
    val chapter by produceState<Chapter?>(null, bookId, chapterIndex) {
        value = null
        value = AppGraph.books.chapter(bookId, chapterIndex)
    }
    val listState = rememberLazyListState()
    var showSettings by remember { mutableStateOf(false) }
    var showGoToPage by remember { mutableStateOf(false) }
    var selectedParagraph by remember { mutableStateOf<Int?>(null) }

    val narrating = playback.bookId == bookId
    val narratingNow by rememberUpdatedState(narrating && playback.isPlaying)
    val narratedParagraph = if (narrating && playback.chapterIndex == chapterIndex) playback.paragraphIndex else -1

    // Posiciona no parágrafo pendente quando o capítulo carrega.
    LaunchedEffect(chapter) {
        val ch = chapter ?: return@LaunchedEffect
        if (pendingParagraph >= 0) {
            // Parágrafo 0 = início do capítulo: mostra também o rótulo "CAPÍTULO X DE N".
            val target = pendingParagraph.coerceIn(0, maxOf(0, ch.paragraphs.lastIndex))
            listState.scrollToItem(if (target == 0) 0 else target + 1)
            pendingParagraph = -1
        }
    }

    // Salva o progresso de leitura conforme a rolagem.
    LaunchedEffect(chapter) {
        val ch = chapter ?: return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .debounce(600)
            .collect { first ->
                val p = (first - 1).coerceIn(0, maxOf(0, ch.paragraphs.lastIndex))
                val old = AppGraph.books.progressOf(bookId)
                if (!narratingNow) {
                    AppGraph.books.saveProgress(bookId, old.copy(chapter = ch.index, paragraph = p, audioPositionMs = if (old.chapter == ch.index) old.audioPositionMs else 0))
                }
            }
    }

    // Acompanha a narração: troca de capítulo e rola até o trecho falado.
    LaunchedEffect(playback.chapterIndex, playback.paragraphIndex, playback.isPlaying, settings.followNarration) {
        if (!narrating || !settings.followNarration || !playback.isPlaying) return@LaunchedEffect
        if (playback.chapterIndex != chapterIndex) {
            pendingParagraph = playback.paragraphIndex
            chapterIndex = playback.chapterIndex
            return@LaunchedEffect
        }
        val target = playback.paragraphIndex + 1
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == target }
        // O início do parágrafo narrado precisa estar dentro da área visível (abaixo da barra superior).
        val topLimit = info.viewportStartOffset + info.beforeContentPadding
        val bottomLimit = info.viewportEndOffset - info.afterContentPadding
        val startVisible = item != null && item.offset >= topLimit - 4 && item.offset + minOf(item.size, 200) <= bottomLimit
        if (!startVisible) listState.animateScrollToItem(target, scrollOffset = -24)
    }

    val currentPage by remember {
        derivedStateOf {
            val ch = chapter ?: return@derivedStateOf toc[chapterIndex].startPage
            val i = (listState.firstVisibleItemIndex - 1).coerceIn(0, maxOf(0, ch.paragraphs.lastIndex))
            ch.paragraphs.getOrNull(i)?.page ?: toc[chapterIndex].startPage
        }
    }

    fun goToChapter(i: Int, paragraph: Int = 0) {
        if (i !in toc.indices) return
        if (i == chapterIndex) {
            scope.launch { listState.scrollToItem(paragraph + 1) }
        } else {
            pendingParagraph = paragraph
            chapterIndex = i
        }
    }

    /**
     * Vai para a página [page]. Um parágrafo pode atravessar páginas; se nenhum parágrafo começar na página pedida,
     * usa o que a contém. Com [step] (+1/-1) continua procurando até sair da posição atual, para nunca ficar preso.
     */
    fun goToPage(page: Int, step: Int = 0) {
        val total = meta.pageCount.coerceAtLeast(1)
        val currentPara = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
        scope.launch {
            var p = page.coerceIn(1, total)
            while (true) {
                val entry = toc.firstOrNull { it.endPage >= p } ?: toc.last()
                val ch = AppGraph.books.chapter(bookId, entry.index) ?: return@launch
                var para = ch.paragraphs.indexOfFirst { it.page >= p }
                if (para < 0 || ch.paragraphs[para].page > p) {
                    val containing = ch.paragraphs.indexOfLast { it.page <= p }
                    if (containing >= 0) para = containing
                }
                para = para.coerceAtLeast(0)
                // Avançando: se o trecho já está visível no fim do capítulo (a lista não rola mais), segue para a próxima página/capítulo.
                val moved = entry.index != chapterIndex || when {
                    step > 0 -> para > currentPara && listState.canScrollForward
                    step < 0 -> para < currentPara
                    else -> para != currentPara
                }
                val next = p + step
                if (moved || step == 0 || next !in 1..total) {
                    goToChapter(entry.index, para)
                    return@launch
                }
                p = next
            }
        }
    }

    val fontFamily = if (settings.readerFont == ReaderFont.SERIF) FontFamily.Serif else FontFamily.SansSerif
    val bodyStyle = TextStyle(
        fontSize = settings.fontSizeSp.sp,
        lineHeight = (settings.fontSizeSp * settings.lineHeight).sp,
        fontFamily = fontFamily,
        color = colors.text,
        letterSpacing = 0.01.em,
    )

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(meta.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, color = colors.text)
                        Text(toc[chapterIndex].title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                    }
                },
                navigationIcon = { IconButton(onClick = { nav.backOrLibrary() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", tint = colors.text) } },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.chapters(bookId)) }) { Icon(Icons.Rounded.Toc, "Capítulos", tint = colors.text) }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Rounded.FormatSize, "Ajustes de leitura", tint = colors.text) }
                    IconButton(onClick = {
                        if (playback.bookId != bookId) {
                            val first = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                            AppGraph.playback.play(bookId, chapterIndex, first)
                        }
                        nav.navigate(Routes.player(bookId))
                    }) { Icon(Icons.Rounded.Headphones, "Ouvir", tint = colors.accent) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = colors.secondary.copy(alpha = 0.15f))
                Row(
                    Modifier.fillMaxWidth().background(colors.background)
                        .then(if (playback.bookId == null) Modifier.navigationBarsPadding() else Modifier)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { goToChapter(chapterIndex - 1) }, enabled = chapterIndex > 0) {
                        Icon(Icons.Rounded.KeyboardDoubleArrowLeft, "Capítulo anterior", tint = colors.secondary)
                    }
                    IconButton(onClick = { goToPage(currentPage - 1, -1) }, enabled = currentPage > 1) {
                        Icon(Icons.AutoMirrored.Rounded.NavigateBefore, "Página anterior", tint = colors.secondary)
                    }
                    Text(
                        "Pág. $currentPage de ${meta.pageCount} · Cap. ${chapterIndex + 1}/${toc.size}",
                        modifier = Modifier.weight(1f).clickable { showGoToPage = true }.padding(vertical = 10.dp),
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = colors.secondary,
                    )
                    IconButton(onClick = { goToPage(currentPage + 1, +1) }, enabled = currentPage < meta.pageCount) {
                        Icon(Icons.AutoMirrored.Rounded.NavigateNext, "Próxima página", tint = colors.secondary)
                    }
                    IconButton(onClick = { goToChapter(chapterIndex + 1) }, enabled = chapterIndex < toc.lastIndex) {
                        Icon(Icons.Rounded.KeyboardDoubleArrowRight, "Próximo capítulo", tint = colors.secondary)
                    }
                }
                MiniPlayer(onOpen = { nav.navigate(Routes.player(it)) })
            }
        },
    ) { pad ->
        val ch = chapter
        if (ch == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(colors.background),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 32.dp),
        ) {
            item(key = "header") {
                Text(
                    "CAPÍTULO ${chapterIndex + 1} DE ${toc.size}",
                    style = MaterialTheme.typography.labelSmall, color = colors.secondary, letterSpacing = 0.15.em,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            itemsIndexed(ch.paragraphs, key = { i, _ -> "p$i" }) { i, p ->
                val highlighted = i == narratedParagraph
                val shape = RoundedCornerShape(8.dp)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = (settings.paragraphSpacingDp / 2).dp)
                        .background(if (highlighted) colors.highlight else Color.Transparent, shape)
                        .clickable { selectedParagraph = i }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    if (p.kind == ParagraphKind.HEADING) {
                        Text(
                            p.text,
                            style = bodyStyle.copy(fontSize = (settings.fontSizeSp * 1.3f).sp, lineHeight = (settings.fontSizeSp * 1.7f).sp, fontWeight = FontWeight.Bold, color = colors.heading),
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    } else {
                        Text(p.text, style = bodyStyle, textAlign = TextAlign.Start)
                    }
                }
            }
            item(key = "footer") {
                Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (chapterIndex < toc.lastIndex) {
                        Button(onClick = { goToChapter(chapterIndex + 1) }, colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)) {
                            Text("Próximo: ${toc[chapterIndex + 1].title.take(40)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        Text("Fim do livro", color = colors.secondary, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }

    selectedParagraph?.let { idx ->
        val p = chapter?.paragraphs?.getOrNull(idx)
        ModalBottomSheet(onDismissRequest = { selectedParagraph = null }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Página ${p?.page ?: "-"} · parágrafo ${idx + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(p?.text ?: "", maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = {
                        AppGraph.playback.play(bookId, chapterIndex, idx)
                        selectedParagraph = null
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Ouvir a partir daqui")
                }
                TextButton(onClick = {
                    AppGraph.books.saveProgress(bookId, ReadingProgress(chapter = chapterIndex, paragraph = idx))
                    selectedParagraph = null
                }, modifier = Modifier.fillMaxWidth()) { Text("Marcar como ponto de leitura") }
            }
        }
    }

    if (showGoToPage) {
        var text by remember { mutableStateOf(currentPage.toString()) }
        AlertDialog(
            onDismissRequest = { showGoToPage = false },
            title = { Text("Ir para página") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { v -> text = v.filter { it.isDigit() }.take(5) },
                    label = { Text("Página (1–${meta.pageCount})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = { text.toIntOrNull()?.let { goToPage(it) }; showGoToPage = false }) { Text("Ir") }
            },
            dismissButton = { TextButton(onClick = { showGoToPage = false }) { Text("Cancelar") } },
        )
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            ReadingSettingsPanel(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp))
        }
    }
}

/** Ajustes de tamanho de fonte, espaçamento, tema e fonte — usado no leitor e nas Configurações. */
@Composable
fun ReadingSettingsPanel(modifier: Modifier = Modifier) {
    val s by AppGraph.settings.settings.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Leitura", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text("Tamanho da fonte: ${s.fontSizeSp.toInt()}", style = MaterialTheme.typography.bodySmall)
        BrandSlider(value = s.fontSizeSp, onValueChange = { v -> AppGraph.settings.update { it.copy(fontSizeSp = v) } }, valueRange = 14f..30f, steps = 15)
        Text("Espaçamento entre linhas: ${String.format(java.util.Locale("pt", "BR"), "%.1f", s.lineHeight)}", style = MaterialTheme.typography.bodySmall)
        BrandSlider(value = s.lineHeight, onValueChange = { v -> AppGraph.settings.update { it.copy(lineHeight = v) } }, valueRange = 1.2f..2.2f)
        Text("Espaço entre parágrafos: ${s.paragraphSpacingDp.toInt()}", style = MaterialTheme.typography.bodySmall)
        BrandSlider(value = s.paragraphSpacingDp, onValueChange = { v -> AppGraph.settings.update { it.copy(paragraphSpacingDp = v) } }, valueRange = 4f..32f)
        Text("Tema de leitura", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderTheme.entries.forEach { t ->
                val c = readerColors(t)
                FilterChip(
                    selected = s.readerTheme == t,
                    onClick = { AppGraph.settings.update { it.copy(readerTheme = t) } },
                    label = { Text(t.label) },
                    leadingIcon = {
                        Surface(
                            color = c.background, shape = RoundedCornerShape(50), modifier = Modifier.size(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {}
                    },
                    shape = RoundedCornerShape(50),
                    colors = brandChipColors(),
                )
            }
        }
        Text("Fonte", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderFont.entries.forEach { f ->
                FilterChip(selected = s.readerFont == f, onClick = { AppGraph.settings.update { it.copy(readerFont = f) } }, label = { Text(f.label) }, shape = RoundedCornerShape(50), colors = brandChipColors())
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Acompanhar a narração", style = MaterialTheme.typography.bodyMedium)
                Text("Rola o texto e destaca o trecho que está sendo lido", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = s.followNarration, onCheckedChange = { v -> AppGraph.settings.update { it.copy(followNarration = v) } })
        }
        Spacer(Modifier.height(4.dp))
    }
}
