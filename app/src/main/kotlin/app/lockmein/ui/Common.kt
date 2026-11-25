package app.lockmein.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.lockmein.AppInfo
import app.lockmein.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen destinations.
 * Existing names (LISTS, WINDOW, EMERGENCY) are kept for compatibility with intents and tests.
 */
enum class Screen {
    HOME,
    SETUP,
    LISTS,     // Apps
    BROWSERS,
    WINDOW,    // Requests
    EMERGENCY, // Friend's code
    REPORT,
    SETTINGS,
    REMOVE,
    HEALTH,    // Health check
    ABOUT,
    PHONE_USE,
    FILTER,    // Screen filter: review, per app, eval, judge
    THEMES,    // Look and wallpaper
    REMOTE,    // Connect Claude (MCP link)
}

val LocalNavigate = compositionLocalOf<(Screen) -> Unit> { {} }
val LocalOpenDrawer = compositionLocalOf<() -> Unit> { {} }
/** True on a page the side bar lists: its top bar shows the menu button; the phone's back still goes home. */
val LocalTopLevel = compositionLocalOf { false }

/** Pages merged into one side-bar entry: one title, and a tab per page under the top bar. */
data class PageTabs(val title: String, val labels: List<String>, val selected: Int, val onSelect: (Int) -> Unit)
val LocalPageTabs = compositionLocalOf<PageTabs?> { null }

@Composable
fun Navigation(navigate: (Screen) -> Unit, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalNavigate provides navigate, content = content)

/**
 * Common page skeleton:
 * - Top app bar on the page background: menu icon (pages in the side bar) or back arrow (subpages), title in sentence case, optional actions.
 * - Single column, 16dp side margins, 12dp between blocks; sections are opened by a quiet [Heading].
 * - Pinned bottom bar for the one primary thumb-zone action.
 */
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
    val openDrawer = LocalOpenDrawer.current
    val topLevel = LocalTopLevel.current
    val tabs = LocalPageTabs.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
            TopAppBar(
                title = { Text(tabs?.title ?: title, style = MaterialTheme.typography.titleLarge, maxLines = 1) },
                navigationIcon = {
                    if (onBack != null && !topLevel) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    } else {
                        IconButton(onClick = openDrawer) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open side bar")
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
            if (tabs != null) {
                androidx.compose.material3.PrimaryTabRow(
                    selectedTabIndex = tabs.selected,
                    containerColor = MaterialTheme.colorScheme.background,
                    divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
                ) {
                    tabs.labels.forEachIndexed { i, label ->
                        androidx.compose.material3.Tab(
                            selected = tabs.selected == i,
                            onClick = { tabs.onSelect(i) },
                            selectedContentColor = MaterialTheme.colorScheme.primary,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            text = { Text(label, maxLines = 1) },
                        )
                    }
                }
            }
            }
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Section label: small, quiet, sentence case, set a little apart from what came before. The cards under it carry the weight. */
@Composable
fun Heading(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier
        .fillMaxWidth()
        .padding(start = 4.dp, top = 12.dp),
)

@Composable
fun Note(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 4.dp),
)

/** Status indicator: Green (OK), Amber (Needs attention), Red (Broken/Closed). Never color alone. */
enum class StatusKind { OK, ATTENTION, CLOSED }

@Composable
fun statusColor(kind: StatusKind): Color = when (kind) {
    StatusKind.OK -> Palette.Ok
    StatusKind.ATTENTION -> Palette.Attention
    StatusKind.CLOSED -> Palette.Closed
}

private fun statusIcon(kind: StatusKind): ImageVector = when (kind) {
    StatusKind.OK -> Icons.Filled.CheckCircle
    StatusKind.ATTENTION -> Icons.Filled.WarningAmber
    StatusKind.CLOSED -> Icons.Filled.Block
}

/** A flat pill in the status's quiet container: icon and word in the status colour. */
@Composable
fun StatusBadge(kind: StatusKind, label: String, modifier: Modifier = Modifier) {
    val color = statusColor(kind)
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(Palette.containerOf(color))
            .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(statusIcon(kind), contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Text(
            label,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
fun Problem(text: String?) {
    if (text != null) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            Icon(Icons.Filled.Block, contentDescription = null, tint = Palette.Closed, modifier = Modifier.padding(top = 1.dp).size(18.dp))
            Text(text, color = Palette.Closed, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

/** The sentence an outcome came to, with icon and clear status color. */
@Composable
fun OutcomeText(outcome: Outcome?) = when (outcome) {
    is Outcome.Done -> Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Palette.Ok, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Text(outcome.message, color = Palette.Ok, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
    is Outcome.Refused -> Problem(outcome.reason)
    null -> Unit
}

/**
 * Card container: a flat tonal surface, 20dp corners, no border. The [accent] decides how much colour it may carry:
 * - none, OK or the accent teal on a card you only read: neutral (the icon and words inside say what it is);
 * - attention or closed: a quiet wash of that colour, so a problem is seen before it is read;
 * - the accent teal on a card you tap ([onClick]): selected, a teal wash and a teal edge.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) = TintedCard(modifier, accent ?: Color.Unspecified, onClick, quiet = accent == null, content = content)

@Composable
fun InfoCard(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable ColumnScope.() -> Unit) =
    AppCard(modifier = modifier, accent = accent, content = content)

/** See [AppCard]. [quiet]: always neutral, whatever the colour. [padding]: inner padding, 16dp by default. */
@Composable
fun TintedCard(
    modifier: Modifier,
    color: Color,
    onClick: (() -> Unit)? = null,
    quiet: Boolean = false,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.surfaceContainer
    val primary = MaterialTheme.colorScheme.primary
    val selected = !quiet && onClick != null && color == primary
    val warn = !quiet && (color == Palette.Attention || color == Palette.Closed)
    val background = when {
        selected -> primary.copy(alpha = if (Palette.isDark) 0.14f else 0.08f).compositeOver(neutral)
        warn -> Palette.containerOf(color)
        else -> neutral
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(background)
            .let { if (selected) it.border(1.5.dp, primary, CardShape) else it }
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** A call-out for something that needs a decision: the status wash, an icon, a title and a line, and optional content (a button). */
@Composable
fun Callout(
    kind: StatusKind?,
    title: String,
    text: String? = null,
    icon: ImageVector? = null,
    onDismiss: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val color = if (kind == null) MaterialTheme.colorScheme.primary else statusColor(kind)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(if (kind == null) MaterialTheme.colorScheme.surfaceContainer else Palette.containerOf(color))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon ?: statusIcon(kind ?: StatusKind.OK), contentDescription = null, tint = color, modifier = Modifier.padding(top = 1.dp).size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
        content()
    }
}

/**
 * An icon on a small rounded tile. Status colours (OK, attention, closed) sit on their quiet container; anything else (the
 * accent teal, a plain section icon) is drawn neutral, so colour keeps meaning something. The name is kept for its callers.
 */
@Composable
fun GlowCircle(icon: ImageVector, color: Color, size: Dp = 40.dp) {
    val status = color == Palette.Ok || color == Palette.Attention || color == Palette.Closed
    Box(
        Modifier
            .size(size)
            .clip(TileShape)
            .background(if (status) Palette.containerOf(color) else MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (status) color else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** A number (or a tick, once [done]) in a small circle: numbered steps and chores. */
@Composable
fun NumberDisc(number: Int, @Suppress("UNUSED_PARAMETER") color: Color = MaterialTheme.colorScheme.primary, done: Boolean = false, size: Dp = 28.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (done) Palette.OkContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.Ok, modifier = Modifier.size(size * 0.58f))
        } else {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The title row of a card: an icon tile, a title and an optional line under it. */
@Composable
fun CardTitle(icon: ImageVector, color: Color, title: String, subtitle: String? = null, titleColor: Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GlowCircle(icon, color, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor ?: MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A ring that fills as time runs: a quiet track and a solid arc with round ends. No glow. */
@Composable
fun Ring(progress: Float, size: Dp, stroke: Dp, color: Color, content: @Composable ColumnScope.() -> Unit) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val arc = androidx.compose.ui.geometry.Size(this.size.width - s, this.size.height - s)
            val topLeft = androidx.compose.ui.geometry.Offset(s / 2, s / 2)
            drawArc(track, -90f, 360f, false, topLeft, arc, style = Stroke(s))
            drawArc(color, -90f, 360f * progress.coerceIn(0.01f, 1f), false, topLeft, arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/** The pinned bottom area of a page: the page background with a hairline above, holding the thumb-zone action. */
@Composable
fun BottomBar(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

/** The main action: full-width filled pill, pinned at the bottom in the thumb zone. */
@Composable
fun PrimaryButton(
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth().height(52.dp),
    enabled: Boolean = true,
    onClick: () -> Unit,
) = Button(
    onClick = onClick,
    enabled = enabled,
    shape = ButtonShape,
    modifier = modifier,
    colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ),
) {
    Text(label, style = MaterialTheme.typography.labelLarge)
}

/** Alias for callers expecting MenuButton. */
@Composable
fun MenuButton(label: String, enabled: Boolean = true, onClick: () -> Unit) =
    PrimaryButton(label = label, enabled = enabled, onClick = onClick)

@Composable
private fun tonalColors() = ButtonDefaults.filledTonalButtonColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor = MaterialTheme.colorScheme.primary,
)

/** Secondary action: a full-width tonal pill (no outline), quieter than the primary one. */
@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth().height(48.dp),
    enabled: Boolean = true,
) = FilledTonalButton(
    onClick = onClick,
    enabled = enabled,
    shape = ButtonShape,
    modifier = modifier,
    colors = tonalColors(),
) {
    Text(label, style = MaterialTheme.typography.labelLarge)
}

@Composable
fun RowScope.MenuButtonSmall(label: String, onClick: () -> Unit) =
    Button(
        onClick = onClick,
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
        modifier = Modifier.weight(1f),
    ) {
        Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
    }

@Composable
fun RowScope.SecondaryButtonSmall(label: String, onClick: () -> Unit) =
    FilledTonalButton(
        onClick = onClick,
        shape = ButtonShape,
        colors = tonalColors(),
        modifier = Modifier.weight(1f),
    ) {
        Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
    }

@Composable
fun ButtonRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)

/** Kept for older callers: the same icon tile as [GlowCircle]. */
@Composable
fun IconCircle(icon: ImageVector, color: Color, size: Dp = 40.dp, @Suppress("UNUSED_PARAMETER") tint: Color = Color.White) =
    GlowCircle(icon, color, size)

/** A hairline between rows inside a card, indented past the rows' icons by [inset]. */
@Composable
fun RowDivider(inset: Dp = 0.dp) =
    HorizontalDivider(Modifier.padding(start = inset), color = MaterialTheme.colorScheme.outlineVariant)

/** 40dp app icon with 10dp rounded corners. */
@Composable
fun AppIcon(packageName: String, size: Dp = 40.dp) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val px = 120
            val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, px, px)
            drawable.draw(Canvas(out))
            out.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp)),
        )
    } else {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.6f))
        }
    }
}

/**
 * Standard app row used across every list:
 * App icon (40dp), app label (16sp), one short grey line saying state/reason (14sp), and a right control.
 */
@Composable
fun AppRow(
    packageName: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    statusColor: Color? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(packageName, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        trailing()
    }
}

/** Runs slow work off the main thread. */
fun <T> CoroutineScope.background(work: () -> T, done: (T) -> Unit) {
    launch {
        val result = withContext(Dispatchers.IO) { work() }
        done(result)
    }
}

/** Where one requirement stands: PASS, FAIL, CHECKING, CONFIRM. */
enum class CheckState { PASS, FAIL, CHECKING, CONFIRM }

/**
 * Checklist row: a status icon, title, short detail line, and (when not passing) a status word. On its own it is a card (a quiet
 * wash when failing); with [inCard] it is a bare row for a grouped list inside one card.
 */
@Composable
fun CheckRow(
    title: String,
    state: CheckState,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) = CheckRowImpl(title, state, detail, actionLabel, onAction, inCard = false)

/** A [CheckRow] as a bare row, for a grouped list of checks inside one card. */
@Composable
fun CheckItem(title: String, state: CheckState, detail: String? = null) =
    CheckRowImpl(title, state, detail, null, null, inCard = true)

@Composable
private fun CheckRowImpl(
    title: String,
    state: CheckState,
    detail: String?,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    inCard: Boolean,
) {
    val (statusKind, tagText) = when (state) {
        CheckState.PASS -> StatusKind.OK to "OK"
        CheckState.FAIL -> StatusKind.CLOSED to "Problem"
        CheckState.CHECKING -> StatusKind.ATTENTION to "Checking"
        CheckState.CONFIRM -> StatusKind.ATTENTION to "Action needed"
    }
    val color = when (state) {
        CheckState.PASS -> Palette.Ok
        CheckState.FAIL -> Palette.Closed
        CheckState.CHECKING -> MaterialTheme.colorScheme.primary
        CheckState.CONFIRM -> Palette.Attention
    }
    val icon = when (state) {
        CheckState.PASS -> Icons.Filled.CheckCircle
        CheckState.FAIL -> Icons.Filled.Block
        CheckState.CHECKING, CheckState.CONFIRM -> Icons.Filled.WarningAmber
    }
    val row = @Composable {
        Row(
            modifier = if (inCard) Modifier.fillMaxWidth().padding(vertical = 12.dp) else Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (state == CheckState.CHECKING) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (state != CheckState.CHECKING && state != CheckState.PASS) StatusBadge(statusKind, tagText)
                }
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state == CheckState.FAIL) Palette.Closed else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (actionLabel != null && onAction != null && state != CheckState.PASS) {
                    TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text(actionLabel, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
    if (inCard) row() else TintedCard(Modifier, color) { row() }
}

/** Confirmation dialog for actions (destructive actions in red text). */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String = "Confirm",
    isDestructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = CardShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (isDestructive) Palette.Closed else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Full-screen multi-select app picker with search and pinned bottom confirm button. */
@Composable
fun MultiAppPicker(
    choices: List<AppInfo>,
    onDismiss: () -> Unit,
    onConfirm: (List<AppInfo>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<AppInfo>() }
    val filtered = remember(query, choices) {
        if (query.isBlank()) choices
        else choices.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
    }

    // A dialog window is not told the system bars' size on every phone (HyperOS put the button under the gesture bar), so they
    // are measured here, on the app's own window, and whatever the dialog is not told is added as padding.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val outsideTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val outsideBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val insideTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
        val insideBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
        val extraTop = (outsideTop - insideTop).coerceAtLeast(0.dp)
        val extraBottom = (outsideBottom - insideBottom).coerceAtLeast(0.dp)
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold(
                topBar = {
                    Column(Modifier.statusBarsPadding().padding(top = extraTop)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Filled.Close, contentDescription = "Close")
                            }
                            Text(
                                "Add apps",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected.isNotEmpty()) {
                                TextButton(onClick = { selected.clear() }) { Text("Clear ${selected.size}") }
                            }
                        }
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search apps") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            singleLine = true,
                            shape = FieldShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                },
                bottomBar = {
                    Box(Modifier.padding(bottom = extraBottom)) {
                    BottomBar {
                        PrimaryButton(
                            label = if (selected.isEmpty()) "Select apps" else "Add ${selected.size} app" + if (selected.size > 1) "s" else "",
                            enabled = selected.isNotEmpty(),
                            onClick = { onConfirm(selected.toList()) },
                        )
                    }
                    }
                },
            ) { padding ->
                LazyColumn(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp),
                ) {
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                "No apps match your search.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                    } else {
                        items(filtered, key = { it.packageName }) { app ->
                            val isChecked = selected.any { it.packageName == app.packageName }
                            val toggle = {
                                if (isChecked) selected.removeAll { it.packageName == app.packageName }
                                else selected.add(app)
                            }
                            // A picked app is tinted, not only ticked, so the choice reads from across the list.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(TileShape)
                                    .background(if (isChecked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                    .clickable { toggle() }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                AppIcon(app.packageName, size = 36.dp)
                                Text(
                                    app.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    if (isChecked) Icons.Filled.CheckCircle else Icons.Filled.AddCircleOutline,
                                    contentDescription = if (isChecked) "Picked" else "Pick",
                                    tint = if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }
}

/** Legacy single-app picker for callers still using it. */
@Composable
fun AppPicker(choices: List<AppInfo>, onDismiss: () -> Unit, onPick: (AppInfo) -> Unit) {
    MultiAppPicker(choices = choices, onDismiss = onDismiss) { picked ->
        picked.firstOrNull()?.let(onPick)
    }
}
