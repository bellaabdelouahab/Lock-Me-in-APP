package app.lockmein.ui

import android.app.admin.DevicePolicyManager
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Sos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import app.lockmein.Health
import app.lockmein.HealthItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** The time left, short enough to print huge: 2d 4h, 14h 57m, 38m. */
private fun shortLength(d: Duration): String {
    val minutes = d.toMinutes().coerceAtLeast(0)
    return when {
        minutes >= 24 * 60 -> "${minutes / (24 * 60)}d ${minutes % (24 * 60) / 60}h"
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes}m"
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("NO FIGHT", style = MaterialTheme.typography.displayMedium)
        Text("AT NIGHT.", style = MaterialTheme.typography.displayMedium, color = Palette.Signal)
    }
    Text("You choose the rules while you are calm. At night the phone keeps them, and opening anything up has to wait a week.", style = MaterialTheme.typography.bodyLarge)
    Rule()
    Step(1, "Set it up once", "Seven steps. Each one is checked on the phone before you can go on.")
    Step(2, "The night closes the phone", "From your cut-off until fajr only your night apps run.")
    Step(3, "Opening up waits a week", "Closing more is instant. Opening is a request carried out in the weekly window.")
    CheckRow("This phone", if (owner) CheckState.PASS else CheckState.CONFIRM, if (owner) "Lock Me In is the device owner." else "Not the device owner yet: step 1 walks you through it.")
    MenuButton("Start the setup", onClick = onSetup)
    UpdateCard(onlyIfNewer = true)
}

@Composable
private fun Step(number: Int, title: String, text: String) {
    Column {
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("0$number", style = MaterialTheme.typography.headlineMedium, color = Palette.Signal)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
        }
        Rule(thick = false)
    }
}

/** One cell of the shortcut grid: no box, split from its neighbour by a line. Name in capitals, a line, an optional count. */
@Composable
private fun RowScope.Tile(icon: ImageVector, color: Color, title: String, line: String, badge: Int = 0, onClick: () -> Unit) {
    Column(Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 16.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
            Spacer(Modifier.weight(1f))
            if (badge > 0) Box(Modifier.size(26.dp).clip(Dot).background(Palette.Signal), contentAlignment = Alignment.Center) {
                Text("$badge", color = Palette.Paper, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(title.uppercase(), style = MaterialTheme.typography.labelLarge)
        Text(line, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 2)
    }
}

@Composable
private fun TileRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) { content() }
    Rule(thick = false)
}

@Composable
private fun RowScope.Split() = Box(Modifier.fillMaxHeight().width(1.dp).background(Palette.Line))

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
        Phase.OPEN_WINDOW -> Five(next.minus(Duration.ofHours(1)), "Open window", "Requests that waited 7 days can be carried out now.", Palette.Signal, Icons.Filled.LockOpen)
        Phase.DAY -> Five(ZonedDateTime.of(local.toLocalDate(), fajr.fajr(local.toLocalDate()), zone).toInstant(), "Day", "Social apps run until ${config.nightStart}. YouTube stays blocked.", Palette.Day, Icons.Filled.WbSunny)
    }

    // The poster: the phase in huge capitals, the time left bigger still, a square bar.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(12.dp).clip(Dot).background(color))
            Text("NOW", style = MaterialTheme.typography.labelSmall)
        }
        Text(headline.uppercase(), style = MaterialTheme.typography.displaySmall, color = color)
        Text(shortLength(Duration.between(now, next)), style = MaterialTheme.typography.displayLarge, maxLines = 1)
        Text("LEFT, UNTIL ${DAY_TIME.format(next.atZone(zone)).uppercase()}", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
    }
    Box(Modifier.fillMaxWidth().height(10.dp).background(Palette.Line)) {
        Box(Modifier.fillMaxWidth(progressOf(phaseStart, now, next)).height(10.dp).background(Palette.Ink))
    }
    Text(detail, style = MaterialTheme.typography.bodyLarge)
    unblocks.forEach { (pkg, until) ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppIcon(pkg, 28.dp)
            Text("${inventory.label(pkg)} is open until ${CLOCK.format(until.atZone(zone))}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }

    // Not what the app thinks, what Android says.
    val health by produceState<List<HealthItem>?>(null, config) { value = withContext(Dispatchers.IO) { Health(context).check() } }
    HealthBadge(health) { navigate(Screen.HEALTH) }
    UpdateCard(onlyIfNewer = true)

    Rule()
    TileRow {
        Tile(Icons.Filled.Sos, Palette.Locked, "Emergency", "Friend's code opens one app for an hour.") { navigate(Screen.EMERGENCY) }
        Split()
        Tile(Icons.Filled.EventAvailable, Palette.Ink, "Requests", if (waiting.isEmpty()) "Nothing waiting." else "${waiting.size} waiting for the window.", waiting.size) { navigate(Screen.WINDOW) }
    }
    TileRow {
        Tile(Icons.Filled.Apps, Palette.Ink, "Apps", "${config.nightAllowlist.size} at night, ${config.alwaysBlocked.size} always blocked.") { navigate(Screen.LISTS) }
        Split()
        Tile(Icons.Filled.Language, Palette.Ink, "Browser", config.allowedBrowser?.let { inventory.label(it) } ?: "None stays open.") { navigate(Screen.BROWSERS) }
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
        color = Palette.Ink,
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
        color = Palette.Signal,
        onClick = { navigate(Screen.WINDOW) },
    )

    SectionCard("Private DNS", config.dnsHost, leading = Icons.Filled.Shield)
    if (state.quarantined.isNotEmpty()) SectionCard("New apps held back", "${state.quarantined.size} app(s) wait for your review.", state.quarantined.toList(), Icons.Filled.Apps) { navigate(Screen.WINDOW) }

    Spacer(Modifier.height(4.dp))
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
