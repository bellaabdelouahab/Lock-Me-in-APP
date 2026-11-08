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
import androidx.compose.material.icons.filled.Search
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
import app.lockmein.Rules
import app.lockmein.TrustedClock
import app.lockmein.FilterService
import app.lockmein.core.AppList
import app.lockmein.core.ListOp
import app.lockmein.core.LockConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Closed(val packageName: String, val why: String, val closedNow: Boolean, val held: Boolean)

private data class PendingRemoval(val list: AppList, val packageName: String, val label: String)

@Composable
fun ListsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { ChangeManager(context) }
    val store = remember { ConfigStore(context) }
    val state = remember { LockState(context) }
    val inventory = remember { DeviceInventory(context) }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var pickingForList by remember { mutableStateOf<AppList?>(null) }
    val initialTab = remember {
        (context as? android.app.Activity)?.intent?.getIntExtra("tab", 0) ?: 0
    }
    var selectedTab by remember { mutableIntStateOf(initialTab.coerceIn(0, 4)) }
    var searchQuery by remember { mutableStateOf("") }
    var pendingRemoval by remember { mutableStateOf<PendingRemoval?>(null) }

    val config = remember(version) { store.load() } ?: return Page("Apps", onBack) { Note("Finish the setup first.") }
    val isSetupComplete = remember(version) { store.setupComplete }
    val isLocked = remember(version) { manager.locked }
    val now = TrustedClock(context).now()

    fun change(list: AppList, op: ListOp, pkg: String) {
        scope.background({ manager.editList(list, op, pkg) }) {
            outcome = it
            version++
        }
    }

    val closed by produceState<List<Closed>?>(null, version) {
        value = withContext(Dispatchers.IO) { closedNow(context, config, now) }
    }

    val tabs = listOf(
        "At night (${config.nightClosed.size})",
        "Always blocked (${config.alwaysBlocked.size})",
        "Window apps (${config.windowApps.size})",
        "Watched (${config.watchedApps.size})",
        "Now (${closed?.size ?: 0})",
    )

    Page(
        title = "Apps",
        onBack = onBack,
        bottomBar = {
            if (selectedTab < 4) {
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
                            label = when (selectedTab) {
                                0 -> "+ Add night apps"
                                1 -> "+ Add blocked apps"
                                2 -> "+ Add window apps"
                                else -> "+ Add watched apps"
                            },
                            onClick = {
                                pickingForList = when (selectedTab) {
                                    0 -> AppList.NIGHT_CLOSED
                                    1 -> AppList.ALWAYS_BLOCKED
                                    2 -> AppList.WINDOW_APPS
                                    else -> AppList.WATCHED
                                }
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
                    if (!isSetupComplete) "Setup is not complete yet: changes apply at once."
                    else "From your night start until fajr these apps are closed. Adding is instant. Removing is a request for Sunday review.",
                )
                renderAppList(
                    apps = config.nightClosed,
                    listType = AppList.NIGHT_CLOSED,
                    searchQuery = searchQuery,
                    inventory = inventory,
                    isLocked = isLocked,
                    onRequestRemove = { pkg, label ->
                        pendingRemoval = PendingRemoval(AppList.NIGHT_CLOSED, pkg, label)
                    },
                )
            }
            1 -> {
                // Always blocked
                Note(
                    if (!isSetupComplete) "Blocking works at once. Setup is not complete yet, so unblocking also works at once."
                    else "These apps stay closed in every phase. Adding is instant. Removing is a request for Sunday review.",
                )
                renderAppList(
                    apps = config.alwaysBlocked,
                    listType = AppList.ALWAYS_BLOCKED,
                    searchQuery = searchQuery,
                    inventory = inventory,
                    isLocked = isLocked,
                    onRequestRemove = { pkg, label ->
                        pendingRemoval = PendingRemoval(AppList.ALWAYS_BLOCKED, pkg, label)
                    },
                )
            }
            2 -> {
                // Window apps
                Note(
                    if (!isSetupComplete) "Setup is not complete yet: changes apply at once."
                    else "These apps open only during the daily window (20:00 to 21:30) and are suspended the rest of the day. Adding is instant. Removing is a request for Sunday review.",
                )
                renderAppList(
                    apps = config.windowApps,
                    listType = AppList.WINDOW_APPS,
                    searchQuery = searchQuery,
                    inventory = inventory,
                    isLocked = isLocked,
                    onRequestRemove = { pkg, label ->
                        pendingRemoval = PendingRemoval(AppList.WINDOW_APPS, pkg, label)
                    },
                )
            }
            3 -> {
                // Watched apps
                Note(
                    if (!isSetupComplete) "Setup is not complete yet: changes apply at once."
                    else "The on-device AI screen filter monitors these apps for explicit and revealing content. Adding is instant. Removing is a request for Sunday review.",
                )
                renderAppList(
                    apps = config.watchedApps,
                    listType = AppList.WATCHED,
                    searchQuery = searchQuery,
                    inventory = inventory,
                    isLocked = isLocked,
                    onRequestRemove = { pkg, label ->
                        pendingRemoval = PendingRemoval(AppList.WATCHED, pkg, label)
                    },
                )
            }
            4 -> {
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
        val consequences = if (!isLocked) {
            "Removing ${removal.label} will take effect immediately because the setup is not finished yet."
        } else {
            "Ask to remove ${removal.label}? This will create a request reviewed and approved during the Sunday window (20:00 to 21:00)."
        }
        ConfirmDialog(
            title = "Remove ${removal.label}?",
            text = consequences,
            confirmLabel = if (isLocked) "Request removal" else "Remove",
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
        val taken = when (list) {
            AppList.NIGHT_CLOSED -> config.nightClosed
            AppList.ALWAYS_BLOCKED -> config.alwaysBlocked
            AppList.WINDOW_APPS -> config.windowApps
            AppList.WATCHED -> config.watchedApps
        }
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

@Composable
private fun renderAppList(
    apps: Set<String>,
    listType: AppList,
    searchQuery: String,
    inventory: DeviceInventory,
    isLocked: Boolean,
    onRequestRemove: (String, String) -> Unit,
) {
    val filtered = apps
        .filter { pkg ->
            val label = inventory.label(pkg)
            searchQuery.isBlank() || label.contains(searchQuery, ignoreCase = true) || pkg.contains(searchQuery, ignoreCase = true)
        }
        .sortedBy { inventory.label(it).lowercase() }

    if (filtered.isEmpty()) {
        AppCard {
            Text(
                if (searchQuery.isNotBlank()) "No apps match your filter."
                else "No apps listed here. Use the button below to add apps.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        AppCard {
            filtered.forEachIndexed { index, pkg ->
                val label = inventory.label(pkg)
                val subtitle = when (listType) {
                    AppList.WINDOW_APPS -> "Open only 20:00 to 21:30"
                    AppList.NIGHT_CLOSED -> "Closed at night"
                    AppList.ALWAYS_BLOCKED -> "Always blocked"
                    AppList.WATCHED -> "Watched by screen filter"
                }

                AppRow(
                    packageName = pkg,
                    title = label,
                    subtitle = subtitle,
                ) {
                    TextButton(onClick = { onRequestRemove(pkg, label) }) {
                        Text(if (isLocked) "Request remove" else "Remove", color = Palette.Closed)
                    }
                }
                if (index < filtered.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

private fun closedNow(context: android.content.Context, config: LockConfig, now: Instant): List<Closed> {
    val inventory = DeviceInventory(context).read()
    val state = LockState(context)
    val plan = PolicyRunner(context).planFor(config, now)
    val admin = android.content.ComponentName(context, app.lockmein.AdminReceiver::class.java)
    val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
    val isOwner = dpm.isDeviceOwnerApp(context.packageName)
    val held = state.quarantined

    return plan.suspended.map { pkg ->
        val isSuspended = if (isOwner) dpm.isPackageSuspended(admin, pkg) else false
        val filterBlockedUntil = state.filterBlocked(now)[pkg]
        val isFilterServiceOff = !FilterService.isEnabled(context)
        val why = when {
            filterBlockedUntil != null -> "Closed by the filter until ${DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(filterBlockedUntil)}."
            isFilterServiceOff && pkg in config.watchedApps -> "Watched app: closed because the screen filter is off."
            pkg in held -> "New app: held back until reviewed in Requests."
            pkg in config.alwaysBlocked -> "Always blocked."
            pkg in config.windowApps -> "Window app: closed outside 20:00 to 21:30."
            pkg in config.nightClosed -> "Closed at night."
            pkg in inventory.browsers -> "Other browser: only ${config.allowedBrowser ?: "none"} is allowed."
            else -> "Suspended by policy."
        }
        Closed(pkg, why, isSuspended, held = pkg in held)
    }.sortedBy { it.packageName }
}
