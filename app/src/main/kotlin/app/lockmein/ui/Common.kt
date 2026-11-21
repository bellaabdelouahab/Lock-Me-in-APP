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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
    HEALTH,    // Is it working?
    ABOUT,
}

val LocalNavigate = compositionLocalOf<(Screen) -> Unit> { {} }
val LocalOpenDrawer = compositionLocalOf<() -> Unit> { {} }

@Composable
fun Navigation(navigate: (Screen) -> Unit, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalNavigate provides navigate, content = content)

/**
 * Common page skeleton:
 * - Top app bar: left = menu icon (on Home) or back arrow (on subpages); center = title in sentence case; right = optional single icon action.
 * - Single column, 16dp horizontal padding, consistent 8dp rhythm.
 * - Pinned bottom bar container for the primary thumb-zone action.
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
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1) },
                navigationIcon = {
                    if (onBack != null) {
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
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Standard section header: sentence case, 16sp SemiBold. */
@Composable
fun Heading(text: String) = Column(
    Modifier
        .fillMaxWidth()
        .padding(top = 10.dp, bottom = 2.dp),
) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
fun Note(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** Status indicator: Green (OK), Amber (Needs attention), Red (Broken/Closed). Never color alone. */
enum class StatusKind { OK, ATTENTION, CLOSED }

@Composable
fun StatusBadge(kind: StatusKind, label: String, modifier: Modifier = Modifier) {
    val (icon, color) = when (kind) {
        StatusKind.OK -> Icons.Filled.CheckCircle to Palette.Ok
        StatusKind.ATTENTION -> Icons.Filled.WarningAmber to Palette.Attention
        StatusKind.CLOSED -> Icons.Filled.Block to Palette.Closed
    }
    // A lit pill: a faint wash of the colour, edged in it.
    val dark = Palette.isDark
    val base = MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(color.copy(alpha = if (dark) 0.16f else 0.12f).compositeOver(base))
            .border(1.dp, color.copy(alpha = if (dark) 0.45f else 0.35f), PillShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
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
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            Icon(Icons.Filled.Block, contentDescription = null, tint = Palette.Closed, modifier = Modifier.size(18.dp))
            Text(text, color = Palette.Closed, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

/** The sentence an outcome came to, with icon and clear status color. */
@Composable
fun OutcomeText(outcome: Outcome?) = when (outcome) {
    is Outcome.Done -> Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Palette.Ok, modifier = Modifier.size(18.dp))
        Text(outcome.message, color = Palette.Ok, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
    is Outcome.Refused -> Problem(outcome.reason)
    null -> Unit
}

/**
 * Card container: 16dp corners, a faint vertical wash and a 1dp edge. With an [accent] it is edged and lit in the colour of what
 * it reports (green OK, amber attention, red closed, blue dates); without one the edge is a quiet tint of the primary teal.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) = TintedCard(modifier, accent ?: MaterialTheme.colorScheme.primary, onClick, quiet = accent == null, content = content)

@Composable
fun InfoCard(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable ColumnScope.() -> Unit) =
    AppCard(modifier = modifier, accent = accent, content = content)

/** A card edged and faintly lit in the colour of what it reports. [quiet]: a neutral card, the colour only hinted at. */
@Composable
fun TintedCard(
    modifier: Modifier,
    color: Color,
    onClick: (() -> Unit)? = null,
    quiet: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = Palette.isDark
    val base = MaterialTheme.colorScheme.surfaceVariant
    val wash = if (quiet) (if (dark) 0.04f else 0.025f) else (if (dark) 0.10f else 0.06f)
    val edge = if (quiet) (if (dark) 0.22f else 0.18f) else (if (dark) 0.45f else 0.35f)
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Brush.verticalGradient(listOf(color.copy(alpha = wash).compositeOver(base), base)))
            .border(1.dp, color.copy(alpha = edge), CardShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** An icon in a disc lit from the middle, edged in its colour: sections, statuses, quick actions. */
@Composable
fun GlowCircle(icon: ImageVector, color: Color, size: Dp = 48.dp) {
    val dark = Palette.isDark
    Box(
        Modifier.size(size).drawBehind {
            drawCircle(
                Brush.radialGradient(
                    0f to color.copy(alpha = if (dark) 0.45f else 0.30f),
                    1f to color.copy(alpha = if (dark) 0.14f else 0.12f),
                    center = center, radius = this.size.minDimension / 2,
                ),
            )
            drawCircle(color.copy(alpha = 0.35f), style = Stroke(1.dp.toPx()))
        },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = if (dark) Color.White.copy(alpha = 0.92f) else color, modifier = Modifier.size(size * 0.48f)) }
}

/** A number (or a tick, once [done]) in a small lit disc: numbered steps and chores. */
@Composable
fun NumberDisc(number: Int, color: Color = MaterialTheme.colorScheme.primary, done: Boolean = false, size: Dp = 28.dp) {
    if (done) return GlowCircle(Icons.Filled.Check, Palette.Ok, size)
    val dark = Palette.isDark
    Box(
        Modifier.size(size).drawBehind {
            drawCircle(color.copy(alpha = if (dark) 0.22f else 0.14f))
            drawCircle(color.copy(alpha = 0.40f), style = Stroke(1.dp.toPx()))
        },
        contentAlignment = Alignment.Center,
    ) {
        Text("$number", style = MaterialTheme.typography.labelMedium, color = if (dark) Color.White.copy(alpha = 0.92f) else color, fontWeight = FontWeight.Bold)
    }
}

/** The title row of a card: a lit icon, a title and an optional line under it. */
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

/** A ring that fills as time runs: a faint track, an arc brightening toward its end, a soft halo. */
@Composable
fun Ring(progress: Float, size: Dp, stroke: Dp, color: Color, content: @Composable ColumnScope.() -> Unit) {
    val track = color.copy(alpha = if (Palette.isDark) 0.16f else 0.18f)
    val glow = color.copy(alpha = if (Palette.isDark) 0.30f else 0.14f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val arc = androidx.compose.ui.geometry.Size(this.size.width - s, this.size.height - s)
            val topLeft = androidx.compose.ui.geometry.Offset(s / 2, s / 2)
            drawCircle(
                Brush.radialGradient(
                    0.55f to Color.Transparent, 0.80f to glow, 1f to Color.Transparent,
                    center = center, radius = this.size.minDimension / 2 + s,
                ),
                radius = this.size.minDimension / 2 + s,
            )
            drawArc(track, -90f, 360f, false, topLeft, arc, style = Stroke(s))
            val sweep = Brush.sweepGradient(
                0f to color.copy(alpha = 0.55f), (progress.coerceAtLeast(0.01f)) to color, 1f to color.copy(alpha = 0.55f),
                center = center,
            )
            rotate(-90f) { drawArc(sweep, 0f, 360f * progress, false, topLeft, arc, style = Stroke(s, cap = StrokeCap.Round)) }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/** The pinned bottom area of a page: the page background with a hairline above, holding the thumb-zone action. */
@Composable
fun BottomBar(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = if (Palette.isDark) 0.22f else 0.16f))
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

/** The main action: full-width filled button pinned at the bottom in the thumb zone. */
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

/** Secondary action: full-width outlined button. */
@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth().height(48.dp),
    enabled: Boolean = true,
) = OutlinedButton(
    onClick = onClick,
    enabled = enabled,
    shape = ButtonShape,
    modifier = modifier,
    border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant),
) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    OutlinedButton(
        onClick = onClick,
        shape = ButtonShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        modifier = Modifier.weight(1f),
    ) {
        Text(label, maxLines = 1, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
    }

@Composable
fun ButtonRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)

/** Kept for older callers: the same lit disc as [GlowCircle]. */
@Composable
fun IconCircle(icon: ImageVector, color: Color, size: Dp = 40.dp, @Suppress("UNUSED_PARAMETER") tint: Color = Color.White) =
    GlowCircle(icon, color, size)

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

/** Checklist row: icon, title, short detail line, status tag. Failing ones are red/amber, verified are green. */
@Composable
fun CheckRow(
    title: String,
    state: CheckState,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
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
    TintedCard(Modifier, color) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                if (state == CheckState.CHECKING) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.primary)
                } else {
                    GlowCircle(icon, color, size = 36.dp)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (state != CheckState.CHECKING) StatusBadge(statusKind, tagText)
                }
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state == CheckState.FAIL) Palette.Closed else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (actionLabel != null && onAction != null && state != CheckState.PASS) {
                    TextButton(onClick = onAction, modifier = Modifier.padding(top = 2.dp)) {
                        Text(actionLabel, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
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
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold(
                topBar = {
                    Column(Modifier.statusBarsPadding()) {
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
                    BottomBar {
                        PrimaryButton(
                            label = if (selected.isEmpty()) "Select apps" else "Add ${selected.size} app" + if (selected.size > 1) "s" else "",
                            enabled = selected.isNotEmpty(),
                            onClick = { onConfirm(selected.toList()) },
                        )
                    }
                },
            ) { padding ->
                LazyColumn(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
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
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isChecked) selected.removeAll { it.packageName == app.packageName }
                                        else selected.add(app)
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AppIcon(app.packageName, size = 40.dp)
                                Text(
                                    app.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        if (checked) selected.add(app)
                                        else selected.removeAll { it.packageName == app.packageName }
                                    },
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
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
