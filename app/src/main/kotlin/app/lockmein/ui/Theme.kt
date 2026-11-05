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
 * Calm, predictable Material 3 theme.
 * Follows the system dark theme setting (not schedule).
 * Accent: calm Deep Teal.
 * Status uses three meanings only: OK (green), Needs attention (amber), Broken/closed (red).
 * Shape: one modest corner radius (12dp) used everywhere.
 */
object Palette {
    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = isSystemInDarkTheme()

    // Semantic status colors (never color alone: always paired with icon and word)
    val Ok: Color @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFF79D886) else Color(0xFF1B6D2F)
    val OkContainer: Color @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFF0F3818) else Color(0xFFD6F5D6)

    val Attention: Color @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFFF6BD41) else Color(0xFF8C6200)
    val AttentionContainer: Color @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFF3E2C00) else Color(0xFFFFF0D0)

    val Closed: Color @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A)
    val ClosedContainer: Color @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFF410002) else Color(0xFFFFDAD6)

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
    val Card: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceVariant
    val CardHigh: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHighest
    val Bar: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
    val Rule: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant
    val RuleDark: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primaryContainer
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00685F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC6EFE7),
    onPrimaryContainer = Color(0xFF00201D),
    secondary = Color(0xFF4A635F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E2),
    onSecondaryContainer = Color(0xFF05201C),
    background = Color(0xFFFBFDF9),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFFBFDF9),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFEFF2ED),
    onSurfaceVariant = Color(0xFF5A605B),
    surfaceContainer = Color(0xFFF2F5F0),
    surfaceContainerHigh = Color(0xFFEBEFE9),
    surfaceContainerHighest = Color(0xFFE1E5DF),
    outline = Color(0xFFD6DBD4),
    outlineVariant = Color(0xFFE2E6DF),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF68DBCB),
    onPrimary = Color(0xFF003732),
    primaryContainer = Color(0xFF005049),
    onPrimaryContainer = Color(0xFFC6EFE7),
    secondary = Color(0xFFB0CCC6),
    onSecondary = Color(0xFF1B3531),
    secondaryContainer = Color(0xFF324B47),
    onSecondaryContainer = Color(0xFFCCE8E2),
    background = Color(0xFF121413),
    onBackground = Color(0xFFE1E3DF),
    surface = Color(0xFF121413),
    onSurface = Color(0xFFE1E3DF),
    surfaceVariant = Color(0xFF1E211F),
    onSurfaceVariant = Color(0xFFA4AAA4),
    surfaceContainer = Color(0xFF1A1D1B),
    surfaceContainerHigh = Color(0xFF242725),
    surfaceContainerHighest = Color(0xFF2E312F),
    outline = Color(0xFF3C413D),
    outlineVariant = Color(0xFF282B29),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

// One modest corner radius (12dp) used everywhere
val Radius = 12.dp
val CardShape = RoundedCornerShape(Radius)
val ButtonShape = RoundedCornerShape(Radius)
val Dot = CircleShape

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

private val SystemSans = FontFamily.SansSerif

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
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
