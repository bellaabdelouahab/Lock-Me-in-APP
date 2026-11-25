package app.lockmein.ui

import androidx.compose.material.icons.filled.Lock
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lockmein.AppInfo
import app.lockmein.BuildConfig
import app.lockmein.ChangeManager
import app.lockmein.ConfigStore
import app.lockmein.DeviceInventory
import app.lockmein.Outcome
import app.lockmein.Recents
import app.lockmein.Rules
import app.lockmein.core.ConfigCodec
import app.lockmein.core.DnsHost
import app.lockmein.core.KnownBrowsers
import app.lockmein.core.LockConfig
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

private enum class SettingsDialog {
    NONE,
    DNS,
    BROWSER,
    NIGHT_START,
    WEEKLY_WINDOW,
    LOCATION,
    UPDATES,
    IMPORT,
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val navigate = LocalNavigate.current
    val store = remember { ConfigStore(context) }
    val manager = remember { ChangeManager(context) }
    val inventory = remember { DeviceInventory(context) }
    val scope = rememberCoroutineScope()

    var version by remember { mutableIntStateOf(0) }
    var draft by remember(version) { mutableStateOf(store.load()) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var activeDialog by remember { mutableStateOf(SettingsDialog.NONE) }
    var hideRecents by remember { mutableStateOf(Recents.hidden(context)) }

    val current = draft ?: return Page("Settings", onBack) { Note("Finish the setup first.") }

    fun save(updated: LockConfig) {
        scope.background({ manager.saveSettings(updated) }) {
            outcome = it
            version++
        }
    }

    val windowDayName = DayOfWeek.of(current.windowDay).getDisplayName(TextStyle.FULL, Locale.getDefault())
    val browserLabel = if (current.openSecuredBrowsers) "Every secured browser" else current.allowedBrowser?.let { inventory.label(it) } ?: "No browser stays open"

    Page("Settings", onBack) {
        OutcomeText(outcome)

        if (manager.locked) {
            AppCard(accent = Palette.Attention) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlowCircle(Icons.Filled.Lock, Palette.Attention, size = 36.dp)
                    Text(
                        "Settings are locked. Any change requested here will wait for Sunday review (20:00 to 21:00).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Section: Protection
        Heading("Protection")
        AppCard {
            SettingsItem(
                icon = Icons.Filled.Dns,
                title = "Private DNS host",
                value = current.dnsHost.ifBlank { "Not set" },
                onClick = { activeDialog = SettingsDialog.DNS },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsItem(
                icon = Icons.Filled.Language,
                title = "Browsers",
                value = browserLabel,
                onClick = { activeDialog = SettingsDialog.BROWSER },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsItem(
                icon = Icons.Filled.NightsStay,
                title = "Night starts",
                value = current.nightStart,
                onClick = { activeDialog = SettingsDialog.NIGHT_START },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsItem(
                icon = Icons.Filled.Schedule,
                title = "Weekly settings window",
                tint = Palette.Info,
                value = "$windowDayName ${current.windowStart} to ${current.windowEnd}",
                onClick = { activeDialog = SettingsDialog.WEEKLY_WINDOW },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsItem(
                icon = Icons.Filled.LocationOn,
                title = "Location and fajr",
                tint = Palette.Info,
                value = (app.lockmein.core.Places.nameOf(current.latitude, current.longitude) ?: "${current.latitude}, ${current.longitude}") + " (${current.sunAngleDegrees} deg)",
                onClick = { activeDialog = SettingsDialog.LOCATION },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val isFilterOn = app.lockmein.FilterService.isEnabled(context)
            val lastHitMillis = remember(version) { app.lockmein.LockState(context).lastFilterHitMillis }
            val filterStatus = if (!isFilterOn) {
                "Off (tap to open Accessibility settings)"
            } else if (lastHitMillis > 0) {
                val hitTime = java.time.Instant.ofEpochMilli(lastHitMillis)
                val formatter = java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(java.time.ZoneId.systemDefault())
                "On (last hit ${formatter.format(hitTime)})"
            } else {
                "On (no detections)"
            }
            SettingsItem(
                icon = Icons.Filled.Shield,
                title = "Screen filter",
                value = filterStatus,
                tint = if (isFilterOn) Palette.Ok else Palette.Attention,
                onClick = {
                    val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
            )
        }

        // Section: Setup
        Heading("Setup")
        AppCard {
            SettingsItem(
                icon = Icons.Filled.Refresh,
                title = "Redo the setup",
                value = if (store.redoing) "Setup is currently reopened" else "Go through the steps again",
                onClick = {
                    if (store.redoing || !store.setupComplete) {
                        navigate(Screen.SETUP)
                    } else {
                        scope.background({ manager.startRedo() }) { res ->
                            outcome = res
                            if (store.redoing) navigate(Screen.SETUP)
                        }
                    }
                },
            )
        }

        // Section: App
        Heading("App")
        AppCard {
            SettingsItem(
                icon = Icons.Filled.SystemUpdate,
                title = "App updates",
                value = "Version ${BuildConfig.VERSION_NAME}",
                onClick = { activeDialog = SettingsDialog.UPDATES },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlowCircle(Icons.Filled.VisibilityOff, MaterialTheme.colorScheme.primary, size = 36.dp)
                Column(Modifier.weight(1f)) {
                    Text("Hide from recent apps", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Keeps Lock Me In out of the app switcher so it cannot be swiped away.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = hideRecents,
                    onCheckedChange = { want ->
                        if (Recents.setHidden(context, want)) {
                            hideRecents = want
                            (context as? android.app.Activity)?.let(Recents::apply)
                        } else {
                            outcome = Outcome.Refused("Hiding from recent apps is enforced while protection is active.")
                        }
                    },
                )
            }
        }

        // Section: Share
        Heading("Share")
        AppCard {
            SettingsItem(
                icon = Icons.Filled.FileUpload,
                title = "Export settings",
                value = "Share your rules as plain text (no secrets)",
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ConfigCodec.encode(current))
                    context.startActivity(Intent.createChooser(send, "Share settings").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsItem(
                icon = Icons.Filled.FileDownload,
                title = "Import settings",
                value = "Load rules from shared text",
                onClick = { activeDialog = SettingsDialog.IMPORT },
            )
        }

        // Section: Danger zone (only visible during monthly uninstall window: 1st of month 20:00 to 21:00)
        val schedule = Rules.schedule(current)
        val now = app.lockmein.TrustedClock(context).now()
        if (schedule.inUninstallWindow(now)) {
            Heading("Danger zone")
            AppCard(accent = Palette.Closed) {
                SettingsItem(
                    icon = Icons.Filled.Warning,
                    title = "Remove Lock Me In",
                    value = "Monthly uninstall window open until 21:00",
                    titleColor = Palette.Closed,
                    onClick = { navigate(Screen.REMOVE) },
                )
            }
        }
    }

    // Sub-dialogs
    when (activeDialog) {
        SettingsDialog.DNS -> {
            var hostText by remember { mutableStateOf(current.dnsHost) }
            AlertDialog(
                onDismissRequest = { activeDialog = SettingsDialog.NONE },
                shape = CardShape,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                title = { Text("Private DNS host", style = MaterialTheme.typography.titleMedium) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hostText,
                            onValueChange = { hostText = it },
                            label = { Text("DNS Host") },
                            placeholder = { Text("abc123.dns.nextdns.io") },
                            singleLine = true,
                            shape = FieldShape,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Any host supporting DNS-over-TLS works. In NextDNS, copy the host under Endpoints.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val norm = DnsHost.normalise(hostText) ?: hostText.trim()
                            activeDialog = SettingsDialog.NONE
                            save(current.copy(dnsHost = norm))
                        },
                        enabled = hostText.isNotBlank(),
                    ) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = SettingsDialog.NONE }) { Text("Cancel") }
                },
            )
        }
        SettingsDialog.BROWSER -> {
            val snapshot = remember { inventory.read() }
            val browserChoices = remember {
                val found = (snapshot.browsers + KnownBrowsers.ALL).filter { it in snapshot.installed }.distinct()
                found.map { AppInfo(it, inventory.label(it)) }
            }
            var selectedBrowser by remember { mutableStateOf(current.allowedBrowser) }
            var allSecured by remember { mutableStateOf(current.openSecuredBrowsers) }

            AlertDialog(
                onDismissRequest = { activeDialog = SettingsDialog.NONE },
                shape = CardShape,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                title = { Text("Browsers", style = MaterialTheme.typography.titleMedium) },
                text = {
                    LazyColumn {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { allSecured = true }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RadioButton(selected = allSecured, onClick = { allSecured = true })
                                Column(Modifier.weight(1f)) {
                                    Text("Every secured browser", style = MaterialTheme.typography.bodyMedium)
                                    Text("Chrome, Edge, Brave, Firefox, Samsung, Mi Browser, all watched. Tor, Opera and VPN browsers stay closed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text("Or only one:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        }
                        items(browserChoices) { b ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedBrowser = b.packageName; allSecured = false }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RadioButton(selected = !allSecured && selectedBrowser == b.packageName, onClick = { selectedBrowser = b.packageName; allSecured = false })
                                AppIcon(b.packageName, size = 32.dp)
                                Text(b.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedBrowser = null; allSecured = false }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RadioButton(selected = !allSecured && selectedBrowser == null, onClick = { selectedBrowser = null; allSecured = false })
                                Text("No browser stays open", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        activeDialog = SettingsDialog.NONE
                        // "Every secured browser" keeps the one chosen before as well, so turning it off later returns to it.
                        save(current.copy(allowedBrowser = selectedBrowser, openSecuredBrowsers = allSecured))
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = SettingsDialog.NONE }) { Text("Cancel") }
                },
            )
        }
        SettingsDialog.NIGHT_START -> {
            var timeText by remember { mutableStateOf(current.nightStart) }
            AlertDialog(
                onDismissRequest = { activeDialog = SettingsDialog.NONE },
                shape = CardShape,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                title = { Text("Night starts", style = MaterialTheme.typography.titleMedium) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = timeText,
                            onValueChange = { timeText = it },
                            label = { Text("Time (HH:mm)") },
                            placeholder = { Text("22:00") },
                            singleLine = true,
                            shape = FieldShape,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("From this time until fajr, night apps are closed.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            activeDialog = SettingsDialog.NONE
                            save(current.copy(nightStart = timeText.trim()))
                        },
                        enabled = timeText.isNotBlank(),
                    ) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = SettingsDialog.NONE }) { Text("Cancel") }
                },
            )
        }
        SettingsDialog.WEEKLY_WINDOW -> {
            var dayNum by remember { mutableIntStateOf(current.windowDay) }
            var startText by remember { mutableStateOf(current.windowStart) }
            var endText by remember { mutableStateOf(current.windowEnd) }
            AlertDialog(
                onDismissRequest = { activeDialog = SettingsDialog.NONE },
                shape = CardShape,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                title = { Text("Weekly settings window", style = MaterialTheme.typography.titleMedium) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Day of week:", style = MaterialTheme.typography.bodySmall)
                        // Seven buttons share a narrow dialog: one letter each, so the label always fits; the full name is said below.
                        val days = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            days.forEachIndexed { idx, d ->
                                val dayVal = idx + 1
                                val picked = dayNum == dayVal
                                androidx.compose.foundation.layout.Box(
                                    Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .clickable { dayNum = dayVal },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        d.take(1),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (picked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                        Text(days.getOrNull(dayNum - 1) ?: "", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = startText,
                            onValueChange = { startText = it },
                            label = { Text("Window opens (HH:mm)") },
                            singleLine = true,
                            shape = FieldShape,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = endText,
                            onValueChange = { endText = it },
                            label = { Text("Window closes (HH:mm)") },
                            singleLine = true,
                            shape = FieldShape,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        activeDialog = SettingsDialog.NONE
                        save(current.copy(windowDay = dayNum, windowStart = startText.trim(), windowEnd = endText.trim()))
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = SettingsDialog.NONE }) { Text("Cancel") }
                },
            )
        }
        SettingsDialog.LOCATION -> {
            var place by remember { mutableStateOf(current) }
            AlertDialog(
                onDismissRequest = { activeDialog = SettingsDialog.NONE },
                shape = CardShape,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                title = { Text("Location and fajr", style = MaterialTheme.typography.titleMedium) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        LocationFields(place) { place = it }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        activeDialog = SettingsDialog.NONE
                        save(current.copy(latitude = place.latitude, longitude = place.longitude, sunAngleDegrees = place.sunAngleDegrees, fajrOffsetMinutes = place.fajrOffsetMinutes))
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = SettingsDialog.NONE }) { Text("Cancel") }
                },
            )
        }
        SettingsDialog.UPDATES -> {
            AlertDialog(
                onDismissRequest = { activeDialog = SettingsDialog.NONE },
                shape = CardShape,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                title = { Text("App updates", style = MaterialTheme.typography.titleMedium) },
                text = {
                    UpdateCard()
                },
                confirmButton = {
                    TextButton(onClick = { activeDialog = SettingsDialog.NONE }) { Text("Close") }
                },
            )
        }
        SettingsDialog.IMPORT -> {
            var importContent by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { activeDialog = SettingsDialog.NONE },
                shape = CardShape,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                title = { Text("Import settings", style = MaterialTheme.typography.titleMedium) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Paste settings text to import.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = importContent,
                            onValueChange = { importContent = it },
                            minLines = 3,
                            shape = FieldShape,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = importContent.isNotBlank(),
                        onClick = {
                            activeDialog = SettingsDialog.NONE
                            val res = runCatching { ConfigCodec.decode(importContent) }
                            res.onSuccess {
                                save(it)
                            }
                            res.onFailure {
                                outcome = Outcome.Refused(it.message ?: "Could not read those settings.")
                            }
                        },
                    ) { Text("Import") }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = SettingsDialog.NONE }) { Text("Cancel") }
                },
            )
        }
        SettingsDialog.NONE -> Unit
    }
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    titleColor: androidx.compose.ui.graphics.Color? = null,
    tint: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlowCircle(icon, titleColor ?: tint ?: MaterialTheme.colorScheme.primary, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor ?: MaterialTheme.colorScheme.onSurface)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}
