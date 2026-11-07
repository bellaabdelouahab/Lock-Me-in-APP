package app.lockmein.ui

import android.content.Intent
import androidx.compose.foundation.clickable
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
    val browserLabel = current.allowedBrowser?.let { inventory.label(it) } ?: "No browser stays open"

    Page("Settings", onBack) {
        OutcomeText(outcome)

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
                title = "Approved browser",
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
                value = "$windowDayName ${current.windowStart} to ${current.windowEnd}",
                onClick = { activeDialog = SettingsDialog.WEEKLY_WINDOW },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsItem(
                icon = Icons.Filled.LocationOn,
                title = "Location and fajr",
                value = "${current.latitude}, ${current.longitude} (${current.sunAngleDegrees} deg)",
                onClick = { activeDialog = SettingsDialog.LOCATION },
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
                            if (res is Outcome.Done) navigate(Screen.SETUP)
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
                Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
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
            AppCard {
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
                            shape = CardShape,
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

            AlertDialog(
                onDismissRequest = { activeDialog = SettingsDialog.NONE },
                shape = CardShape,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                title = { Text("Approved browser", style = MaterialTheme.typography.titleMedium) },
                text = {
                    LazyColumn {
                        items(browserChoices) { b ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedBrowser = b.packageName }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RadioButton(selected = selectedBrowser == b.packageName, onClick = { selectedBrowser = b.packageName })
                                AppIcon(b.packageName, size = 32.dp)
                                Text(b.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedBrowser = null }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RadioButton(selected = selectedBrowser == null, onClick = { selectedBrowser = null })
                                Text("No browser stays open", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        activeDialog = SettingsDialog.NONE
                        save(current.copy(allowedBrowser = selectedBrowser))
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
                            shape = CardShape,
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
                title = { Text("Weekly open window", style = MaterialTheme.typography.titleMedium) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Day of week:", style = MaterialTheme.typography.bodySmall)
                        val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            days.forEachIndexed { idx, d ->
                                val dayVal = idx + 1
                                if (dayNum == dayVal) MenuButtonSmall(d) {}
                                else SecondaryButtonSmall(d) { dayNum = dayVal }
                            }
                        }
                        OutlinedTextField(
                            value = startText,
                            onValueChange = { startText = it },
                            label = { Text("Window opens (HH:mm)") },
                            singleLine = true,
                            shape = CardShape,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = endText,
                            onValueChange = { endText = it },
                            label = { Text("Window closes (HH:mm)") },
                            singleLine = true,
                            shape = CardShape,
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
            var latText by remember { mutableStateOf(current.latitude.toString()) }
            var lonText by remember { mutableStateOf(current.longitude.toString()) }
            var angleText by remember { mutableStateOf(current.sunAngleDegrees.toString()) }
            var offsetText by remember { mutableStateOf(current.fajrOffsetMinutes.toString()) }

            AlertDialog(
                onDismissRequest = { activeDialog = SettingsDialog.NONE },
                shape = CardShape,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                title = { Text("Location and fajr", style = MaterialTheme.typography.titleMedium) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Fajr is calculated from your coordinates.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = latText, onValueChange = { latText = it }, label = { Text("Latitude") }, singleLine = true, shape = CardShape, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = lonText, onValueChange = { lonText = it }, label = { Text("Longitude") }, singleLine = true, shape = CardShape, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = angleText, onValueChange = { angleText = it }, label = { Text("Sun angle (deg)") }, singleLine = true, shape = CardShape, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = offsetText, onValueChange = { offsetText = it }, label = { Text("Fajr offset (minutes)") }, singleLine = true, shape = CardShape, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val lat = latText.toDoubleOrNull() ?: current.latitude
                        val lon = lonText.toDoubleOrNull() ?: current.longitude
                        val ang = angleText.toDoubleOrNull() ?: current.sunAngleDegrees
                        val off = offsetText.toIntOrNull() ?: current.fajrOffsetMinutes
                        activeDialog = SettingsDialog.NONE
                        save(current.copy(latitude = lat, longitude = lon, sunAngleDegrees = ang, fajrOffsetMinutes = off))
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
                            shape = CardShape,
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
        Icon(icon, contentDescription = null, tint = titleColor ?: MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor ?: MaterialTheme.colorScheme.onSurface)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}
