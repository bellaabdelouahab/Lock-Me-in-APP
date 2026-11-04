package app.lockmein.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Swiss poster: paper and ink, hard lines, square blocks, one loud signal colour. By day it is bright paper; from the night's
 * start it dims by itself (warm near-black, paper-coloured ink) so it never glares at 2am. The meaning of each colour stays:
 * Day (green) = verified, Locked (red) = broken or closed, Open (yellow) = the window or something to look at,
 * Signal (orange) = what you can press.
 */
object Palette {
    /** Set from the schedule (MainActivity); every colour below follows it. */
    var night by mutableStateOf(false)

    val Paper get() = if (night) Color(0xFF16140F) else Color(0xFFF2EEE3)
    val Ink get() = if (night) Color(0xFFD8D0BF) else Color(0xFF111111)
    val Signal get() = if (night) Color(0xFFC4501E) else Color(0xFFFF4F00)
    val Day get() = if (night) Color(0xFF4E9A6B) else Color(0xFF0B8A4C)
    val Locked get() = if (night) Color(0xFFC0473F) else Color(0xFFE0281B)
    val Open get() = if (night) Color(0xFFB8922E) else Color(0xFFF2B600)
    val Muted get() = if (night) Color(0xFF8C8475) else Color(0xFF6B675E)
    val Line get() = if (night) Color(0xFF3A362D) else Color(0x29111111)

    // Old names, kept so every screen takes the new look at once.
    val Surface get() = Paper
    val Night get() = Paper
    val Card get() = if (night) Color(0xFF201D17) else Color(0xFFE8E3D6)
    val CardHigh get() = Card
    val Bar get() = Ink
    val Rule get() = Signal
    val RuleDark get() = if (night) Color(0xFF3A2618) else Color(0xFFFFD9C7)
}

private fun scheme() = if (Palette.night) darkColorScheme(
    primary = Palette.Signal, onPrimary = Palette.Paper,
    primaryContainer = Palette.RuleDark, onPrimaryContainer = Palette.Ink,
    secondary = Palette.Ink, onSecondary = Palette.Paper,
    background = Palette.Paper, onBackground = Palette.Ink,
    surface = Palette.Paper, onSurface = Palette.Ink,
    surfaceVariant = Palette.Card, onSurfaceVariant = Palette.Muted,
    surfaceContainerHighest = Palette.Card,
    error = Palette.Locked, outline = Palette.Ink, outlineVariant = Palette.Line,
) else lightColorScheme(
    primary = Palette.Ink, onPrimary = Palette.Paper,
    primaryContainer = Palette.RuleDark, onPrimaryContainer = Palette.Ink,
    secondary = Palette.Signal, onSecondary = Palette.Paper,
    background = Palette.Paper, onBackground = Palette.Ink,
    surface = Palette.Paper, onSurface = Palette.Ink,
    surfaceVariant = Palette.Card, onSurfaceVariant = Palette.Muted,
    surfaceContainerHighest = Palette.Card,
    error = Palette.Locked, outline = Palette.Ink, outlineVariant = Palette.Line,
)

// Square everywhere; only the small things (dots, switches, app icons) are round.
private val Square = androidx.compose.foundation.shape.RoundedCornerShape(0)
private val Hard = Shapes(extraSmall = Square, small = Square, medium = Square, large = Square, extraLarge = Square)

/** One heavy grotesk, tight and big for numbers and names, capitals with air for labels. */
private val Grotesk = FontFamily.SansSerif
private val Type = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Grotesk, fontWeight = FontWeight.Black, fontSize = 76.sp, lineHeight = 74.sp, letterSpacing = (-2.5).sp),
        displayMedium = displayMedium.copy(fontFamily = Grotesk, fontWeight = FontWeight.Black, fontSize = 64.sp, lineHeight = 62.sp, letterSpacing = (-2).sp),
        displaySmall = displaySmall.copy(fontFamily = Grotesk, fontWeight = FontWeight.Black, fontSize = 44.sp, lineHeight = 44.sp, letterSpacing = (-1.2).sp),
        headlineLarge = headlineLarge.copy(fontFamily = Grotesk, fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
        headlineMedium = headlineMedium.copy(fontFamily = Grotesk, fontWeight = FontWeight.Black, letterSpacing = (-0.8).sp),
        headlineSmall = headlineSmall.copy(fontFamily = Grotesk, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        titleLarge = titleLarge.copy(fontFamily = Grotesk, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp),
        titleMedium = titleMedium.copy(fontFamily = Grotesk, fontWeight = FontWeight.Bold),
        titleSmall = titleSmall.copy(fontFamily = Grotesk, fontWeight = FontWeight.Bold),
        bodyLarge = bodyLarge.copy(fontFamily = Grotesk),
        bodyMedium = bodyMedium.copy(fontFamily = Grotesk),
        bodySmall = bodySmall.copy(fontFamily = Grotesk),
        labelLarge = labelLarge.copy(fontFamily = Grotesk, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp),
        labelMedium = labelMedium.copy(fontFamily = Grotesk, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        labelSmall = TextStyle(fontFamily = Grotesk, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp),
    )
}

/** Round, for the few things that are round on purpose. */
val Dot = CircleShape

@Composable
fun LockTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme(), shapes = Hard, typography = Type, content = content)
