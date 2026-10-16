package br.com.ghbook.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import br.com.ghbook.data.ReaderTheme

// Paleta da marca: preto, branco e azul-escuro (cores exatas do logo).
val BrandInk = Color(0xFF0A0A0A)
val BrandNavy = Color(0xFF0B2A6B)
val BrandWhite = Color(0xFFFFFFFF)
/** Azul da marca clareado para contraste sobre fundos escuros. */
val BrandNavyLight = Color(0xFFB3C5FF)

private val LightScheme = lightColorScheme(
    primary = BrandNavy,
    onPrimary = BrandWhite,
    primaryContainer = Color(0xFFE6ECF8),
    onPrimaryContainer = BrandNavy,
    secondary = BrandInk,
    onSecondary = BrandWhite,
    secondaryContainer = Color(0xFFEDEEF1),
    onSecondaryContainer = BrandInk,
    tertiary = BrandNavy,
    onTertiary = BrandWhite,
    background = BrandWhite,
    onBackground = BrandInk,
    surface = BrandWhite,
    onSurface = BrandInk,
    surfaceVariant = Color(0xFFF1F2F4),
    onSurfaceVariant = Color(0xFF5B5F67),
    surfaceContainerLowest = BrandWhite,
    surfaceContainerLow = Color(0xFFF7F7F8),
    surfaceContainer = Color(0xFFF2F3F5),
    surfaceContainerHigh = Color(0xFFECEDF0),
    surfaceContainerHighest = Color(0xFFE5E7EB),
    surfaceTint = Color.Transparent,
    inverseSurface = BrandInk,
    inverseOnSurface = BrandWhite,
    inversePrimary = BrandNavyLight,
    outline = Color(0xFFD3D6DC),
    outlineVariant = Color(0xFFE7E8EB),
    error = Color(0xFFB3261E),
    onError = BrandWhite,
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFF8C1D18),
    scrim = Color(0xFF000000),
)

private val DarkScheme = darkColorScheme(
    primary = BrandNavyLight,
    onPrimary = BrandNavy,
    primaryContainer = Color(0xFF1A2F5E),
    onPrimaryContainer = Color(0xFFDCE3FA),
    secondary = Color(0xFFF2F2F2),
    onSecondary = BrandInk,
    secondaryContainer = Color(0xFF262626),
    onSecondaryContainer = Color(0xFFF2F2F2),
    tertiary = BrandNavyLight,
    onTertiary = BrandNavy,
    background = BrandInk,
    onBackground = Color(0xFFF2F2F2),
    surface = BrandInk,
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFA4A7AE),
    surfaceContainerLowest = Color(0xFF050505),
    surfaceContainerLow = Color(0xFF121212),
    surfaceContainer = Color(0xFF171717),
    surfaceContainerHigh = Color(0xFF1F1F1F),
    surfaceContainerHighest = Color(0xFF292929),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFF2F2F2),
    inverseOnSurface = BrandInk,
    inversePrimary = BrandNavy,
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF262626),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0907),
    errorContainer = Color(0xFF3A1513),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

/** Cores de estado e de destaque que o Material 3 não cobre, ajustadas para cada modo. */
@Immutable
data class StatusColors(
    val success: Color,
    val warning: Color,
    val danger: Color,
    /** Fundo do cartão "Continuar" e do botão principal de reprodução (preto da marca). */
    val hero: Color,
    val onHero: Color,
    val track: Color,
)

private val LightStatus = StatusColors(
    success = Color(0xFF1B7A4B),
    warning = Color(0xFF8A5A00),
    danger = Color(0xFFB3261E),
    hero = BrandInk,
    onHero = BrandWhite,
    track = Color(0xFFE5E7EB),
)

private val DarkStatus = StatusColors(
    success = Color(0xFF6FD3A0),
    warning = Color(0xFFF2C94C),
    danger = Color(0xFFFF8A80),
    hero = Color(0xFF1A1A1A),
    onHero = BrandWhite,
    track = Color(0xFF2C2C2C),
)

private val LocalStatusColors = staticCompositionLocalOf { LightStatus }

val Accent: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
val Success: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.success
val Warning: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.warning
val Danger: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.danger
val Hero: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.hero
val OnHero: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.onHero
/** Trilho das barras de progresso. */
val Track: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.track

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.015).em),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.01.em),
        labelSmall = t.labelSmall.copy(fontWeight = FontWeight.Medium),
    )
}

@Composable
fun GhBookTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (darkTheme) DarkScheme else LightScheme, typography = AppTypography, content = content)
    }
}

data class ReaderColors(
    val background: Color,
    val text: Color,
    val secondary: Color,
    val highlight: Color,
    val heading: Color,
    /** Cor de destaque (ícones e botões) coerente com o fundo do tema de leitura. */
    val accent: Color,
    val onAccent: Color,
    val isLight: Boolean,
)

fun readerColors(theme: ReaderTheme): ReaderColors = when (theme) {
    ReaderTheme.DARK -> ReaderColors(Color(0xFF121212), Color(0xFFE8E6E3), Color(0xFF8E9096), Color(0x2E8FA8EA), BrandNavyLight, BrandNavyLight, BrandNavy, false)
    ReaderTheme.BLACK -> ReaderColors(Color.Black, Color(0xFFD6D6D6), Color(0xFF7F8288), Color(0x2E8FA8EA), BrandNavyLight, BrandNavyLight, BrandNavy, false)
    ReaderTheme.SEPIA -> ReaderColors(Color(0xFFF4ECDD), Color(0xFF33291D), Color(0xFF76674F), Color(0x1F0B2A6B), BrandNavy, BrandNavy, BrandWhite, true)
    ReaderTheme.LIGHT -> ReaderColors(BrandWhite, Color(0xFF1A1A1A), Color(0xFF6B6E75), Color(0x1A0B2A6B), BrandNavy, BrandNavy, BrandWhite, true)
}
