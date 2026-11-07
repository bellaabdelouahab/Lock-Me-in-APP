package app.lockmein.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.lockmein.ChangeManager
import app.lockmein.ConfigStore
import app.lockmein.DeviceInventory
import app.lockmein.LockState
import app.lockmein.Outcome
import app.lockmein.Rules
import app.lockmein.TrustedClock
import app.lockmein.core.AppList
import app.lockmein.core.FriendAction
import app.lockmein.core.FriendCode
import app.lockmein.core.KnownBrowsers
import java.time.Duration
import java.time.Instant

@Composable
fun CodeField(code: String, onChange: (String) -> Unit) = OutlinedTextField(
    value = code,
    onValueChange = { onChange(it.filter(Char::isDigit).take(6)) },
    label = { Text("6-digit friend's code") },
    singleLine = true,
    shape = CardShape,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    modifier = Modifier.fillMaxWidth(),
)

private enum class ActionPicker {
    NONE,
    OPEN_APP,
    APPROVE_REQUEST,
    STOP_BLOCKING,
    RELEASE_APP,
    APPROVE_BROWSER,
}

private data class StopBlockingItem(val list: AppList, val listName: String, val packageName: String)

/**
 * Friend's code screen:
 * Ask Mom or your brother for today's code.
 * Validates the 6-digit code once and opens a bottom sheet with 6 actions.
 * Exactly one action is applied with no second prompt. Closing sheet without choosing wastes the code.
 * 5 wrong codes lock out for 30 minutes.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var working by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }

    // Bottom sheet state
    var showActionSheet by remember { mutableStateOf(false) }
    var actionApplied by remember { mutableStateOf(false) }
    var currentPicker by remember { mutableStateOf(ActionPicker.NONE) }

    // Re-keying state
    var rekeyingStep by remember { mutableIntStateOf(0) } // 0 = idle, 1 = enter current code, 2 = show new QR
    var newSecret by remember { mutableStateOf<ByteArray?>(null) }
    var confirmNewCode by remember { mutableStateOf("") }
    var rekeyError by remember { mutableStateOf<String?>(null) }

    val now = TrustedClock(context).now()
    val isLockedOut = state.isLockedOut(now)
    val config = store.load()

    Page("Friend's code", onBack) {
        if (isLockedOut) {
            val remMinutes = Duration.between(now, state.lockoutUntil!!).toMinutes().coerceAtLeast(1)
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = Palette.Closed, modifier = Modifier.size(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Locked out after 5 wrong codes", style = MaterialTheme.typography.titleMedium, color = Palette.Closed)
                        Text(
                            "Too many wrong attempts. Try again in $remMinutes minutes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            AppCard {
                Text(
                    "Ask Mom or your brother for today's code.",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Entering today's code validates it and lets you pick one action to apply immediately without asking again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            CodeField(code) {
                code = it
                outcome = null
            }

            OutcomeText(outcome)

            PrimaryButton(
                label = if (working) "Checking code..." else "Check code",
                enabled = code.length == 6 && !working,
                onClick = {
                    working = true
                    scope.background({ manager.checkFriendCode(code) }) { res ->
                        working = false
                        if (res is Outcome.Done) {
                            code = ""
                            actionApplied = false
                            currentPicker = ActionPicker.NONE
                            showActionSheet = true
                        } else {
                            outcome = res
                        }
                        version++
                    }
                },
            )

            Spacer(Modifier.height(8.dp))

            SecondaryButton(
                label = "Re-key friend's code (new QR)",
                onClick = {
                    rekeyingStep = 1
                    code = ""
                    rekeyError = null
                },
            )
        }
    }

    // Single-Action Bottom Sheet
    if (showActionSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                if (!actionApplied) {
                    manager.wasteFriendCode()
                    outcome = Outcome.Refused("This code is now used. No action was taken.")
                }
                showActionSheet = false
                currentPicker = ActionPicker.NONE
            },
            containerColor = MaterialTheme.colorScheme.surface,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "This code is now used. Pick one action.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Closing without choosing will waste this code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Attention,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                when (currentPicker) {
                    ActionPicker.NONE -> {
                        val waiting = manager.requests()
                        val held = state.quarantined.toList()
                        val snapshot = inventory.read()
                        val closedBrowsers = if (config != null) {
                            ((snapshot.browsers + KnownBrowsers.ALL).filter { it in snapshot.installed } - setOfNotNull(config.allowedBrowser) - state.approvedBrowsers).sorted()
                        } else emptyList()

                        // Action 1: Open one app for an hour
                        ActionRow(
                            title = "Open one app for an hour",
                            subtitle = "Temporarily unblock an app for 60 minutes",
                            onClick = { currentPicker = ActionPicker.OPEN_APP },
                        )

                        // Action 2: Approve one waiting request now
                        ActionRow(
                            title = "Approve one waiting request now",
                            subtitle = if (waiting.isEmpty()) "No requests waiting" else "${waiting.size} request(s) waiting",
                            enabled = waiting.isNotEmpty(),
                            onClick = { currentPicker = ActionPicker.APPROVE_REQUEST },
                        )

                        // Action 3: Stop blocking one app now
                        ActionRow(
                            title = "Stop blocking one app now",
                            subtitle = "Remove from Always blocked, Window apps, or Night closed without waiting",
                            onClick = { currentPicker = ActionPicker.STOP_BLOCKING },
                        )

                        // Action 4: Let one new app through now
                        ActionRow(
                            title = "Let one new app through now",
                            subtitle = if (held.isEmpty()) "No new apps held back" else "${held.size} app(s) quarantined",
                            enabled = held.isNotEmpty(),
                            onClick = { currentPicker = ActionPicker.RELEASE_APP },
                        )

                        // Action 5: Open one closed browser now
                        ActionRow(
                            title = "Open one closed browser now",
                            subtitle = if (closedBrowsers.isEmpty()) "No closed browsers" else "${closedBrowsers.size} browser(s) closed",
                            enabled = closedBrowsers.isNotEmpty(),
                            onClick = { currentPicker = ActionPicker.APPROVE_BROWSER },
                        )

                        // Action 6: Redo the setup now
                        ActionRow(
                            title = "Redo the setup now",
                            subtitle = "Reopen the setup to change apps, settings, or DNS",
                            onClick = {
                                actionApplied = true
                                showActionSheet = false
                                scope.background({ manager.applyFriendAction(FriendAction.RedoSetup) }) { res ->
                                    outcome = res
                                    navigate(Screen.SETUP)
                                }
                            },
                        )
                    }

                    ActionPicker.OPEN_APP -> {
                        SubPickerHeader("Choose an app to open for an hour") { currentPicker = ActionPicker.NONE }
                        val apps = remember { inventory.launchableApps() }
                        LazyColumn(modifier = Modifier.height(300.dp)) {
                            items(apps) { app ->
                                AppPickerRow(label = app.label, subtitle = app.packageName) {
                                    actionApplied = true
                                    showActionSheet = false
                                    scope.background({ manager.applyFriendAction(FriendAction.OpenAppForHour(app.packageName)) }) { res ->
                                        outcome = res
                                    }
                                }
                            }
                        }
                    }

                    ActionPicker.APPROVE_REQUEST -> {
                        SubPickerHeader("Choose a waiting request to approve") { currentPicker = ActionPicker.NONE }
                        val requests = manager.requests()
                        LazyColumn(modifier = Modifier.height(300.dp)) {
                            items(requests) { req ->
                                val label = if (req.target.isNotBlank()) inventory.label(req.target) else req.kind.name
                                AppPickerRow(label = label, subtitle = "${req.kind} (asked ${req.count} times)") {
                                    actionApplied = true
                                    showActionSheet = false
                                    scope.background({ manager.applyFriendAction(FriendAction.ApproveRequest(req.id)) }) { res ->
                                        outcome = res
                                    }
                                }
                            }
                        }
                    }

                    ActionPicker.STOP_BLOCKING -> {
                        SubPickerHeader("Choose an app to stop blocking") { currentPicker = ActionPicker.NONE }
                        val items = remember {
                            buildList {
                                config?.alwaysBlocked?.forEach { add(StopBlockingItem(AppList.ALWAYS_BLOCKED, "Always blocked", it)) }
                                config?.windowApps?.forEach { add(StopBlockingItem(AppList.WINDOW_APPS, "Window apps", it)) }
                                config?.nightClosed?.forEach { add(StopBlockingItem(AppList.NIGHT_CLOSED, "Closed at night", it)) }
                            }
                        }
                        if (items.isEmpty()) {
                            Text("No apps currently in block lists.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyColumn(modifier = Modifier.height(300.dp)) {
                                items(items) { item ->
                                    val label = inventory.label(item.packageName)
                                    AppPickerRow(label = label, subtitle = "${item.listName} (${item.packageName})") {
                                        actionApplied = true
                                        showActionSheet = false
                                        scope.background({ manager.applyFriendAction(FriendAction.StopBlockingApp(item.list, item.packageName)) }) { res ->
                                            outcome = res
                                        }
                                    }
                                }
                            }
                        }
                    }

                    ActionPicker.RELEASE_APP -> {
                        SubPickerHeader("Choose a quarantined app to let through") { currentPicker = ActionPicker.NONE }
                        val held = state.quarantined.toList()
                        LazyColumn(modifier = Modifier.height(300.dp)) {
                            items(held) { pkg ->
                                val label = inventory.label(pkg)
                                AppPickerRow(label = label, subtitle = pkg) {
                                    actionApplied = true
                                    showActionSheet = false
                                    scope.background({ manager.applyFriendAction(FriendAction.ReleaseApp(pkg)) }) { res ->
                                        outcome = res
                                    }
                                }
                            }
                        }
                    }

                    ActionPicker.APPROVE_BROWSER -> {
                        SubPickerHeader("Choose a closed browser to approve") { currentPicker = ActionPicker.NONE }
                        val snapshot = inventory.read()
                        val closedBrowsers = if (config != null) {
                            ((snapshot.browsers + KnownBrowsers.ALL).filter { it in snapshot.installed } - setOfNotNull(config.allowedBrowser) - state.approvedBrowsers).sorted()
                        } else emptyList()
                        LazyColumn(modifier = Modifier.height(300.dp)) {
                            items(closedBrowsers) { pkg ->
                                val label = inventory.label(pkg)
                                AppPickerRow(label = label, subtitle = pkg) {
                                    actionApplied = true
                                    showActionSheet = false
                                    scope.background({ manager.applyFriendAction(FriendAction.ApproveBrowser(pkg)) }) { res ->
                                        outcome = res
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Re-keying Dialog Flow
    if (rekeyingStep == 1) {
        var currentCodeInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rekeyingStep = 0 },
            shape = CardShape,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Re-key friend's code", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter the current valid friend code first to verify your identity.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    CodeField(currentCodeInput) { currentCodeInput = it }
                    if (rekeyError != null) {
                        Problem(rekeyError)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = currentCodeInput.length == 6,
                    onClick = {
                        val genSecret = manager.reKeySecret(currentCodeInput)
                        if (genSecret != null) {
                            newSecret = genSecret
                            rekeyingStep = 2
                            rekeyError = null
                        } else {
                            rekeyError = "Code was wrong or already used."
                        }
                    },
                ) { Text("Verify") }
            },
            dismissButton = {
                TextButton(onClick = { rekeyingStep = 0 }) { Text("Cancel") }
            },
        )
    } else if (rekeyingStep == 2 && newSecret != null) {
        val secret = newSecret!!
        AlertDialog(
            onDismissRequest = { /* Must confirm or cancel */ },
            shape = CardShape,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Scan new friend QR", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(Modifier.clip(CardShape).background(Color.White).padding(10.dp)) {
                            QrImage(FriendCode.otpauthUri(secret))
                        }
                    }
                    Text(
                        "Have Mom or your brother scan this with their authenticator app. Once confirmed, this QR code is never shown again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CodeField(confirmNewCode) { confirmNewCode = it }
                    if (rekeyError != null) {
                        Problem(rekeyError)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = confirmNewCode.length == 6,
                    onClick = {
                        val ok = manager.verifyEnrolment(confirmNewCode)
                        if (ok) {
                            rekeyingStep = 0
                            newSecret = null
                            outcome = Outcome.Done("Friend's code successfully updated.")
                            version++
                        } else {
                            rekeyError = "Code does not match. Try the next 6-digit code."
                        }
                    },
                ) { Text("Confirm and finish") }
            },
            dismissButton = {
                TextButton(onClick = {
                    rekeyingStep = 0
                    newSecret = null
                }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SubPickerHeader(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onBack).padding(vertical = 4.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AppPickerRow(label: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/**
 * Monthly uninstall screen:
 * Shown on the 1st of each month between 20:00 and 21:00.
 * Confirms with exact text: "You chose this a month ago. Still want it? Type REMOVE"
 */
@Composable
fun RemoveScreen(onBack: () -> Unit, onRemoved: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { ChangeManager(context) }
    val store = remember { ConfigStore(context) }
    val scope = rememberCoroutineScope()
    var confirmText by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var working by remember { mutableStateOf(false) }

    val config = store.load()
    val now = TrustedClock(context).now()
    val inUninstallWindow = config?.let { Rules.schedule(it).inUninstallWindow(now) } ?: false

    Page("Remove Lock Me In", onBack) {
        AppCard {
            Text("Danger zone", style = MaterialTheme.typography.titleMedium, color = Palette.Closed)
            if (!inUninstallWindow) {
                Text(
                    "Lock Me In can only be removed during the monthly uninstall window: on the 1st of each month between 20:00 and 21:00.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "You chose this a month ago. Still want it? Type REMOVE",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "This releases all policies, clears device owner, and uninstalls Lock Me In.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutcomeText(outcome)

        if (inUninstallWindow) {
            OutlinedTextField(
                value = confirmText,
                onValueChange = { confirmText = it },
                label = { Text("Type REMOVE to confirm") },
                singleLine = true,
                shape = CardShape,
                modifier = Modifier.fillMaxWidth(),
            )

            PrimaryButton(
                label = if (working) "Removing..." else "Remove Lock Me In",
                enabled = confirmText.trim() == "REMOVE" && !working,
                onClick = {
                    working = true
                    scope.background({ manager.removeLockMeIn() }) { res ->
                        working = false
                        outcome = res
                        if (res is Outcome.Done) {
                            runCatching {
                                val uninstall = Intent(Intent.ACTION_DELETE).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(uninstall)
                            }
                            onRemoved()
                        }
                    }
                },
            )
        }
    }
}
