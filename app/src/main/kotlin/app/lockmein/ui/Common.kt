package app.lockmein.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
    val (icon, color, bg) = when (kind) {
        StatusKind.OK -> Triple(Icons.Filled.CheckCircle, Palette.Ok, Palette.OkContainer)
        StatusKind.ATTENTION -> Triple(Icons.Filled.WarningAmber, Palette.Attention, Palette.AttentionContainer)
        StatusKind.CLOSED -> Triple(Icons.Filled.Block, Palette.Closed, Palette.ClosedContainer)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bg,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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

/** Card container with 12dp corner radius and 1dp subtle outline. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    AppCard(modifier = modifier, content = content)

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
    border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.weight(1f),
    ) {
        Text(label, maxLines = 1, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
    }

@Composable
fun ButtonRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)

@Composable
fun IconCircle(icon: ImageVector, color: Color, size: Dp = 40.dp, tint: Color = Color.White) {
    Box(Modifier.size(size).clip(Dot).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** 40dp app icon with 8dp rounded corners. */
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
                .clip(RoundedCornerShape(8.dp)),
        )
    } else {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.padding(top = 2.dp), contentAlignment = Alignment.Center) {
                if (state == CheckState.CHECKING) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.primary)
                } else {
                    StatusBadge(statusKind, tagText)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
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
                            shape = CardShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                },
                bottomBar = {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(16.dp),
                        ) {
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
