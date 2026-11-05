package app.lockmein.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.font.FontWeight
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

private data class Closed(val packageName: String, val why: String, val closedNow: Boolean, val held: Boolean)

private data class PendingRemoval(val list: AppList, val packageName: String, val label: String)

@Composable
fun ListsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { ChangeManager(context) }
    val store = remember { ConfigStore(context) }
    val inventory = remember { DeviceInventory(context) }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var pickingForList by remember { mutableStateOf<AppList?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var pendingRemoval by remember { mutableStateOf<PendingRemoval?>(null) }

    val config = remember(version) { store.load() } ?: return Page("Apps", onBack) { Note("Finish the setup first.") }
    val isSetupComplete = remember(version) { store.setupComplete }

    fun change(list: AppList, op: ListOp, pkg: String) {
        scope.background({ manager.editList(list, op, pkg) }) {
            outcome = it
            version++
        }
    }

    val closed by produceState<List<Closed>?>(null, version) {
        value = withContext(Dispatchers.IO) { closedNow(context, config) }
    }

    val tabs = listOf(
        "At night (${config.nightClosed.size})",
        "Always (${config.alwaysBlocked.size})",
        "Now (${closed?.size ?: 0})",
    )

    Page(
        title = "Apps",
        onBack = onBack,
        bottomBar = {
            if (selectedTab < 2) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        PrimaryButton(
                            label = if (selectedTab == 0) "+ Add night apps" else "+ Add blocked apps",
                            onClick = {
                                pickingForList = if (selectedTab == 0) AppList.NIGHT_CLOSED else AppList.ALWAYS_BLOCKED
                            },
                        )
                    }
                }
            }
        },
    ) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index; searchQuery = "" },
                    text = {
                        Text(
                            title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    },
                )
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Filter apps in this tab") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = CardShape,
            modifier = Modifier.fillMaxWidth(),
        )

        OutcomeText(outcome)

        when (selectedTab) {
            0 -> {
                // Closed at night
                Note(
                    if (!isSetupComplete) "Setup is not complete yet: changes apply at once. When complete, removing an app waits 7 days."
                    else "From your night start until fajr these apps are closed. Every other app keeps working.",
                )
                val filtered = config.nightClosed
                    .filter { pkg ->
                        val label = inventory.label(pkg)
                        searchQuery.isBlank() || label.contains(searchQuery, ignoreCase = true) || pkg.contains(searchQuery, ignoreCase = true)
                    }
                    .sortedBy { inventory.label(it).lowercase() }

                if (filtered.isEmpty()) {
                    AppCard {
                        Text(
                            if (searchQuery.isNotBlank()) "No apps match your filter."
                            else "No apps closed at night. Use the button below to add apps.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    AppCard {
                        filtered.forEachIndexed { index, pkg ->
                            val label = inventory.label(pkg)
                            AppRow(
                                packageName = pkg,
                                title = label,
                                subtitle = if (isSetupComplete) "Closed at night (removing waits 7 days)" else "Closed at night (works now)",
                            ) {
                                TextButton(onClick = {
                                    pendingRemoval = PendingRemoval(AppList.NIGHT_CLOSED, pkg, label)
                                }) {
                                    Text("Remove", color = Palette.Closed)
                                }
                            }
                            if (index < filtered.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
            1 -> {
                // Always blocked
                Note(
                    if (!isSetupComplete) "Blocking works at once. Setup is not complete yet, so unblocking also works at once."
                    else "These apps stay closed in every phase. Adding is instant. Unblocking is a request that waits 7 days.",
                )
                val filtered = config.alwaysBlocked
                    .filter { pkg ->
                        val label = inventory.label(pkg)
                        searchQuery.isBlank() || label.contains(searchQuery, ignoreCase = true) || pkg.contains(searchQuery, ignoreCase = true)
                    }
                    .sortedBy { inventory.label(it).lowercase() }

                if (filtered.isEmpty()) {
                    AppCard {
                        Text(
                            if (searchQuery.isNotBlank()) "No apps match your filter."
                            else "No apps blocked all the time. Use the button below to add apps.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    AppCard {
                        filtered.forEachIndexed { index, pkg ->
                            val label = inventory.label(pkg)
                            AppRow(
                                packageName = pkg,
                                title = label,
                                subtitle = if (isSetupComplete) "Always blocked (unblocking waits 7 days)" else "Always blocked (works now)",
                            ) {
                                TextButton(onClick = {
                                    pendingRemoval = PendingRemoval(AppList.ALWAYS_BLOCKED, pkg, label)
                                }) {
                                    Text("Remove", color = Palette.Closed)
                                }
                            }
                            if (index < filtered.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
            2 -> {
                // Closed right now
                Note("Read directly from Android. This list reflects apps currently suspended on the device.")
                val list = closed
                when {
                    list == null -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Reading from Android...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    list.isEmpty() -> {
                        AppCard {
                            Text("Nothing is closed at this hour.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    else -> {
                        val filtered = list.filter { app ->
                            val label = inventory.label(app.packageName)
                            searchQuery.isBlank() || label.contains(searchQuery, ignoreCase = true) || app.packageName.contains(searchQuery, ignoreCase = true)
                        }
                        if (filtered.isEmpty()) {
                            AppCard {
                                Text("No closed apps match your filter.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            AppCard {
                                filtered.forEachIndexed { index, app ->
                                    val label = inventory.label(app.packageName)
                                    AppRow(
                                        packageName = app.packageName,
                                        title = label,
                                        subtitle = if (!app.closedNow) "Should be closed but is open. Next pass closes it." else app.why,
                                        statusColor = if (!app.closedNow) Palette.Closed else null,
                                    ) {
                                        if (app.held) {
                                            TextButton(onClick = {
                                                scope.background({ manager.releaseApp(app.packageName) }) {
                                                    outcome = it
                                                    version++
                                                }
                                            }) {
                                                Text("Let through")
                                            }
                                        }
                                    }
                                    if (index < filtered.lastIndex) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation dialog before removing an app
    pendingRemoval?.let { removal ->
        val consequences = if (!isSetupComplete) {
            "Works now: removing ${removal.label} will take effect immediately because the setup is not finished yet."
        } else {
            "Waits 7 days: removing ${removal.label} opens the phone up, so it becomes a request that waits 7 days, then applies in the weekly open window."
        }
        ConfirmDialog(
            title = "Remove ${removal.label}?",
            text = consequences,
            confirmLabel = "Remove",
            isDestructive = true,
            onDismiss = { pendingRemoval = null },
            onConfirm = {
                val list = removal.list
                val pkg = removal.packageName
                pendingRemoval = null
                change(list, ListOp.REMOVE, pkg)
            },
        )
    }

    // Add apps picker
    pickingForList?.let { list ->
        val taken = if (list == AppList.NIGHT_CLOSED) config.nightClosed else config.alwaysBlocked
        val choices = remember(list, version) {
            inventory.launchableApps().filter { it.packageName !in taken && it.packageName != context.packageName }
        }
        MultiAppPicker(
            choices = choices,
            onDismiss = { pickingForList = null },
            onConfirm = { picked ->
                pickingForList = null
                picked.forEach { app ->
                    change(list, ListOp.ADD, app.packageName)
                }
            },
        )
    }
}

private fun closedNow(context: android.content.Context, config: LockConfig): List<Closed> {
    val plan = PolicyRunner(context).planFor(config)
    val held = LockState(context).quarantined
    val pm = context.packageManager
    val browser = config.allowedBrowser?.let { DeviceInventory(context).label(it) }
    return plan.suspended.map { pkg ->
        val why = when {
            pkg in config.alwaysBlocked -> "Blocked all the time."
            pkg in held -> "New app: held back until reviewed in Requests."
            pkg == "com.google.android.youtube" -> "YouTube is closed all the time."
            pkg in config.nightClosed -> "Closed at night, until fajr."
            else -> if (browser == null) "A browser: none stays open." else "A browser: only $browser stays open."
        }
        Closed(pkg, why, runCatching { pm.isPackageSuspended(pkg) }.getOrDefault(true), pkg in held)
    }.sortedBy { DeviceInventory(context).label(it.packageName).lowercase() }
}
