package app.lockmein.ui

import android.app.admin.DevicePolicyManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Shield
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Insights
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lockmein.BuildConfig
import app.lockmein.ChangeManager
import app.lockmein.ConfigStore
import app.lockmein.DeviceInventory
import app.lockmein.Health
import app.lockmein.HealthItem
import app.lockmein.LockState
import app.lockmein.Outcome
import app.lockmein.Release
import app.lockmein.Rules
import app.lockmein.Updater
import app.lockmein.core.AstronomicalFajr
import app.lockmein.core.CountdownFormatter
import app.lockmein.core.LockConfig
import app.lockmein.core.Phase
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_TIME = DateTimeFormatter.ofPattern("EEE HH:mm")

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val navigate = LocalNavigate.current
    val store = remember { ConfigStore(context) }
    val state = remember { LockState(context) }
    val config = store.load()
    val owner = context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)
    val active = store.protectionActive && config != null

    Page(
        title = "Lock Me In",
    ) {
        if (!active || config == null) {
            WelcomeView(owner = owner) { navigate(Screen.SETUP) }
        } else {
            DashboardView(config = config, state = state, owner = owner)
        }
    }
}

@Composable
private fun WelcomeView(owner: Boolean, onSetup: () -> Unit) {
    AppCard {
        Text("No fight at night", style = MaterialTheme.typography.titleLarge)
        Text(
            "You choose the rules while you are calm. At night the phone keeps them, and opening anything up waits a week.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Heading("How it works")
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("1. Set it up once", fontWeight = FontWeight.SemiBold)
            Text("Seven steps. Each one is verified on the phone before you go on.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("2. The night closes the phone", fontWeight = FontWeight.SemiBold)
            Text("From your night start until fajr only your chosen apps run.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("3. Opening up waits a week", fontWeight = FontWeight.SemiBold)
            Text("Closing more is instant. Opening is a request carried out in the weekly window.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    CheckRow(
        title = "Device owner",
        state = if (owner) CheckState.PASS else CheckState.CONFIRM,
        detail = if (owner) "Lock Me In is the device owner." else "Not device owner yet: step 1 walks you through it.",
    )

    PrimaryButton(label = "Start setup", onClick = onSetup)
    ResetSetup(onReset = {})
}

@Composable
private fun DashboardView(config: LockConfig, state: LockState, owner: Boolean) {
    val context = LocalContext.current
    val navigate = LocalNavigate.current
    val store = remember { ConfigStore(context) }
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val now = app.lockmein.TrustedClock(context).now()
    val schedule = remember(config) { Rules.schedule(config, zone) }
    val fajr = remember(config) { AstronomicalFajr(config.fajrConfig(), zone) }
    val phase = schedule.phaseAt(now)
    val next = schedule.nextTransition(now)
    val local = ZonedDateTime.ofInstant(now, zone)
    val inventory = remember { DeviceInventory(context) }
    val waiting = state.requests
    val unblocks = state.unblocks(now)

    // Check for updates
    var latestRelease by remember { mutableStateOf<Release?>(null) }
    LaunchedEffect(Unit) {
        latestRelease = withContext(Dispatchers.IO) { Updater.latest() }
    }
    val hasUpdate = Updater.isNewer(latestRelease)
    var bannerDismissed by remember { mutableStateOf(false) }

    // Combined top alert card: setup not finished OR update available
    val setupIncomplete = !store.setupComplete
    if (!bannerDismissed && (setupIncomplete || hasUpdate)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = CardShape,
            colors = CardDefaults.cardColors(
                containerColor = if (setupIncomplete) Palette.AttentionContainer else MaterialTheme.colorScheme.primaryContainer,
            ),
            border = BorderStroke(1.dp, if (setupIncomplete) Palette.Attention else MaterialTheme.colorScheme.primary),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (setupIncomplete) Icons.Filled.WarningAmber else Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = if (setupIncomplete) Palette.Attention else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        if (setupIncomplete) (if (store.redoing) "Setup reopened" else "Setup not complete")
                        else "Update ready: version ${latestRelease?.name}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (setupIncomplete) Palette.Attention else MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                    )
                    IconButton(onClick = { bannerDismissed = true }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                    }
                }
                Text(
                    if (setupIncomplete) "Every change works at once until the setup passes every check and completes."
                    else latestRelease?.notes?.ifBlank { "A newer version is ready to install." } ?: "A newer version is ready to install.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (setupIncomplete) {
                    PrimaryButton(
                        label = if (store.redoing) "Go through steps" else "Finish setup",
                        onClick = { navigate(Screen.SETUP) },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    )
                } else if (hasUpdate) {
                    PrimaryButton(
                        label = "Update now",
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { Updater.install(context) {} }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    )
                }
            }
        }
    }

    // Hero: the current phase as a ring that fills up as it runs out, with the time left in the middle.
    val countdown = remember(config, now) { CountdownFormatter.compute(schedule, config, now, zone) }
    val (phaseName, phaseIcon) = when (phase) {
        Phase.NIGHT -> "Night" to Icons.Filled.NightsStay
        Phase.OPEN_WINDOW -> "Settings window" to Icons.Filled.EventAvailable
        Phase.DAY -> "Day" to Icons.Filled.WbSunny
    }
    val (spanStart, spanEnd) = remember(config, now) { phaseSpan(schedule, phase, now) }
    val nextPhaseName = if (phase == Phase.NIGHT) "day" else "night"

    val healthItems by produceState<List<HealthItem>?>(null, config) {
        value = withContext(Dispatchers.IO) { Health(context).check() }
    }
    val failingHealth = healthItems?.count { !it.ok } ?: 0

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Ring(
                progress = fraction(spanStart, spanEnd, now),
                size = 128.dp,
                stroke = 10.dp,
                color = if (phase == Phase.NIGHT) MaterialTheme.colorScheme.primary else Palette.Attention,
            ) {
                Text(short(Duration.between(now, spanEnd)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("until $nextPhaseName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(phaseIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Text(phaseName, style = MaterialTheme.typography.titleLarge)
                }
                // The badge never claims more than Android confirms: open setup or a failing check is not "Protected".
                when {
                    healthItems == null -> StatusBadge(kind = StatusKind.ATTENTION, label = "Checking")
                    failingHealth > 0 -> StatusBadge(kind = StatusKind.ATTENTION, label = "Not fully protected")
                    !store.setupComplete -> StatusBadge(kind = StatusKind.ATTENTION, label = "Setup open")
                    else -> StatusBadge(kind = StatusKind.OK, label = if (phase == Phase.OPEN_WINDOW) "Window open" else "Protected")
                }
                Text(
                    "${nextPhaseName.replaceFirstChar { it.uppercase() }} at ${CLOCK.format(spanEnd.atZone(zone))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().clickable { navigate(Screen.HEALTH) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                healthItems == null -> {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Checking protection...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                failingHealth == 0 -> {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Palette.Ok, modifier = Modifier.size(18.dp))
                    Text("Protection verified", style = MaterialTheme.typography.bodyMedium, color = Palette.Ok, fontWeight = FontWeight.Medium)
                }
                else -> {
                    Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Palette.Attention, modifier = Modifier.size(18.dp))
                    Text(
                        if (failingHealth == 1) "1 item needs attention" else "$failingHealth items need attention",
                        style = MaterialTheme.typography.bodyMedium, color = Palette.Attention, fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }

    // Quick actions
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        QuickAction(Icons.Filled.Apps, "Apps") { navigate(Screen.LISTS) }
        QuickAction(Icons.Filled.EventAvailable, "Requests", badge = waiting.size) { navigate(Screen.WINDOW) }
        QuickAction(Icons.Filled.Key, "Friend's code") { navigate(Screen.EMERGENCY) }
        QuickAction(Icons.Filled.Insights, "Report") { navigate(Screen.REPORT) }
    }

    // Insights
    Heading("Insights")
    val closedNow by produceState<Int?>(null, config) {
        value = withContext(Dispatchers.IO) { runCatching { app.lockmein.ClosedReasons.all(context, config, now).count { it.closedNow } }.getOrNull() }
    }
    val filterWeek by produceState<Pair<Int, Int>?>(null) {
        value = withContext(Dispatchers.IO) {
            val since = now.minus(Duration.ofDays(7))
            val events = app.lockmein.EventLog(context).read().filter { it.at.isAfter(since) }
            events.count { it.kind == app.lockmein.core.EventKind.FILTER_HIT } to events.count { it.kind == app.lockmein.core.EventKind.FILTER_BLOCKED }
        }
    }
    val windowOpen = app.lockmein.core.Schedule.DAILY_WINDOW_START
    val windowClose = app.lockmein.core.Schedule.DAILY_WINDOW_END
    val todayOpen = local.with(windowOpen).toInstant()
    val todayClose = local.with(windowClose).toInstant()
    val appsOpenNow = now >= todayOpen && now < todayClose
    val nextOpen = if (now < todayOpen) todayOpen else todayOpen.plus(Duration.ofDays(1))
    val nextSettings = schedule.nextWindowStart(now)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        InsightTile(
            Modifier.weight(1f),
            title = "Window apps",
            value = if (appsOpenNow) short(Duration.between(now, todayClose)) else short(Duration.between(now, nextOpen)),
            caption = if (appsOpenNow) "Open until ${CLOCK.format(windowClose)}" else "Open at ${CLOCK.format(windowOpen)}",
            progress = if (appsOpenNow) fraction(todayOpen, todayClose, now) else fraction(nextOpen.minus(Duration.ofDays(1)).plus(Duration.between(todayOpen, todayClose)), nextOpen, now),
            color = if (appsOpenNow) Palette.Ok else MaterialTheme.colorScheme.primary,
        ) { navigate(Screen.LISTS) }
        InsightTile(
            Modifier.weight(1f),
            title = "Sunday review",
            value = short(Duration.between(now, nextSettings)),
            caption = DAY_TIME.format(nextSettings.atZone(zone)) + if (waiting.isNotEmpty()) ", ${waiting.size} waiting" else "",
            progress = fraction(nextSettings.minus(Duration.ofDays(7)), nextSettings, now),
            color = MaterialTheme.colorScheme.secondary,
        ) { navigate(Screen.WINDOW) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        InsightTile(
            Modifier.weight(1f),
            title = "Closed now",
            value = closedNow?.toString() ?: "...",
            caption = "Apps closed right now",
            icon = Icons.Filled.Block,
            color = Palette.Closed,
        ) { navigate(Screen.LISTS) }
        InsightTile(
            Modifier.weight(1f),
            title = "Screen filter",
            value = filterWeek?.first?.toString() ?: "...",
            caption = filterWeek?.let { "Warnings this week, ${it.second} closed" } ?: "Warnings this week",
            icon = Icons.Filled.Shield,
            color = Palette.Attention,
        ) { navigate(Screen.REPORT) }
    }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconCircle(Icons.Filled.Schedule, MaterialTheme.colorScheme.secondaryContainer, size = 40.dp, tint = MaterialTheme.colorScheme.secondary)
            Column(Modifier.weight(1f)) {
                Text(countdown.uninstallWindowLine, style = MaterialTheme.typography.bodyMedium)
                Text(countdown.appsCountLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    Text(
        when {
            BuildConfig.PREVIEW -> "Preview build: nothing is applied to this phone."
            BuildConfig.TEST_BUILD -> "Test build: can be removed over USB."
            else -> "Sealed build."
        } + if (owner) " Device owner: yes." else " Device owner: no.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Start and end of the current [phase]: walks the schedule's transitions from a day and a half back. */
private fun phaseSpan(schedule: app.lockmein.core.Schedule, phase: Phase, now: Instant): Pair<Instant, Instant> {
    var t = now.minus(Duration.ofHours(36))
    var prev = schedule.phaseAt(t)
    var start = t
    repeat(60) {
        val n = schedule.nextTransition(t)
        val p = schedule.phaseAt(n)
        if (p != prev) {
            if (!n.isAfter(now)) start = n else if (prev == phase) return start to n
        }
        prev = p
        t = n
    }
    return start to now.plus(Duration.ofHours(1))
}

private fun fraction(start: Instant, end: Instant, now: Instant): Float {
    val total = Duration.between(start, end).toMillis().coerceAtLeast(1)
    return (Duration.between(start, now).toMillis().toFloat() / total).coerceIn(0f, 1f)
}

/** "3h 12m", "45m", "2d 4h". */
private fun short(d: Duration): String {
    val m = d.toMinutes().coerceAtLeast(0)
    return when {
        m >= 48 * 60 -> "${m / 1440}d ${m % 1440 / 60}h"
        m >= 60 -> "${m / 60}h ${m % 60}m"
        else -> "${m}m"
    }
}

@Composable
private fun Ring(progress: Float, size: Dp, stroke: Dp, color: Color, content: @Composable ColumnScope.() -> Unit) {
    val track = color.copy(alpha = 0.18f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val arc = androidx.compose.ui.geometry.Size(this.size.width - s, this.size.height - s)
            val topLeft = androidx.compose.ui.geometry.Offset(s / 2, s / 2)
            drawArc(track, -90f, 360f, false, topLeft, arc, style = Stroke(s))
            drawArc(color, -90f, 360f * progress, false, topLeft, arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, badge: Int = 0, onClick: () -> Unit) {
    Column(
        Modifier.width(78.dp).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            IconCircle(icon, MaterialTheme.colorScheme.primaryContainer, size = 52.dp, tint = MaterialTheme.colorScheme.primary)
            if (badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(20.dp).background(Palette.Attention, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("$badge", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun InsightTile(
    modifier: Modifier,
    title: String,
    value: String,
    caption: String,
    progress: Float? = null,
    icon: ImageVector? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    AppCard(modifier = modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (progress != null) {
                Ring(progress, 40.dp, 5.dp, color) {}
            } else if (icon != null) {
                IconCircle(icon, color.copy(alpha = 0.18f), size = 40.dp, tint = color)
            }
            Column {
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
