package app.lockmein.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.lockmein.ChangeManager
import app.lockmein.ConfigStore
import app.lockmein.Outcome

/**
 * The way out of a setup that went wrong (a DNS host that cuts the internet, a half-applied start): everything back to the
 * phone's defaults, the app stays device owner. Two taps. Shown only until the setup completed with every check passing.
 */
@Composable
fun ResetSetup(onReset: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    if (remember { ConfigStore(context).setupComplete }) return
    var armed by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    Heading("Something went wrong?")
    Note(if (armed) "Tap again to reset. Private DNS goes back to automatic, nothing stays blocked, and your settings and friend's code are wiped. Lock Me In stays device owner, so the computer step is not needed again."
        else "Reset takes everything this setup did back off the phone, so you can start over. It works only until the setup is complete.")
    SecondaryButton(if (working) "Resetting..." else if (armed) "Tap again to reset" else "Reset to defaults", {
        if (!armed) { armed = true; return@SecondaryButton }
        working = true
        scope.background({ ChangeManager(context).resetSetup() }) { result ->
            working = false
            armed = false
            outcome = result
            if (result is Outcome.Done) onReset()
        }
    }, enabled = !working)
    OutcomeText(outcome)
}

/**
 * On home: a protection that was started but never passed every check. Until it does, lists and settings change at once, so this
 * is the moment to pick the night apps. "Finish the setup" applies the rules again and completes the setup only if Android
 * confirms every check; otherwise it lists what is still not in force.
 */
@Composable
fun UnfinishedSetupBanner(onReset: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var complete by remember { mutableStateOf(ConfigStore(context).setupComplete) }
    if (complete) return
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<List<app.lockmein.HealthItem>?>(null) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    Text("SETUP NOT FINISHED", style = MaterialTheme.typography.labelLarge, color = Palette.Locked)
    Note("Until the setup is complete, every change works at once. Pick your night apps now (Apps): once it is complete, adding one waits 7 days. Then finish: the rules are applied again and the setup completes only if every check passes.")
    MenuButton(if (working) "Checking..." else "Finish the setup", enabled = !working) {
        working = true
        outcome = null
        scope.background({
            val result = ChangeManager(context).startProtection()
            result to if (result is Outcome.Done) app.lockmein.Health(context).check() else null
        }) { (result, health) ->
            working = false
            val bad = health.orEmpty().filter { !it.ok }
            if (result is Outcome.Done && health != null && bad.isEmpty()) {
                ConfigStore(context).setupComplete = true
                complete = true
            } else {
                outcome = result.takeIf { it is Outcome.Refused }
                failed = bad
            }
        }
    }
    OutcomeText(outcome)
    failed?.forEach { CheckRow(it.title, CheckState.FAIL, it.detail) }
    ResetSetup(onReset)
}
