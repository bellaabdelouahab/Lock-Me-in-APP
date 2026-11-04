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
import app.lockmein.Outcome
import app.lockmein.core.AppList
import app.lockmein.core.ListOp
import app.lockmein.core.NightRisk

/** The two lists the person edits: apps allowed at night and apps blocked all the time. */
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
            val risk = if (list == AppList.NIGHT_ALLOWED && op == ListOp.ADD) NightRisk.warning(pkg) else null
            outcome = if (risk != null && it is Outcome.Done) Outcome.Done("${it.message} Careful: $risk") else it
            version++
        }
    }

    val open = !ConfigStore(context).setupComplete
    Page("Night and blocked apps", onBack) {
        if (open) Note("The setup is not complete yet, so every change here works at once. Once it is complete, opening anything up waits 7 days.")
        OutcomeText(outcome)
        Listing("Apps allowed at night", "Adding one opens the phone up: it is a request that waits 7 days, then you carry it out in the weekly window. Removing one works at once.", config.nightAllowlist, inventory,
            onRemove = { change(AppList.NIGHT_ALLOWED, ListOp.REMOVE, it) }, onAdd = { picking = AppList.NIGHT_ALLOWED })
        Listing("Apps blocked all the time", "Blocking works at once. Unblocking is a request that waits 7 days.", config.alwaysBlocked, inventory,
            onRemove = { change(AppList.ALWAYS_BLOCKED, ListOp.REMOVE, it) }, onAdd = { picking = AppList.ALWAYS_BLOCKED })
    }

    picking?.let { list ->
        val taken = if (list == AppList.NIGHT_ALLOWED) config.nightAllowlist else config.alwaysBlocked
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
