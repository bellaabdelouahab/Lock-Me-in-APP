package app.lockmein.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.lockmein.ChangeManager
import app.lockmein.ConfigStore
import app.lockmein.DeviceInventory
import app.lockmein.LockState
import app.lockmein.Outcome
import app.lockmein.Rules
import app.lockmein.core.AppList
import app.lockmein.core.ChangeGate
import app.lockmein.core.ChangeRequest
import app.lockmein.core.ListOp
import app.lockmein.core.Phase
import app.lockmein.core.RequestKind
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

/** The weekly open window, and the requests waiting for it. The window carries out what was asked a week ago; it is not for using apps. */
@Composable
fun WindowScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val navigate = LocalNavigate.current
    val manager = remember { ChangeManager(context) }
    val store = remember { ConfigStore(context) }
    val state = remember { LockState(context) }
    val inventory = remember { DeviceInventory(context) }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    val config = store.load() ?: return Page("The open window", onBack) { Note("Finish the setup first.") }

    val schedule = Rules.schedule(config)
    val gate = ChangeGate(schedule, wait = Duration.ofDays(config.loosenWaitDays.toLong()))
    val now = app.lockmein.TrustedClock(context).now()
    val zone = ZoneId.systemDefault()
    val open = schedule.phaseAt(now) == Phase.OPEN_WINDOW
    val waiting = remember(version) { manager.requests() }
    val held = remember(version) { state.quarantined.sorted() }

    fun describe(request: ChangeRequest): String = when (request.kind) {
        RequestKind.NIGHT_ADD -> "Old request, does nothing now: ${inventory.label(request.target)}"
        RequestKind.NIGHT_REMOVE -> "Stop closing at night: ${inventory.label(request.target)}"
        RequestKind.BLOCKED_REMOVE -> "Unblock: ${inventory.label(request.target)}"
        RequestKind.BROWSER -> "Open the browser: ${inventory.label(request.target)}"
        RequestKind.RELEASE_APP -> "Let the new app through: ${inventory.label(request.target)}"
        RequestKind.SETTINGS -> "Change the settings"
        RequestKind.REMOVE_PROTECTION -> "Remove the protection"
    }

    Page("The open window", onBack) {
        InfoCard {
            Text(if (open) "The window is open now." else "The window is closed. It opens ${TIME.format(schedule.nextWindowStart(now).atZone(zone))}.")
            Text("Closing the phone more (closing an app at night, blocking an app, cancelling a request) works at any time. Opening it up is a request: it waits ${config.loosenWaitDays} days, then you carry it out here.")
        }
        OutcomeText(outcome)

        Heading("Waiting requests")
        if (waiting.isEmpty()) Note("None.")
        waiting.forEach { request ->
            val ready = gate.readyAt(request)
            InfoCard {
                Text(describe(request))
                Text(if (now >= ready) "Wait is over." else "Can be carried out after ${TIME.format(ready.atZone(zone))}.")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (request.kind == RequestKind.REMOVE_PROTECTION) {
                        TextButton(onClick = { navigate(Screen.REMOVE) }) { Text("Remove with friend's code") }
                    } else {
                        TextButton(enabled = now >= ready && open, onClick = {
                            scope.background({ manager.applyRequest(request.id) }) { outcome = it; version++ }
                        }) { Text("Carry out") }
                    }
                    TextButton(onClick = { scope.background({ manager.cancelRequest(request.id) }) { outcome = it; version++ } }) { Text("Cancel") }
                }
            }
        }

        Heading("New apps held back")
        Note("Apps installed since protection started stay suspended. Letting one through is a request like any other opening; blocking it is instant.")
        if (held.isEmpty()) Note("None.")
        held.forEach { pkg ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(inventory.label(pkg), Modifier.weight(1f))
                TextButton(onClick = {
                    scope.background({ manager.releaseApp(pkg) }) { outcome = it; version++ }
                }) { Text("Ask to let through") }
                TextButton(onClick = {
                    scope.background({ manager.editList(AppList.ALWAYS_BLOCKED, ListOp.ADD, pkg).also { if (it is Outcome.Done) state.release(pkg) } }) { outcome = it; version++ }
                }) { Text("Block") }
            }
        }

        Heading("Ask for a change")
        SecondaryButton("Night and blocked apps", { navigate(Screen.LISTS) })
        SecondaryButton("Browsers", { navigate(Screen.BROWSERS) })
        SecondaryButton("Settings", { navigate(Screen.SETTINGS) })
    }
}
