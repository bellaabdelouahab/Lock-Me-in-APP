package app.lockmein.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.RectangleShape
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lockmein.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { HOME, SETUP, LISTS, BROWSERS, WINDOW, EMERGENCY, REPORT, SETTINGS, REMOVE, HEALTH }

/** Where a screen can send the person. */
val LocalNavigate = compositionLocalOf<(Screen) -> Unit> { {} }

@Composable
fun Navigation(navigate: (Screen) -> Unit, content: @Composable () -> Unit) = CompositionLocalProvider(LocalNavigate provides navigate, content = content)

/** A thick ink rule: the poster's way of separating things instead of boxes. */
@Composable
fun Rule(thick: Boolean = true) = Box(Modifier.fillMaxWidth().height(if (thick) 3.dp else 1.dp).background(if (thick) Palette.Ink else Palette.Line))

/** A page: the title in capitals with a thick rule under it, an optional back arrow and actions, then scrolling content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onBack != null) BackHandler(onBack = onBack)
    Scaffold(
        containerColor = Palette.Paper,
        topBar = {
            Column(Modifier.background(Palette.Paper).statusBarsPadding()) {
                TopAppBar(
                    title = { Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, maxLines = 1) },
                    navigationIcon = {
                        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    },
                    actions = actions,
                    windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Paper, titleContentColor = Palette.Ink, navigationIconContentColor = Palette.Ink, actionIconContentColor = Palette.Ink),
                )
                Box(Modifier.padding(horizontal = 20.dp)) { Rule() }
            }
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** A section label: small capitals, then a thin rule. */
@Composable
fun Heading(text: String) = Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.Ink)
    Rule(thick = false)
}

@Composable
fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)

@Composable
fun Problem(text: String?) {
    if (text != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp).size(8.dp).background(Palette.Locked))
        Text(text, color = Palette.Locked, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** The sentence an outcome came to, in the colour that fits. */
@Composable
fun OutcomeText(outcome: Outcome?) = when (outcome) {
    is Outcome.Done -> Text(outcome.message, color = Palette.Day, fontWeight = FontWeight.SemiBold)
    is Outcome.Refused -> Problem(outcome.reason)
    null -> Unit
}

/** No box: the content sits on the paper with a thick rule on its left, like a margin note on a poster. */
@Composable
fun InfoCard(content: @Composable ColumnScope.() -> Unit) = Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
    Box(Modifier.fillMaxHeight().width(3.dp).background(Palette.Ink))
    Column(Modifier.weight(1f).padding(start = 14.dp, top = 2.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** The main action: a square ink block with capitals. */
@Composable
fun MenuButton(label: String, enabled: Boolean = true, onClick: () -> Unit) =
    Button(
        onClick = onClick, enabled = enabled, shape = RectangleShape, modifier = Modifier.fillMaxWidth().height(58.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Ink, contentColor = Palette.Paper, disabledContainerColor = Palette.Line, disabledContentColor = Palette.Muted),
    ) { Text(label.uppercase(), style = MaterialTheme.typography.labelLarge) }

/** A second action: a square ink outline. */
@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true) =
    OutlinedButton(onClick = onClick, enabled = enabled, shape = RectangleShape, border = BorderStroke(2.dp, if (enabled) Palette.Ink else Palette.Line), modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelLarge, color = if (enabled) Palette.Ink else Palette.Muted)
    }

/** Small buttons for rows: the chosen one is filled, the others outlined. */
@Composable
fun RowScope.MenuButtonSmall(label: String, onClick: () -> Unit) =
    Button(onClick = onClick, shape = RectangleShape, colors = ButtonDefaults.buttonColors(containerColor = Palette.Ink, contentColor = Palette.Paper), modifier = Modifier.weight(1f)) { Text(label.uppercase(), maxLines = 1, style = MaterialTheme.typography.labelMedium) }

@Composable
fun RowScope.SecondaryButtonSmall(label: String, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, shape = RectangleShape, border = BorderStroke(2.dp, Palette.Ink), modifier = Modifier.weight(1f)) { Text(label.uppercase(), maxLines = 1, color = Palette.Ink, style = MaterialTheme.typography.labelMedium) }

@Composable
fun ButtonRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)

/** One of the few round things: a solid dot of [color] with the icon on it in paper. */
@Composable
fun IconCircle(icon: ImageVector, color: Color, size: Dp = 56.dp, tint: Color = Palette.Paper) {
    Box(Modifier.size(size).clip(Dot).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** The app's own icon, as the phone shows it. */
@Composable
fun AppIcon(packageName: String, size: Dp = 36.dp) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val px = 96
            val out = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, px, px)
            drawable.draw(android.graphics.Canvas(out))
            out.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(size).clip(Dot))
    else IconCircle(Icons.Filled.Apps, Palette.Muted, size)
}

/** M T W T F S S as squares: filled ink for the chosen days. [days] are ISO numbers, 1 = Monday. */
@Composable
fun DayCircles(days: Set<Int>, on: Color = Palette.Ink, off: Color = Palette.Line, onText: Color = Palette.Paper) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        "MTWTFSS".forEachIndexed { index, letter ->
            val chosen = (index + 1) in days
            Box(Modifier.weight(1f).height(30.dp).background(if (chosen) on else Color.Transparent).border(1.dp, if (chosen) on else off), contentAlignment = Alignment.Center) {
                Text(letter.toString(), color = if (chosen) onText else Palette.Muted, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** A rule as a poster block: a solid slab of [color] (ink by default), the name in capitals, the time huge, the days, a square progress bar. */
@Composable
fun RuleCard(
    title: String,
    icons: List<ImageVector>,
    time: String,
    days: Set<Int>,
    progress: Float? = null,
    caption: String? = null,
    color: Color = Palette.Ink,
    onClick: (() -> Unit)? = null,
) {
    val slab = if (color == Palette.Signal || color == Palette.Rule) Palette.Signal else Palette.Ink
    Column(
        Modifier.fillMaxWidth().background(slab).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), color = Palette.Paper, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            icons.forEach { Icon(it, contentDescription = null, tint = Palette.Paper, modifier = Modifier.padding(start = 8.dp).size(18.dp)) }
            if (onClick != null) Text("  \u2192", color = Palette.Paper, style = MaterialTheme.typography.titleLarge)
        }
        Text(time, color = Palette.Paper, style = MaterialTheme.typography.headlineMedium)
        DayCircles(days, on = Palette.Paper, off = Palette.Paper.copy(alpha = 0.35f), onText = slab)
        if (progress != null) Box(Modifier.fillMaxWidth().height(6.dp).background(Palette.Paper.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(6.dp).background(Palette.Paper))
        }
        if (caption != null) Text(caption, color = Palette.Paper.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
    }
}

/** A row on the paper, no box: icon, the name in bold, a line of text, app icons, an arrow; a thin rule under it. */
@Composable
fun SectionCard(title: String, subtitle: String, packages: List<String> = emptyList(), leading: ImageVector? = null, onClick: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (leading != null) Icon(leading, null, tint = Palette.Ink, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                if (packages.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) { packages.take(6).forEach { AppIcon(it, 26.dp) } }
            }
            if (onClick != null) Text("\u2192", style = MaterialTheme.typography.headlineSmall, color = Palette.Ink)
        }
        Rule(thick = false)
    }
}

/** Runs slow work (the network, the DNS check, the policy pass) off the main thread and hands the result back. */
fun <T> CoroutineScope.background(work: () -> T, done: (T) -> Unit) {
    launch {
        val result = withContext(Dispatchers.IO) { work() }
        done(result)
    }
}

/** Where one requirement stands: checked by the app (PASS or FAIL), being checked, or one only the person can confirm. */
enum class CheckState { PASS, FAIL, CHECKING, CONFIRM }

/**
 * One requirement, with its live state, as a line on the paper: a round status dot (green once the phone itself confirms it,
 * red while it does not hold, yellow when only you can confirm it), the name, what was found; a thin rule under it.
 */
@Composable
fun CheckRow(title: String, state: CheckState, detail: String? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val color = when (state) {
        CheckState.PASS -> Palette.Day
        CheckState.FAIL -> Palette.Locked
        CheckState.CHECKING -> Palette.Muted
        CheckState.CONFIRM -> Palette.Open
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.padding(top = 2.dp).size(22.dp), contentAlignment = Alignment.Center) {
                if (state == CheckState.CHECKING) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.5.dp, color = Palette.Ink)
                else Box(Modifier.size(16.dp).clip(Dot).background(color))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = if (state == CheckState.FAIL) Palette.Locked else Palette.Muted)
                if (actionLabel != null && onAction != null && state != CheckState.PASS) {
                    Text("${actionLabel.uppercase()}  \u2192", style = MaterialTheme.typography.labelLarge, color = Palette.Signal,
                        modifier = Modifier.padding(top = 6.dp).clickable(onClick = onAction))
                }
            }
            Text(
                when (state) { CheckState.PASS -> "OK"; CheckState.FAIL -> "NO"; CheckState.CHECKING -> "..."; CheckState.CONFIRM -> "YOU" },
                style = MaterialTheme.typography.labelLarge, color = if (state == CheckState.CHECKING) Palette.Muted else color,
            )
        }
        Rule(thick = false)
    }
}
