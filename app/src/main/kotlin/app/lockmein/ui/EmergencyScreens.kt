package app.lockmein.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.lockmein.AppInfo
import app.lockmein.ChangeManager
import app.lockmein.DeviceInventory
import app.lockmein.Outcome
import app.lockmein.core.RequestKind

@Composable
private fun CodeField(code: String, onChange: (String) -> Unit) = OutlinedTextField(
    value = code,
    onValueChange = { onChange(it.filter(Char::isDigit).take(6)) },
    label = { Text("6-digit code from your friend") },
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
)

/** The friend's code unblocks one app for 60 minutes. It changes no setting, and the app closes again by itself. */
@Composable
fun EmergencyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { ChangeManager(context) }
    val inventory = remember { DeviceInventory(context) }
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var app by remember { mutableStateOf<AppInfo?>(null) }
    var picking by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }

    Page("Emergency", onBack) {
        Note("Ask your friend for a code. It opens one app you name for 60 minutes, then the app closes again by itself. It changes no setting and no list, it is written in the weekly report, and each code works once.")
        SecondaryButton(app?.let { "App: ${it.label}" } ?: "Choose the app to open", { picking = true })
        CodeField(code) { code = it }
        OutcomeText(outcome)
        MenuButton("Open it for 60 minutes", enabled = code.length == 6 && app != null) {
            scope.background({ manager.redeemEmergency(code, app!!.packageName) }) {
                outcome = it
                if (it is Outcome.Done) code = ""
            }
        }
    }
    if (picking) {
        AppPicker(remember { inventory.launchableApps().filter { it.packageName != context.packageName } }, onDismiss = { picking = false }) {
            picking = false
            app = it
        }
    }
}

/** Takes the protection off. It is the last opening: a request that waits, then the open window and a fresh code from the friend. */
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
        Note("This lifts every rule, makes the app stop being the device owner, and lets you uninstall it. Three things are needed: you ask first and the request waits; then, in the weekly open window, a fresh code from your friend.")
        if (!asked) {
            OutcomeText(outcome)
            MenuButton("Ask to remove the protection") {
                scope.background({ manager.requestRemoval() }) { outcome = it; version++ }
            }
        } else {
            Note("Requested. When the wait is over and the window is open, enter your friend's code.")
            CodeField(code) { code = it }
            OutcomeText(outcome)
            MenuButton(if (working) "Working..." else "Remove protection", enabled = code.length == 6 && !working) {
                working = true
                scope.background({ manager.removeProtection(code) }) {
                    working = false
                    outcome = it
                    if (it is Outcome.Done) onRemoved()
                }
            }
            SecondaryButton("Cancel the request", { scope.background({ manager.cancelRequest("${RequestKind.REMOVE_PROTECTION}:") }) { outcome = it; version++ } })
        }
    }
}
