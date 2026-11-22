package app.lockmein.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Quiet tonal" theme: calm enough to read half asleep at night.
 *
 * - Neutral almost everywhere: flat tonal surfaces (a card is a step lighter than the page in dark mode, white on a soft grey
 *   page in light mode), no borders, no gradients, no glows. Structure is felt, not seen.
 * - Colour only for meaning, always with an icon and a word: OK (green), needs attention (amber), closed (red). The teal accent
 *   marks what can be tapped and what is selected.
 * - Warm, low-saturation neutrals; off-white text on a near-black page in dark mode (never pure black or pure white).
 * - One big number per screen at most (the time left on Home). Section labels are small and quiet; titles carry the hierarchy.
 * - Shape: 20dp cards, 12dp small containers, pill buttons and badges.
 * Follows the system dark theme setting.
 */
object Palette {
    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = isSystemInDarkTheme()

    // Semantic status colors (never color alone: always paired with icon and word)
    val Ok: Color @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFF86D39A) else Color(0xFF1E6B35)
    val OkContainer: Color @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFF1B2E21) else Color(0xFFDDF1E1)

    val Attention: Color @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFFEDC26E) else Color(0xFF855D00)
    val AttentionContainer: Color @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFF332A17) else Color(0xFFFBEFD3)

    val Closed: Color @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFFF0A49B) else Color(0xFFB3261E)
    val ClosedContainer: Color @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFF3A201E) else Color(0xFFFBE3E0)

    // Kept for callers: dates and neutral facts are shown in the accent or in plain text, not in extra hues.
    val Info: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
    val Neutral: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

    // Neutral text & lines
    val TextPrimary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val Line: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant

    // Compatibility tokens
    val Day: Color @Composable @ReadOnlyComposable get() = Ok
    val Locked: Color @Composable @ReadOnlyComposable get() = Closed
    val Open: Color @Composable @ReadOnlyComposable get() = Attention
    val Signal: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
    val Muted: Color @Composable @ReadOnlyComposable get() = TextSecondary
    val Paper: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface
    val Ink: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
    val Card: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainer
    val CardHigh: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHighest
    val Bar: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
    val Rule: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant
    val RuleDark: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primaryContainer

    /** The container for a status colour: a quiet wash for call-outs and icon backgrounds. */
    @Composable
    @ReadOnlyComposable
    fun containerOf(color: Color): Color = when (color) {
        Ok -> OkContainer
        Attention -> AttentionContainer
        Closed -> ClosedContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF006A60),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEAE4),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4F5E5B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2EBE8),
    onSecondaryContainer = Color(0xFF1A2624),
    background = Color(0xFFF3F4F2),
    onBackground = Color(0xFF1B1C1B),
    surface = Color(0xFFF3F4F2),
    onSurface = Color(0xFF1B1C1B),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF5F6461),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAF9),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEDEEEC),
    surfaceContainerHighest = Color(0xFFE6E8E5),
    outline = Color(0xFFC9CCC9),
    outlineVariant = Color(0xFFE7E8E6),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE3E0),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8AD3C6),
    onPrimary = Color(0xFF00342E),
    primaryContainer = Color(0xFF1F3B37),
    onPrimaryContainer = Color(0xFFCDEAE4),
    secondary = Color(0xFFB4C4C0),
    onSecondary = Color(0xFF1F2C2A),
    secondaryContainer = Color(0xFF263230),
    onSecondaryContainer = Color(0xFFD3E1DD),
    background = Color(0xFF0F1011),
    onBackground = Color(0xFFE5E3DF),
    surface = Color(0xFF0F1011),
    onSurface = Color(0xFFE5E3DF),
    surfaceVariant = Color(0xFF1A1B1D),
    onSurfaceVariant = Color(0xFF9DA19F),
    surfaceContainerLowest = Color(0xFF0B0C0D),
    surfaceContainerLow = Color(0xFF151618),
    surfaceContainer = Color(0xFF1A1B1D),
    surfaceContainerHigh = Color(0xFF232527),
    surfaceContainerHighest = Color(0xFF2C2E30),
    outline = Color(0xFF45494A),
    outlineVariant = Color(0xFF2A2C2E),
    error = Color(0xFFF0A49B),
    onError = Color(0xFF5C1712),
    errorContainer = Color(0xFF3A201E),
    onErrorContainer = Color(0xFFFBE3E0),
)

// Cards: 20dp corners. Small containers (icon tiles, fields, app icons): 12dp. Buttons and badges: a full pill.
val Radius = 20.dp
val CardShape = RoundedCornerShape(Radius)
val ButtonShape = RoundedCornerShape(50)
val FieldShape = RoundedCornerShape(14.dp)
val PillShape = RoundedCornerShape(50)
val TileShape = RoundedCornerShape(12.dp)
val Dot = CircleShape

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val SystemSans = FontFamily.SansSerif

private val AppTypography = Typography(
    // The one big number per screen (time left on Home).
    displaySmall = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.25).sp),
    headlineSmall = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

@Composable
fun LockTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkScheme else LightScheme
    MaterialTheme(
        colorScheme = colors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
