package app.lockmein.ui

import android.app.admin.DevicePolicyManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lockmein.BuildConfig
import app.lockmein.ConfigStore
import app.lockmein.DeviceInventory
import app.lockmein.LockState
import app.lockmein.PreviewDemo
import app.lockmein.Rules
import app.lockmein.core.AstronomicalFajr
import app.lockmein.core.LockConfig
import app.lockmein.core.Phase
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_TIME = DateTimeFormatter.ofPattern("EEE HH:mm")

private fun lengthOf(d: Duration): String {
    val minutes = d.toMinutes().coerceAtLeast(0)
    return when {
        minutes >= 24 * 60 -> "${minutes / (24 * 60)} d ${minutes % (24 * 60) / 60} h"
        minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
        else -> "$minutes min"
    }
}

/** Where we are between the start of the current phase and the next change, 0 to 1. */
private fun progressOf(start: Instant, now: Instant, end: Instant): Float {
    val total = Duration.between(start, end).toMillis().coerceAtLeast(1)
    return (Duration.between(start, now).toMillis().toFloat() / total).coerceIn(0f, 1f)
}

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
        "Lock Me In",
        actions = {
            if (active) {
                IconButton(onClick = { navigate(Screen.REPORT) }) { Icon(Icons.Filled.Assessment, contentDescription = "This week") }
                IconButton(onClick = { navigate(Screen.SETTINGS) }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            }
        },
    ) {
        if (!active || config == null) {
            Welcome(owner) { navigate(Screen.SETUP) }
        } else {
            Dashboard(config, state, owner)
        }
    }
}

@Composable
private fun Welcome(owner: Boolean, onSetup: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        IconCircle(Icons.Filled.Lock, Palette.Bar, 96.dp)
        Text("No fight at night", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "You choose the rules while you are calm. At night the phone keeps them, and opening anything up has to wait a week.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
    SectionCard("Set it up once", "Seven short steps, about ten minutes. The app guides you and moves on by itself.", leading = Icons.Filled.Shield)
    SectionCard("This phone", if (owner) "This app is the device owner." else "This app is not the device owner yet. Step 1 will help.", leading = Icons.Filled.Settings)
    MenuButton("Start the setup", onClick = onSetup)
}

@Composable
private fun Dashboard(config: LockConfig, state: LockState, owner: Boolean) {
    val context = LocalContext.current
    val navigate = LocalNavigate.current
    val zone = ZoneId.systemDefault()
    val now = Instant.now()
    val schedule = remember(config) { Rules.schedule(config, zone) }
    val fajr = remember(config) { AstronomicalFajr(config.fajrConfig(), zone) }
    val phase = schedule.phaseAt(now)
    val next = schedule.nextTransition(now)
    val local = ZonedDateTime.ofInstant(now, zone)
    val nightStart = java.time.LocalTime.parse(config.nightStart)
    val inventory = remember { DeviceInventory(context) }
    val waiting = state.requests
    val unblocks = state.unblocks(now)

    // Progress through the current phase.
    val (phaseStart, headline, detail, color, icon) = when (phase) {
        Phase.NIGHT -> {
            val start = if (local.toLocalTime() >= nightStart) local.with(nightStart) else local.minusDays(1).with(nightStart)
            Five(start.toInstant(), "Night", "Only ${config.nightAllowlist.size} app(s) and the phone's essentials until fajr.", Palette.Locked, Icons.Filled.NightsStay)
        }
        Phase.OPEN_WINDOW -> Five(next.minus(Duration.ofHours(1)), "Open window", "Requests that waited 7 days can be carried out now.", Palette.Open, Icons.Filled.LockOpen)
        Phase.DAY -> Five(ZonedDateTime.of(local.toLocalDate(), fajr.fajr(local.toLocalDate()), zone).toInstant(), "Day", "Social apps run until ${config.nightStart}. YouTube stays blocked.", Palette.Day, Icons.Filled.WbSunny)
    }

    // Status card.
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), elevation = CardDefaults.cardElevation(4.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconCircle(icon, color, 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
            LinearProgressIndicator(progress = { progressOf(phaseStart, now, next) }, modifier = Modifier.fillMaxWidth(), color = color, trackColor = color.copy(alpha = 0.25f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${lengthOf(Duration.between(now, next))} remaining", fontWeight = FontWeight.Medium)
                Text("until ${DAY_TIME.format(next.atZone(zone))}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            unblocks.forEach { (pkg, until) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppIcon(pkg, 28.dp)
                    Text("${inventory.label(pkg)} is open until ${CLOCK.format(until.atZone(zone))}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    // Emergency.
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Emergency", fontWeight = FontWeight.SemiBold)
            Text("Your friend's code opens one app for 60 minutes. It closes again by itself and changes nothing else.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            MenuButton("Emergency") { navigate(Screen.EMERGENCY) }
        }
    }

    Heading("Rules")
    val nightEnd = fajr.fajr(local.toLocalDate())
    RuleCard(
        title = "Night",
        icons = listOf(Icons.Filled.NightsStay, Icons.Filled.Block, Icons.Filled.Language),
        time = "${config.nightStart} – ${CLOCK.format(nightEnd)} (fajr)",
        days = (1..7).toSet(),
        progress = if (phase == Phase.NIGHT) progressOf(phaseStart, now, next) else 0f,
        caption = "Only the night list runs. Browsers and social apps are closed.",
        color = Palette.RuleDark,
        onClick = { navigate(Screen.LISTS) },
    )
    val windowDay = java.time.DayOfWeek.of(config.windowDay).getDisplayName(TextStyle.FULL, Locale.getDefault())
    val readyIn = waiting.minOfOrNull { Duration.between(now, it.requestedAt.plus(Duration.ofDays(config.loosenWaitDays.toLong()))) }
    RuleCard(
        title = "Open window",
        icons = listOf(Icons.Filled.EventAvailable, Icons.Filled.Schedule),
        time = "$windowDay ${config.windowStart} – ${config.windowEnd}",
        days = setOf(config.windowDay),
        progress = readyIn?.let { 1f - (it.toMillis().toFloat() / Duration.ofDays(config.loosenWaitDays.toLong()).toMillis()).coerceIn(0f, 1f) },
        caption = when {
            waiting.isEmpty() -> "Nothing waiting. Anything you ask to open up waits ${config.loosenWaitDays} days, then applies here."
            readyIn != null && !readyIn.isNegative -> "${waiting.size} request(s) waiting. The first is ready in ${lengthOf(readyIn)}."
            else -> "${waiting.size} request(s) ready to carry out in the window."
        },
        onClick = { navigate(Screen.WINDOW) },
    )

    SectionCard("Allowed at night", if (config.nightAllowlist.isEmpty()) "Nothing but the phone's essentials." else "${config.nightAllowlist.size} app(s)", config.nightAllowlist.toList(), Icons.Filled.NightsStay) { navigate(Screen.LISTS) }
    SectionCard("Blocked all the time", if (config.alwaysBlocked.isEmpty()) "None yet. YouTube is always blocked." else "${config.alwaysBlocked.size} app(s), and YouTube", config.alwaysBlocked.toList(), Icons.Filled.Block) { navigate(Screen.LISTS) }
    val browser = config.allowedBrowser
    SectionCard("Browser", if (browser == null) "No browser stays open." else "${inventory.label(browser)} stays open. All others are closed.", listOfNotNull(browser), Icons.Filled.Language) { navigate(Screen.BROWSERS) }
    SectionCard("Private DNS", config.dnsHost, leading = Icons.Filled.Shield)
    if (state.quarantined.isNotEmpty()) SectionCard("New apps held back", "${state.quarantined.size} app(s) wait for your review.", state.quarantined.toList(), Icons.Filled.Apps) { navigate(Screen.WINDOW) }

    Text(
        when {
            BuildConfig.PREVIEW -> "Preview build: nothing is applied to this phone."
            BuildConfig.TEST_BUILD -> "Test build: can be removed over USB."
            else -> "Sealed build."
        } + if (owner) "  Device owner: yes." else "  Device owner: no.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = { navigate(Screen.REMOVE) }, modifier = Modifier.fillMaxWidth()) { Text("Remove protection", color = MaterialTheme.colorScheme.error) }
    if (BuildConfig.PREVIEW) TextButton(onClick = { PreviewDemo.reset(context); navigate(Screen.SETUP) }, modifier = Modifier.fillMaxWidth()) { Text("Reset the preview and start over") }
}

private data class Five<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
