package app.lockmein.ui

import android.app.admin.DevicePolicyManager
import androidx.compose.foundation.BorderStroke
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

    // Top Status Card: "Am I protected right now?"
    val nightEnd = fajr.fajr(local.toLocalDate())
    val countdown = remember(config, now) { CountdownFormatter.compute(schedule, config, now, zone) }
    val (phaseName, statusKind, _, phaseIcon) = when (phase) {
        Phase.NIGHT -> Quad(
            "Night",
            StatusKind.OK,
            "",
            Icons.Filled.NightsStay,
        )
        Phase.OPEN_WINDOW -> Quad(
            "Settings window",
            StatusKind.ATTENTION,
            "",
            Icons.Filled.EventAvailable,
        )
        Phase.DAY -> Quad(
            "Day",
            StatusKind.OK,
            "",
            Icons.Filled.WbSunny,
        )
    }

    val healthItems by produceState<List<HealthItem>?>(null, config) {
        value = withContext(Dispatchers.IO) { Health(context).check() }
    }
    val failingHealth = healthItems?.count { !it.ok } ?: 0

    AppCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(phaseIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text(phaseName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            // The badge never claims more than Android confirms: open setup or a failing check is not "Protected".
            when {
                healthItems == null -> StatusBadge(kind = StatusKind.ATTENTION, label = "Checking")
                failingHealth > 0 -> StatusBadge(kind = StatusKind.ATTENTION, label = "Not fully protected")
                !ConfigStore(context).setupComplete -> StatusBadge(kind = StatusKind.ATTENTION, label = "Setup open")
                else -> StatusBadge(kind = statusKind, label = if (phase == Phase.OPEN_WINDOW) "Window open" else "Protected")
            }
        }

        Text(
            countdown.bigText,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                countdown.settingsWindowLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                countdown.uninstallWindowLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                countdown.appsCountLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.inSession(now)) {
            val sessionRem = Duration.between(now, state.sessionUntil!!)
            Text(
                "Change session active (${CountdownFormatter.formatDurationHM(sessionRem)} left)",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Attention,
                fontWeight = FontWeight.SemiBold,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Health summary row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { navigate(Screen.HEALTH) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (healthItems == null) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Checking protection status...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (failingHealth == 0) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Palette.Ok, modifier = Modifier.size(18.dp))
                Text("Protection verified", style = MaterialTheme.typography.bodyMedium, color = Palette.Ok, fontWeight = FontWeight.Medium)
            } else {
                Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Palette.Attention, modifier = Modifier.size(18.dp))
                Text(
                    if (failingHealth == 1) "1 item needs attention" else "$failingHealth items need attention",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Attention,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }

    // "Next" Card: Upcoming phase change and weekly window
    AppCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text("Upcoming changes", style = MaterialTheme.typography.titleMedium)
        }
        val nextPhaseName = when (phase) {
            Phase.NIGHT -> "Day"
            Phase.DAY -> "Night"
            Phase.OPEN_WINDOW -> "Night"
        }
        Text(
            "$nextPhaseName starts ${DAY_TIME.format(next.atZone(zone))}.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val windowDay = java.time.DayOfWeek.of(config.windowDay).getDisplayName(TextStyle.FULL, Locale.getDefault())
        val nextWindow = schedule.nextWindowStart(now)
        Text(
            "Weekly window: $windowDay ${config.windowStart} to ${config.windowEnd} (next: ${DAY_TIME.format(nextWindow.atZone(zone))}).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Heading("Shortcuts")

    // Shortcut 1: Closed right now
    AppCard(onClick = { navigate(Screen.LISTS) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconCircle(Icons.Filled.Apps, MaterialTheme.colorScheme.primaryContainer, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text("Closed right now", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${config.nightClosed.size} night apps, ${config.alwaysBlocked.size} always blocked",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }

    // Shortcut 2: Waiting requests
    AppCard(onClick = { navigate(Screen.WINDOW) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconCircle(Icons.Filled.EventAvailable, MaterialTheme.colorScheme.secondaryContainer, tint = MaterialTheme.colorScheme.secondary)
            Column(Modifier.weight(1f)) {
                Text("Waiting requests", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (waiting.isEmpty()) "Nothing waiting" else "${waiting.size} request" + (if (waiting.size > 1) "s" else "") + " waiting for the window",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (waiting.isNotEmpty()) {
                StatusBadge(kind = StatusKind.ATTENTION, label = "${waiting.size}")
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }

    // Shortcut 3: Holder code
    AppCard(onClick = { navigate(Screen.EMERGENCY) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconCircle(Icons.Filled.Key, Palette.ClosedContainer, tint = Palette.Closed)
            Column(Modifier.weight(1f)) {
                Text("Holder code: change session", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Ask Mom or your brother for today's code",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
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

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
