package app.lockmein.ui

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

/** A page: blue top bar with an optional back arrow and actions, then scrolling content. */
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, titleContentColor = Color.White, navigationIconContentColor = Color.White, actionIconContentColor = Palette.Muted),
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
fun Heading(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.Muted, modifier = Modifier.padding(top = 10.dp, start = 4.dp))

@Composable
fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun Problem(text: String?) {
    if (text != null) Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

/** The sentence an outcome came to, in the colour that fits. */
@Composable
fun OutcomeText(outcome: Outcome?) = when (outcome) {
    is Outcome.Done -> Text(outcome.message, color = Palette.Day)
    is Outcome.Refused -> Text(outcome.reason, color = MaterialTheme.colorScheme.error)
    null -> Unit
}

@Composable
fun InfoCard(content: @Composable ColumnScope.() -> Unit) = Card(
    Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(containerColor = Palette.Card),
    border = BorderStroke(1.dp, Palette.Line),
) {
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
fun MenuButton(label: String, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick = onClick, enabled = enabled, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(label, style = MaterialTheme.typography.titleMedium) }

@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true) =
    OutlinedButton(onClick = onClick, enabled = enabled, shape = CircleShape, border = BorderStroke(1.dp, Palette.Line), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text(label, color = if (enabled) Palette.Bar else Palette.Muted) }

/** Small buttons for rows: the chosen one is filled, the others outlined. */
@Composable
fun RowScope.MenuButtonSmall(label: String, onClick: () -> Unit) =
    Button(onClick = onClick, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }

@Composable
fun RowScope.SecondaryButtonSmall(label: String, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }

@Composable
fun ButtonRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)

/** A soft circle of [color] with the icon in that colour (or [tint]). */
@Composable
fun IconCircle(icon: ImageVector, color: Color, size: Dp = 56.dp, tint: Color = color) {
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
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
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(size).clip(CircleShape))
    else IconCircle(Icons.Filled.Apps, Palette.Muted, size)
}

/** M T W T F S S, with the chosen days filled in, like the rule cards of a lockout app. [days] are ISO numbers, 1 = Monday. */
@Composable
fun DayCircles(days: Set<Int>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        "MTWTFSS".forEachIndexed { index, letter ->
            val on = (index + 1) in days
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(if (on) Color.White else Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) { Text(letter.toString(), color = if (on) Palette.RuleDark else Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.Bold) }
        }
    }
}

/** A blue rule card: title, small icons, the time range, the days, and an optional progress bar. */
@Composable
fun RuleCard(
    title: String,
    icons: List<ImageVector>,
    time: String,
    days: Set<Int>,
    progress: Float? = null,
    caption: String? = null,
    color: Color = Palette.Rule,
    onClick: (() -> Unit)? = null,
) {
    Card(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, Palette.Line),
    ) {
        Column(Modifier.background(Palette.glow(color)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                icons.forEach { Icon(it, contentDescription = null, tint = Color.White, modifier = Modifier.padding(start = 8.dp).size(20.dp)) }
            }
            Text(time, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            DayCircles(days)
            if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Color.White, trackColor = Color.White.copy(alpha = 0.3f))
            if (caption != null) Text(caption, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A grey section card with a title, an optional icon row of apps, and a line of text. Tapping it opens the matching screen. */
@Composable
fun SectionCard(title: String, subtitle: String, packages: List<String> = emptyList(), leading: ImageVector? = null, onClick: (() -> Unit)? = null) {
    Card(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = Palette.Card),
        border = BorderStroke(1.dp, Palette.Line),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (leading != null) IconCircle(leading, Palette.Bar, 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (packages.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) { packages.take(6).forEach { AppIcon(it, 30.dp) } }
            }
            if (onClick != null) Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
 * One requirement, with its live state: a mint tick once the phone itself confirms it, coral while it does not hold, a spinner
 * while it is being checked. [detail] says what was found or what to do; [action] goes to where it is fixed.
 */
@Composable
fun CheckRow(title: String, state: CheckState, detail: String? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val color = when (state) {
        CheckState.PASS -> Palette.Day
        CheckState.FAIL -> Palette.Locked
        CheckState.CHECKING -> Palette.Muted
        CheckState.CONFIRM -> Palette.Open
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = Palette.Card),
        border = BorderStroke(1.dp, if (state == CheckState.PASS) Palette.Day.copy(alpha = 0.45f) else Palette.Line),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    when (state) {
                        CheckState.CHECKING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = color)
                        CheckState.PASS -> Icon(Icons.Filled.CheckCircle, null, tint = color, modifier = Modifier.size(22.dp))
                        CheckState.FAIL -> Icon(Icons.Filled.ErrorOutline, null, tint = color, modifier = Modifier.size(22.dp))
                        CheckState.CONFIRM -> Icon(Icons.Filled.Info, null, tint = color, modifier = Modifier.size(22.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = if (state == CheckState.FAIL) Palette.Locked.copy(alpha = 0.9f) else Palette.Muted)
                }
            }
            if (actionLabel != null && onAction != null && state != CheckState.PASS) SecondaryButton(actionLabel, onAction)
        }
    }
}
