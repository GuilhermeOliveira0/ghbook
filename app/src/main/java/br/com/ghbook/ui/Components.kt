package br.com.ghbook.ui

import androidx.compose.runtime.getValue
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import br.com.ghbook.AppGraph
import br.com.ghbook.R
import br.com.ghbook.data.PlaybackMode
import br.com.ghbook.ui.theme.Accent
import br.com.ghbook.ui.theme.BrandNavy
import br.com.ghbook.ui.theme.BrandNavyLight
import br.com.ghbook.ui.theme.Danger
import br.com.ghbook.ui.theme.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private val PtBr = Locale("pt", "BR")

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(PtBr, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(PtBr, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(PtBr, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

fun formatDuration(ms: Long): String {
    if (ms < 60_000) return "${(ms / 1000).coerceAtLeast(1)} s"
    val min = (ms + 30_000) / 60_000
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

/** Velocidade no formato brasileiro: 1×, 1,25×, 0,5×. */
fun speedLabel(s: Float): String =
    String.format(PtBr, "%.2f", s).trimEnd('0').trimEnd(',') + "×"

/** Símbolo da marca: quadrado preto com o livro e as ondas sonoras. */
@Composable
fun BrandMark(size: Dp = 32.dp, modifier: Modifier = Modifier) {
    // No modo escuro o quadrado preto se confundiria com o fundo: um contorno sutil mantém a forma do ícone.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val outline = if (dark) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(size * 0.2f)) else Modifier
    Image(painterResource(R.drawable.ic_brand_mark), contentDescription = null, modifier = modifier.size(size).then(outline))
}

/** Logotipo "GHBOOK": GH em preto (branco no modo escuro) e BOOK em azul-escuro, como na marca. */
@Composable
fun Wordmark(fontSize: TextUnit = 20.sp, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onBackground
    val navy = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) BrandNavyLight else BrandNavy
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = ink, fontWeight = FontWeight.Black)) { append("GH") }
            withStyle(SpanStyle(color = navy, fontWeight = FontWeight.SemiBold)) { append("BOOK") }
        },
        fontSize = fontSize, letterSpacing = 0.02.em, fontFamily = FontFamily.SansSerif,
        modifier = modifier.semantics { contentDescription = "GHBook" },
    )
}

/** Rótulo de seção discreto, em caixa alta. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text.uppercase(PtBr), modifier = modifier,
        style = MaterialTheme.typography.labelSmall, color = color, letterSpacing = 0.12.em, fontWeight = FontWeight.SemiBold,
    )
}

/** Barra de progresso fina e arredondada, usada em todo o app. */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, color: Color = Accent, height: Dp = 4.dp, track: Color = Track) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(height).clip(CircleShape),
        color = color, trackColor = track, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
    )
}

@Composable
fun IndeterminateLine(modifier: Modifier = Modifier, color: Color = Accent) {
    LinearProgressIndicator(
        modifier = modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
        color = color, trackColor = Track, strokeCap = StrokeCap.Round, gapSize = 0.dp,
    )
}

/** Barra superior padrão das telas internas: voltar, título (e subtítulo opcional) e ações. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    backIcon: ImageVector = Icons.AutoMirrored.Rounded.ArrowBack,
    backLabel: String = "Voltar",
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        navigationIcon = { IconButton(onClick = onBack) { Icon(backIcon, backLabel) } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
fun BookCover(bookId: String, title: String, modifier: Modifier = Modifier, width: Dp = 96.dp) {
    val file = AppGraph.books.coverFile(bookId)
    val bitmap by produceState<ImageBitmap?>(null, bookId, file.exists()) {
        value = withContext(Dispatchers.IO) {
            if (file.exists()) runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull() else null
        }
    }
    val radius = if (width < 48.dp) 6.dp else 10.dp
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .width(width)
            .height(width * 1.45f)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF15377F), BrandNavy, Color(0xFF071B47))))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp, contentDescription = "Capa de $title", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (width >= 56.dp) {
            // Capa gerada: título em serifa sobre o azul da marca.
            Column(Modifier.fillMaxSize().padding(width * 0.1f), verticalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.width(width * 0.22f).height(2.dp).background(Color.White.copy(alpha = 0.7f)))
                Text(
                    title, color = Color.White, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                    fontSize = (width.value / 8.5f).coerceIn(9f, 20f).sp, lineHeight = (width.value / 7f).coerceIn(11f, 24f).sp,
                    maxLines = 5, overflow = TextOverflow.Ellipsis,
                )
                Text("GHBOOK", color = Color.White.copy(alpha = 0.55f), fontSize = (width.value / 14f).coerceIn(6f, 11f).sp, letterSpacing = 0.15.em, fontWeight = FontWeight.Bold)
            }
        } else {
            Text(title.take(1).uppercase(PtBr), color = Color.White, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = (width.value / 2.4f).sp)
        }
    }
}

/** Botão circular de reproduzir/pausar no preto da marca (mostra progresso enquanto a voz carrega). */
@Composable
fun PlayPauseButton(
    playing: Boolean,
    buffering: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    // Preto da marca no tema claro; branco no escuro (contraste máximo com o fundo).
    container: Color = MaterialTheme.colorScheme.inverseSurface,
    content: Color = MaterialTheme.colorScheme.inverseOnSurface,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        contentColor = content,
        // O rótulo acessível vale também enquanto a voz carrega (indicador no lugar do ícone).
        modifier = modifier.size(size).semantics { contentDescription = if (playing) "Pausar" else "Reproduzir" },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (buffering) CircularProgressIndicator(Modifier.size(size * 0.4f), color = content, strokeWidth = 2.5.dp)
            else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(size * 0.5f))
        }
    }
}

/** Barra compacta de reprodução exibida na biblioteca e no leitor. */
@Composable
fun MiniPlayer(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val st by AppGraph.playback.state.collectAsState()
    val id = st.bookId ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding()) {
            val progress = if (st.durationMs > 0) (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f) else 0f
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Accent, trackColor = Color.Transparent, gapSize = 0.dp, drawStopIndicator = {},
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Abrir player") { onOpen(id) }
                    .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BookCover(id, st.bookTitle, width = 32.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(st.chapterTitle.ifEmpty { st.bookTitle }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(
                        (if (st.mode == PlaybackMode.AUDIO) "Áudio gerado · " else "Ao vivo · ") + st.bookTitle,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                PlayPauseButton(st.isPlaying, st.isBuffering, onClick = { AppGraph.playback.togglePlayPause() }, size = 44.dp)
            }
        }
    }
}

@Composable
fun ErrorBanner(message: String, onDismiss: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = RoundedCornerShape(14.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = if (onDismiss != null) 4.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = Danger, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Fechar aviso", Modifier.size(20.dp)) }
            }
        }
    }
}

@Composable
fun InfoCard(title: String, body: String, modifier: Modifier = Modifier, accent: Color = Accent, icon: ImageVector = Icons.Rounded.Info) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Slider da marca: trilho fino e marcador redondo, sem os pontos de passo do Material 3. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrandSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors(
        thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Track,
        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
        disabledActiveTickColor = Color.Transparent, disabledInactiveTickColor = Color.Transparent,
    )
    Slider(
        value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
        onValueChangeFinished = onValueChangeFinished, colors = colors, interactionSource = interaction,
        steps = steps, valueRange = valueRange,
        thumb = {
            Box(
                Modifier.size(18.dp).shadow(2.dp, CircleShape)
                    .background(if (enabled) Accent else MaterialTheme.colorScheme.outline, CircleShape),
            )
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state, modifier = Modifier.height(4.dp), enabled = enabled, colors = colors,
                drawStopIndicator = null, thumbTrackGapSize = 0.dp,
            )
        },
    )
}

/** Chips selecionados no azul da marca. */
@Composable
fun brandChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
)

/** Cartão neutro com borda fina, base das listas e grupos de opções. */
@Composable
fun OutlinedCard(modifier: Modifier = Modifier, highlighted: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}
