package app.lockmein.ui

import android.app.admin.DevicePolicyManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lockmein.BuildConfig
import app.lockmein.ConfigStore
import app.lockmein.DeviceInventory
import app.lockmein.Health
import app.lockmein.HealthItem
import app.lockmein.LockState
import app.lockmein.Release
import app.lockmein.Rules
import app.lockmein.Updater
import app.lockmein.core.LockConfig
import app.lockmein.core.Phase
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/** Closed apps listed on Home: three keeps the whole page on one screen; the rest are a tap away. */
private const val SHOWN_ON_HOME = 3

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
    Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("No fight at night", style = MaterialTheme.typography.headlineMedium)
        Text(
            "You choose the rules while you are calm. At night the phone keeps them, and opening anything up waits a week.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Heading("How it works")
    AppCard {
        WelcomeStep(1, "Set it up once", "Seven steps. Each one is verified on the phone before you go on.")
        RowDivider(inset = 44.dp)
        WelcomeStep(2, "The night closes the phone", "From your night start until fajr only your chosen apps run.")
        RowDivider(inset = 44.dp)
        WelcomeStep(3, "Opening up waits a week", "Closing more is instant. Opening is a request carried out in the weekly window.")
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
private fun WelcomeStep(number: Int, title: String, text: String) {
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        NumberDisc(number)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Home holds only what must be seen in one second, in this order:
 * 1. Anything that needs a decision (setup not finished, an update ready), and only when there is one.
 * 2. Am I protected, which phase is it, and when it changes (the ring).
 * 3. Which apps are closed now, and why.
 * 4. The three things done most: Apps, Requests (with what waits), Friend's code.
 * Everything else lives on its own page: the windows' times on Requests and Apps, the filter's week on Report, the build on About.
 * It fits one phone screen without scrolling.
 */
@Composable
private fun DashboardView(config: LockConfig, state: LockState, owner: Boolean) {
    val context = LocalContext.current
    val navigate = LocalNavigate.current
    val store = remember { ConfigStore(context) }
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val now = app.lockmein.TrustedClock(context).now()
    val schedule = remember(config) { Rules.schedule(config, zone) }
    val phase = schedule.phaseAt(now)
    val inventory = remember { DeviceInventory(context) }
    val waiting = state.requests

    // Check for updates
    var latestRelease by remember { mutableStateOf<Release?>(null) }
    LaunchedEffect(Unit) {
        latestRelease = withContext(Dispatchers.IO) { Updater.latest() }
    }
    val hasUpdate = Updater.isNewer(latestRelease)
    var bannerDismissed by remember { mutableStateOf(false) }

    // 1. A decision to make: setup not finished, or an update ready.
    val setupIncomplete = !store.setupComplete
    if (!bannerDismissed && (setupIncomplete || hasUpdate)) {
        Callout(
            kind = if (setupIncomplete) StatusKind.ATTENTION else null,
            icon = if (setupIncomplete) Icons.Filled.WarningAmber else Icons.Filled.SystemUpdate,
            title = if (setupIncomplete) (if (store.redoing) "Setup reopened" else "Setup not complete")
            else "Update ready: version ${latestRelease?.name}",
            text = if (setupIncomplete) "Every change works at once until the setup passes every check and completes."
            else latestRelease?.notes?.ifBlank { "A newer version is ready to install." } ?: "A newer version is ready to install.",
            onDismiss = { bannerDismissed = true },
        ) {
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

    // 2. The phase as a ring that fills as it runs out, the time left in the middle, and whether Android confirms the rules.
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

    TintedCard(Modifier, Color.Unspecified, quiet = true, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Ring(
                progress = fraction(spanStart, spanEnd, now),
                size = 104.dp,
                stroke = 7.dp,
                color = MaterialTheme.colorScheme.primary,
            ) {
                Text(short(Duration.between(now, spanEnd)), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text("until $nextPhaseName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(phaseIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    Text(phaseName, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                }
                Text(
                    "${nextPhaseName.replaceFirstChar { it.uppercase() }} at ${CLOCK.format(spanEnd.atZone(zone))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                // The badge never claims more than Android confirms: open setup or a failing check is not "Protected".
                when {
                    healthItems == null -> StatusBadge(kind = StatusKind.ATTENTION, label = "Checking")
                    failingHealth > 0 -> StatusBadge(kind = StatusKind.ATTENTION, label = "Not fully protected")
                    !store.setupComplete -> StatusBadge(kind = StatusKind.ATTENTION, label = "Setup open")
                    else -> StatusBadge(kind = StatusKind.OK, label = if (phase == Phase.OPEN_WINDOW) "Window open" else "Protected")
                }
            }
        }
        RowDivider()
        Row(
            modifier = Modifier.fillMaxWidth().clip(TileShape).clickable { navigate(Screen.HEALTH) }.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                healthItems == null -> {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Checking protection...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                failingHealth == 0 -> {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Palette.Ok, modifier = Modifier.size(20.dp))
                    Text("Protection verified", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                else -> {
                    Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Palette.Attention, modifier = Modifier.size(20.dp))
                    Text(
                        if (failingHealth == 1) "1 item needs attention" else "$failingHealth items need attention",
                        style = MaterialTheme.typography.bodyMedium, color = Palette.Attention, fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }

    // 3. The three things done most.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QuickAction(Modifier.weight(1f), Icons.Filled.Apps, "App rules") { navigate(Screen.LISTS) }
        QuickAction(Modifier.weight(1f), Icons.Filled.EventAvailable, "Requests", badge = waiting.size) { navigate(Screen.WINDOW) }
        QuickAction(Modifier.weight(1f), Icons.Filled.Key, "Friend's code") { navigate(Screen.EMERGENCY) }
    }

    // 4. Which apps are closed right now, and why (read back from Android, as on Apps, Now).
    val closed by produceState<List<app.lockmein.ClosedReasons.Reason>?>(null, config) {
        value = withContext(Dispatchers.IO) { runCatching { app.lockmein.ClosedReasons.all(context, config, now) }.getOrNull() }
    }
    val closedNow = closed?.count { it.closedNow }
    Heading(if (closedNow == null) "Closed now" else "Closed now ($closedNow)")
    TintedCard(Modifier, Color.Unspecified, quiet = true, padding = 12.dp) {
        val list = closed
        when {
            list == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Reading from Android...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            list.isEmpty() -> Text("Nothing is closed at this hour.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                val shown = list.sortedBy { it.closedNow }.take(SHOWN_ON_HOME)
                shown.forEachIndexed { index, app ->
                    AppRow(
                        packageName = app.packageName,
                        title = inventory.label(app.packageName),
                        subtitle = if (!app.closedNow) "Should be closed but is open. Next pass closes it." else app.why,
                        statusColor = if (!app.closedNow) Palette.Closed else null,
                    ) {}
                    if (index < shown.lastIndex) RowDivider(inset = 52.dp)
                }
            }
        }
        RowDivider()
        Row(
            Modifier.fillMaxWidth().clip(TileShape).clickable { navigate(Screen.LISTS) }.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if ((closed?.size ?: 0) > SHOWN_ON_HOME) "See all ${closed!!.size} in App rules" else "Open App rules",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
    // The build kind and device owner line lives on About.
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

/** A tile for a most-used action: the icon in the accent (it can be tapped), the name under it, a count when something waits. */
@Composable
private fun QuickAction(modifier: Modifier, icon: ImageVector, label: String, badge: Int = 0, onClick: () -> Unit) {
    Column(
        modifier
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(top = 10.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(width = 52.dp, height = 28.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            if (badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(Palette.Attention),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$badge", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 4.dp))
    }
}
