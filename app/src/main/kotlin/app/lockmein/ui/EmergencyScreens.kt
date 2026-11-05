package app.lockmein.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.lockmein.AppInfo
import app.lockmein.ChangeManager
import app.lockmein.ConfigStore
import app.lockmein.DeviceInventory
import app.lockmein.LockState
import app.lockmein.Outcome
import app.lockmein.core.AppList
import app.lockmein.core.ChangeRequest
import app.lockmein.core.KnownBrowsers
import app.lockmein.core.RequestKind

@Composable
fun CodeField(code: String, onChange: (String) -> Unit) = OutlinedTextField(
    value = code,
    onValueChange = { onChange(it.filter(Char::isDigit).take(6)) },
    label = { Text("6-digit code from your friend") },
    singleLine = true,
    shape = CardShape,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    modifier = Modifier.fillMaxWidth(),
)

private enum class FriendPower(val title: String, val description: String, val icon: ImageVector) {
    UNBLOCK_HOUR(
        "Open one app for an hour",
        "Temporarily opens any app for 60 minutes. It closes again by itself.",
        Icons.Filled.HourglassTop,
    ),
    APPLY_REQUEST(
        "Carry out a waiting request now",
        "Carries out a request immediately, skipping the wait and open window.",
        Icons.Filled.EventAvailable,
    ),
    STOP_CLOSING(
        "Stop closing an app now",
        "Takes an app off Closed at night or Always blocked immediately.",
        Icons.Filled.RemoveCircleOutline,
    ),
    RELEASE_APP(
        "Let a held-back new app through now",
        "Releases a quarantined app so you can use it immediately.",
        Icons.Filled.Apps,
    ),
    APPROVE_BROWSER(
        "Open a closed browser now",
        "Approves a closed browser so it stays open.",
        Icons.Filled.Language,
    ),
    REDO_SETUP(
        "Redo the setup now",
        "Reopens the setup steps immediately without waiting.",
        Icons.Filled.Refresh,
    ),
}

private sealed interface PendingAction {
    data class Confirm(val summary: String, val execute: () -> Unit) : PendingAction
    data object PickAppForHour : PendingAction
    data object PickRequest : PendingAction
    data object PickAppToStopClosing : PendingAction
    data object PickQuarantinedApp : PendingAction
    data object PickBrowser : PendingAction
}

/**
 * Friend's code screen:
 * A key for emergencies. One code = one action, then used up.
 */
@Composable
fun EmergencyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val navigate = LocalNavigate.current
    val manager = remember { ChangeManager(context) }
    val store = remember { ConfigStore(context) }
    val state = remember { LockState(context) }
    val inventory = remember { DeviceInventory(context) }
    val scope = rememberCoroutineScope()

    var code by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var version by remember { mutableIntStateOf(0) }

    val config = store.load()

    fun runAction(action: () -> Outcome) {
        scope.background(action) { res ->
            outcome = res
            if (res is Outcome.Done) {
                code = ""
                version++
            }
        }
    }

    Page("Friend's code", onBack) {
        AppCard {
            Text(
                "A key for emergencies. Each 6-digit code from your authenticator app works once and carries out one action immediately.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CodeField(code) { code = it }

        OutcomeText(outcome)

        Heading("What the code can do")
        Note("Enter the 6-digit code above, then pick the action you need.")

        FriendPower.entries.forEach { power ->
            AppCard(onClick = {
                if (code.length != 6) {
                    outcome = Outcome.Refused("Enter the 6-digit code first.")
                    return@AppCard
                }
                outcome = null
                pending = when (power) {
                    FriendPower.UNBLOCK_HOUR -> PendingAction.PickAppForHour
                    FriendPower.APPLY_REQUEST -> PendingAction.PickRequest
                    FriendPower.STOP_CLOSING -> PendingAction.PickAppToStopClosing
                    FriendPower.RELEASE_APP -> PendingAction.PickQuarantinedApp
                    FriendPower.APPROVE_BROWSER -> PendingAction.PickBrowser
                    FriendPower.REDO_SETUP -> PendingAction.Confirm(
                        "Reopen the setup steps now with code $code? This code will be used up.",
                    ) {
                        runAction {
                            manager.redoWithCode(code).also {
                                if (it is Outcome.Done) navigate(Screen.SETUP)
                            }
                        }
                    }
                }
            }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconCircle(power.icon, MaterialTheme.colorScheme.primaryContainer, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(power.title, style = MaterialTheme.typography.titleMedium)
                        Text(power.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        }
    }

    // Modal pickers and confirmation sheets
    when (val current = pending) {
        is PendingAction.Confirm -> {
            ConfirmDialog(
                title = "Use friend's code?",
                text = current.summary,
                confirmLabel = "Use code",
                onDismiss = { pending = null },
                onConfirm = {
                    pending = null
                    current.execute()
                },
            )
        }
        PendingAction.PickAppForHour -> {
            val apps = remember(version) {
                inventory.launchableApps().filter { it.packageName != context.packageName }
            }
            AppPickerDialog(
                title = "Choose app to open for 60 minutes",
                apps = apps,
                onDismiss = { pending = null },
            ) { app ->
                pending = PendingAction.Confirm(
                    "Open ${app.label} for 60 minutes with code $code? This code will be used up.",
                ) {
                    runAction { manager.redeemEmergency(code, app.packageName) }
                }
            }
        }
        PendingAction.PickRequest -> {
            val requests = remember(version) {
                manager.requests().filter { it.kind != RequestKind.REMOVE_PROTECTION }
            }
            if (requests.isEmpty()) {
                outcome = Outcome.Refused("No waiting requests to carry out.")
                pending = null
            } else {
                RequestPickerDialog(
                    title = "Choose request to carry out now",
                    requests = requests,
                    inventory = inventory,
                    onDismiss = { pending = null },
                ) { req ->
                    pending = PendingAction.Confirm(
                        "Carry out this request now with code $code? This code will be used up.",
                    ) {
                        runAction { manager.applyRequestWithCode(code, req.id) }
                    }
                }
            }
        }
        PendingAction.PickAppToStopClosing -> {
            val currentConfig = config ?: store.load()
            val candidates = remember(version, currentConfig) {
                val night = currentConfig?.nightClosed.orEmpty().map { AppList.NIGHT_CLOSED to it }
                val blocked = currentConfig?.alwaysBlocked.orEmpty().map { AppList.ALWAYS_BLOCKED to it }
                night + blocked
            }
            if (candidates.isEmpty()) {
                outcome = Outcome.Refused("No apps are currently on the closed or blocked lists.")
                pending = null
            } else {
                AlertDialog(
                    onDismissRequest = { pending = null },
                    shape = CardShape,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    title = { Text("Choose app to stop closing", style = MaterialTheme.typography.titleMedium) },
                    text = {
                        LazyColumn {
                            items(candidates) { (list, pkg) ->
                                val label = inventory.label(pkg)
                                val listName = if (list == AppList.NIGHT_CLOSED) "Closed at night" else "Always blocked"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            pending = PendingAction.Confirm(
                                                "Stop closing $label ($listName) with code $code? This code will be used up.",
                                            ) {
                                                runAction { manager.stopClosingWithCode(code, list, pkg) }
                                            }
                                        }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    AppIcon(pkg, size = 36.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(label, style = MaterialTheme.typography.titleMedium)
                                        Text(listName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { pending = null }) { Text("Cancel") }
                    },
                )
            }
        }
        PendingAction.PickQuarantinedApp -> {
            val held = remember(version) { state.quarantined.toList() }
            if (held.isEmpty()) {
                outcome = Outcome.Refused("No new apps are held back.")
                pending = null
            } else {
                val appInfos = held.map { AppInfo(it, inventory.label(it)) }
                AppPickerDialog(
                    title = "Choose held-back app to let through",
                    apps = appInfos,
                    onDismiss = { pending = null },
                ) { app ->
                    pending = PendingAction.Confirm(
                        "Let ${app.label} through now with code $code? This code will be used up.",
                    ) {
                        runAction { manager.releaseAppWithCode(code, app.packageName) }
                    }
                }
            }
        }
        PendingAction.PickBrowser -> {
            val snapshot = remember(version) { inventory.read() }
            val currentConfig = config ?: store.load()
            val closed = remember(version, currentConfig) {
                val allowed = setOfNotNull(currentConfig?.allowedBrowser) + state.approvedBrowsers
                (snapshot.browsers + KnownBrowsers.ALL).filter { it in snapshot.installed && it !in allowed }.sorted()
            }
            if (closed.isEmpty()) {
                outcome = Outcome.Refused("No browsers are closed.")
                pending = null
            } else {
                val browserInfos = closed.map { AppInfo(it, inventory.label(it)) }
                AppPickerDialog(
                    title = "Choose browser to approve now",
                    apps = browserInfos,
                    onDismiss = { pending = null },
                ) { browser ->
                    pending = PendingAction.Confirm(
                        "Approve ${browser.label} now with code $code? This code will be used up.",
                    ) {
                        runAction { manager.approveBrowserWithCode(code, browser.packageName) }
                    }
                }
            }
        }
        null -> Unit
    }
}

@Composable
private fun AppPickerDialog(
    title: String,
    apps: List<AppInfo>,
    onDismiss: () -> Unit,
    onPick: (AppInfo) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = CardShape,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            LazyColumn {
                items(apps) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(app) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AppIcon(app.packageName, size = 36.dp)
                        Text(app.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RequestPickerDialog(
    title: String,
    requests: List<ChangeRequest>,
    inventory: DeviceInventory,
    onDismiss: () -> Unit,
    onPick: (ChangeRequest) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = CardShape,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            LazyColumn {
                items(requests) { req ->
                    val description = when (req.kind) {
                        RequestKind.NIGHT_ADD -> "Old request: ${req.target}"
                        RequestKind.NIGHT_REMOVE -> "Stop closing at night: ${inventory.label(req.target)}"
                        RequestKind.BLOCKED_REMOVE -> "Unblock: ${inventory.label(req.target)}"
                        RequestKind.BROWSER -> "Open browser: ${inventory.label(req.target)}"
                        RequestKind.RELEASE_APP -> "Let new app through: ${inventory.label(req.target)}"
                        RequestKind.SETTINGS -> "Change settings"
                        RequestKind.REDO_SETUP -> "Redo setup"
                        RequestKind.REMOVE_PROTECTION -> "Remove protection"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(req) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Remove protection screen:
 * Needs the 7-day waited request, the weekly open window, and the fresh friend code.
 */
@Composable
fun RemoveScreen(onBack: () -> Unit, onRemoved: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { ChangeManager(context) }
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var working by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    val asked = remember(version) { manager.requests().any { it.kind == RequestKind.REMOVE_PROTECTION } }

    Page("Remove protection", onBack) {
        AppCard {
            Text("Danger zone", style = MaterialTheme.typography.titleMedium, color = Palette.Closed)
            Text(
                "This lifts every rule, ends device ownership, and allows uninstallation. Three requirements must all be met: you ask first and the request waits 7 days; then, during the weekly open window, you enter a fresh code from your friend.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutcomeText(outcome)

        if (!asked) {
            PrimaryButton(label = "Ask to remove protection") {
                scope.background({ manager.requestRemoval() }) {
                    outcome = it
                    version++
                }
            }
        } else {
            AppCard {
                StatusBadge(kind = StatusKind.ATTENTION, label = "Removal requested")
                Text(
                    "Removal is requested. When the 7-day wait is over and the weekly open window is active, enter your friend's code below.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            CodeField(code) { code = it }

            PrimaryButton(
                label = if (working) "Working..." else "Remove protection now",
                enabled = code.length == 6 && !working,
                onClick = {
                    working = true
                    scope.background({ manager.removeProtection(code) }) {
                        working = false
                        outcome = it
                        if (it is Outcome.Done) onRemoved()
                    }
                },
            )

            SecondaryButton("Cancel the removal request", {
                scope.background({ manager.cancelRequest("${RequestKind.REMOVE_PROTECTION}:") }) {
                    outcome = it
                    version++
                }
            })
        }
    }
}
