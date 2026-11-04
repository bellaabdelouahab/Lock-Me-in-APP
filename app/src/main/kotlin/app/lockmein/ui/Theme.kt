package app.lockmein.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Night sky: deep navy surfaces, a violet accent for what you do, and three signal colours that always mean the same thing:
 * mint = checked and working (and day), coral = locked or broken, amber = the open window or something to look at.
 * The old names stay (Bar, Rule...) so every screen takes the new look at once.
 */
object Palette {
    val Night = Color(0xFF0B1020)
    val Surface = Color(0xFF0B1020)
    val Card = Color(0xFF161D33)
    val CardHigh = Color(0xFF1F2844)
    val Line = Color(0xFF2A3454)
    val Bar = Color(0xFF7C83FF)
    val Rule = Color(0xFF5B63E6)
    val RuleDark = Color(0xFF2B3170)
    val Locked = Color(0xFFFF5C6C)
    val Open = Color(0xFFFFB547)
    val Day = Color(0xFF3DDC97)
    val Muted = Color(0xFF98A2C3)

    /** The hero cards: a slow fade from a signal colour into the night. */
    fun glow(color: Color) = Brush.linearGradient(listOf(color.copy(alpha = 0.45f), RuleDark.copy(alpha = 0.85f), Card))
}

private val Scheme = darkColorScheme(
    primary = Palette.Bar,
    onPrimary = Color(0xFF0B1020),
    primaryContainer = Palette.RuleDark,
    onPrimaryContainer = Color.White,
    secondary = Palette.Day,
    background = Palette.Night,
    onBackground = Color(0xFFEEF1FF),
    surface = Palette.Night,
    onSurface = Color(0xFFEEF1FF),
    surfaceVariant = Palette.Card,
    onSurfaceVariant = Palette.Muted,
    surfaceContainerHighest = Palette.CardHigh,
    error = Palette.Locked,
    outline = Palette.Line,
    outlineVariant = Palette.Line,
)

private val Rounded = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val Type = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
    )
}

/** Dark on purpose: this app is for the night, and a bright screen at 2am defeats it. */
@Composable
fun LockTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, shapes = Rounded, typography = Type, content = content)
