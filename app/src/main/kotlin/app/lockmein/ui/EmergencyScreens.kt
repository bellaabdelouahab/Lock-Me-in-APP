package app.lockmein.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.lockmein.ChangeManager
import app.lockmein.Outcome

@Composable
private fun CodeField(code: String, onChange: (String) -> Unit) = OutlinedTextField(
    value = code,
    onValueChange = { onChange(it.filter(Char::isDigit).take(6)) },
    label = { Text("6-digit code from your friend") },
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
)

/** The friend's code opens a 60-minute window at any time: a real emergency, or a bug that blocks work. */
@Composable
fun EmergencyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { ChangeManager(context) }
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<Outcome?>(null) }

    Page("Emergency", onBack) {
        Note("Ask your friend for a code. It opens everything for 60 minutes and shows up in the weekly report. Each code works once.")
        CodeField(code) { code = it }
        OutcomeText(outcome)
        MenuButton("Open the emergency window", enabled = code.length == 6) {
            scope.background({ manager.redeemEmergency(code) }) {
                outcome = it
                if (it is Outcome.Done) code = ""
            }
        }
    }
}

/** Takes the protection off. It needs the open window (or an emergency) and the friend's code together. */
@Composable
fun RemoveScreen(onBack: () -> Unit, onRemoved: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { ChangeManager(context) }
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var working by remember { mutableStateOf(false) }

    Page("Remove protection", onBack) {
        Note("This lifts every rule, makes the app stop being the device owner, and lets you uninstall it. It works only in the open window (or an emergency window), and needs a fresh code from your friend.")
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
    }
}
