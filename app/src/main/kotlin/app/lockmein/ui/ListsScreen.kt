package app.lockmein.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lockmein.AppInfo
import app.lockmein.ChangeManager
import app.lockmein.ConfigStore
import app.lockmein.DeviceInventory
import app.lockmein.LockState
import app.lockmein.Outcome
import app.lockmein.PolicyRunner
import app.lockmein.core.AppList
import app.lockmein.core.ListOp
import app.lockmein.core.LockConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One app the rules close right now, why, and whether Android really holds it closed. */
private data class Closed(val packageName: String, val why: String, val closedNow: Boolean, val held: Boolean)

/** The two lists the person edits (apps closed at night, apps blocked all the time) and what is closed right now. */
@Composable
fun ListsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { ChangeManager(context) }
    val store = remember { ConfigStore(context) }
    val inventory = remember { DeviceInventory(context) }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var picking by remember { mutableStateOf<AppList?>(null) }

    val config = remember(version) { store.load() } ?: return Page("Apps", onBack) { Note("Finish the setup first.") }

    fun change(list: AppList, op: ListOp, pkg: String) {
        scope.background({ manager.editList(list, op, pkg) }) {
            outcome = it
            version++
        }
    }

    val closed by produceState<List<Closed>?>(null, version) { value = withContext(Dispatchers.IO) { closedNow(context, config) } }

    val open = !ConfigStore(context).setupComplete
    Page("Night and blocked apps", onBack) {
        if (open) Note("The setup is not complete yet, so every change here works at once. Once it is complete, opening anything up waits 7 days.")
        OutcomeText(outcome)
        Heading("Closed right now")
        Note("Read from Android, not from the app's own notes. Your home screen may not grey out every closed app: this list is what is really closed.")
        when {
            closed == null -> Note("Reading...")
            closed!!.isEmpty() -> Note("Nothing is closed at this hour.")
            else -> closed!!.forEach { app ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app.packageName, 34.dp)
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(inventory.label(app.packageName))
                        Text(app.why, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = Palette.Muted)
                        if (!app.closedNow) Text("Should be closed but is open. The next pass closes it; Repair now on Is it working? does it at once.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = Palette.Locked)
                    }
                    if (app.held) TextButton(onClick = { scope.background({ manager.releaseApp(app.packageName) }) { outcome = it; version++ } }) { Text("Let through") }
                }
            }
        }
        Listing("Closed at night", "From the night start until fajr these apps are closed; every other app keeps working. Adding one works at once. Taking one off opens the phone up: it waits 7 days, then you carry it out in the weekly window.", config.nightClosed, inventory,
            onRemove = { change(AppList.NIGHT_CLOSED, ListOp.REMOVE, it) }, onAdd = { picking = AppList.NIGHT_CLOSED })
        Listing("Apps blocked all the time", "Blocking works at once. Unblocking is a request that waits 7 days.", config.alwaysBlocked, inventory,
            onRemove = { change(AppList.ALWAYS_BLOCKED, ListOp.REMOVE, it) }, onAdd = { picking = AppList.ALWAYS_BLOCKED })
    }

    picking?.let { list ->
        val taken = if (list == AppList.NIGHT_CLOSED) config.nightClosed else config.alwaysBlocked
        val choices = remember(list, version) { inventory.launchableApps().filter { it.packageName !in taken && it.packageName != context.packageName } }
        AppPicker(choices, onDismiss = { picking = null }) {
            picking = null
            change(list, ListOp.ADD, it.packageName)
        }
    }
}

@Composable
private fun Listing(title: String, note: String, packages: Set<String>, inventory: DeviceInventory, onRemove: (String) -> Unit, onAdd: () -> Unit) {
    Heading(title)
    Note(note)
    if (packages.isEmpty()) Note("None.")
    packages.sorted().forEach { pkg ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(pkg, 34.dp)
            Text(inventory.label(pkg), Modifier.weight(1f).padding(start = 12.dp))
            TextButton(onClick = { onRemove(pkg) }) { Text("Remove") }
        }
    }
    SecondaryButton("Add an app", onAdd)
}

@Composable
fun AppPicker(choices: List<AppInfo>, onDismiss: () -> Unit, onPick: (AppInfo) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose an app") },
        text = {
            LazyColumn {
                items(choices) { app -> Text(app.label, Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 12.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What the rules close at this moment, each with its reason, checked against what Android really holds. Blocks: call off the main thread. */
private fun closedNow(context: android.content.Context, config: LockConfig): List<Closed> {
    val plan = PolicyRunner(context).planFor(config)
    val held = LockState(context).quarantined
    val pm = context.packageManager
    val browser = config.allowedBrowser?.let { DeviceInventory(context).label(it) }
    return plan.suspended.map { pkg ->
        val why = when {
            pkg in config.alwaysBlocked -> "Blocked all the time."
            pkg in held -> "New since the protection started: held back until you let it through in Requests."
            pkg == "com.google.android.youtube" -> "YouTube is closed all the time."
            pkg in config.nightClosed -> "Closed at night, until fajr."
            else -> if (browser == null) "A browser: none stays open." else "A browser: only $browser stays open."
        }
        Closed(pkg, why, runCatching { pm.isPackageSuspended(pkg) }.getOrDefault(true), pkg in held)
    }.sortedBy { DeviceInventory(context).label(it.packageName).lowercase() }
}
