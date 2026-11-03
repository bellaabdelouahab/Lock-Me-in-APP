package app.lockmein.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The palette: dark grey surfaces, blue rule cards and top bar, red for locked, amber for the open window, green for day. */
object Palette {
    val Bar = Color(0xFF1976D2)
    val Rule = Color(0xFF0288D1)
    val RuleDark = Color(0xFF01579B)
    val Surface = Color(0xFF262626)
    val Card = Color(0xFF353535)
    val Locked = Color(0xFFE53935)
    val Open = Color(0xFFFFB300)
    val Day = Color(0xFF43A047)
    val Muted = Color(0xFFB0B0B0)
}

private val Scheme = darkColorScheme(
    primary = Color(0xFF2196F3),
    onPrimary = Color.White,
    primaryContainer = Palette.Rule,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF64B5F6),
    background = Palette.Surface,
    onBackground = Color.White,
    surface = Palette.Surface,
    onSurface = Color.White,
    surfaceVariant = Palette.Card,
    onSurfaceVariant = Palette.Muted,
    error = Color(0xFFEF5350),
    outline = Color(0xFF6E6E6E),
)

private val Rounded = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
)

/** Dark on purpose: this app is for the night, and a bright screen at 2am defeats it. */
@Composable
fun LockTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, shapes = Rounded, content = content)
