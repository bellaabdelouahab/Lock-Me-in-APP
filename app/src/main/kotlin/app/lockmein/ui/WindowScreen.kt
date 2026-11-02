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
import app.lockmein.core.ListOp
import app.lockmein.core.Phase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

/** The weekly open window: review the apps that arrived, and reach everything that can be changed. */
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
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    val open = schedule.phaseAt(now) == Phase.OPEN_WINDOW || state.emergencyUntil?.isAfter(now) == true
    val held = remember(version) { state.quarantined.sorted() }

    Page("The open window", onBack) {
        InfoCard {
            Text(if (open) "The window is open: you can change things now." else "The window is closed. It opens ${TIME.format(schedule.nextWindowStart(now).atZone(zone))}.")
            if (!open) Text("Closing the phone more (removing a night app, blocking an app) works any time. Opening it up waits for the window, or your friend's code.")
        }
        OutcomeText(outcome)

        Heading("New apps held back")
        Note("Apps installed since protection started stay suspended until you let them through here.")
        if (held.isEmpty()) Note("None.")
        held.forEach { pkg ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(inventory.label(pkg), Modifier.weight(1f))
                TextButton(onClick = {
                    scope.background({ manager.releaseApp(pkg) }) { outcome = it; version++ }
                }) { Text("Let through") }
                TextButton(onClick = {
                    scope.background({ manager.editList(AppList.ALWAYS_BLOCKED, ListOp.ADD, pkg).also { if (it is Outcome.Done) state.release(pkg) } }) { outcome = it; version++ }
                }) { Text("Block") }
            }
        }

        Heading("Change")
        SecondaryButton("Night and blocked apps", { navigate(Screen.LISTS) })
        SecondaryButton("Browsers", { navigate(Screen.BROWSERS) })
        SecondaryButton("Settings", { navigate(Screen.SETTINGS) })
    }
}
