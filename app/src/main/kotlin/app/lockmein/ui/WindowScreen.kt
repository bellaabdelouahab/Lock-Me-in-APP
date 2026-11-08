package app.lockmein.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lockmein.ChangeManager
import app.lockmein.ConfigStore
import app.lockmein.DeviceInventory
import app.lockmein.LockState
import app.lockmein.Outcome
import app.lockmein.Rules
import app.lockmein.TrustedClock
import app.lockmein.core.AppList
import app.lockmein.core.ChangeGate
import app.lockmein.core.ChangeRequest
import app.lockmein.core.Decision
import app.lockmein.core.ListOp
import app.lockmein.core.RequestKind
import app.lockmein.core.RequestResolution
import app.lockmein.core.Requests
import app.lockmein.core.ResolvedRequest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

@Composable
fun WindowScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val navigate = LocalNavigate.current
    val manager = remember { ChangeManager(context) }
    val store = remember { ConfigStore(context) }
    val state = remember { LockState(context) }
    val inventory = remember { DeviceInventory(context) }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    val config = store.load() ?: return Page("Requests", onBack) { Note("Finish the setup first.") }

    val schedule = Rules.schedule(config)
    val gate = ChangeGate(schedule)
    val now = TrustedClock(context).now()
    val zone = ZoneId.systemDefault()
    val inSettingsWindow = schedule.inSettingsWindow(now)
    val waiting = remember(version) { manager.requests() }
    val history = remember(version) { state.requestHistory }
    val held = remember(version) { state.quarantined.sorted() }

    fun describeKind(kind: RequestKind, target: String): Pair<String, String?> = when (kind) {
        RequestKind.NIGHT_ADD -> "Old request (no effect)" to target
        RequestKind.NIGHT_REMOVE -> "Stop closing at night" to inventory.label(target)
        RequestKind.BLOCKED_REMOVE -> "Unblock app" to inventory.label(target)
        RequestKind.WINDOW_REMOVE -> "Remove from window apps" to inventory.label(target)
        RequestKind.WATCHED_REMOVE -> "Remove from watched apps" to inventory.label(target)
        RequestKind.BROWSER -> "Approve browser" to inventory.label(target)
        RequestKind.RELEASE_APP -> "Let new app through" to inventory.label(target)
        RequestKind.SETTINGS -> "Change settings" to null
        RequestKind.REDO_SETUP -> "Redo setup" to null
        RequestKind.REMOVE_PROTECTION -> "Remove protection" to null
    }

    Page(
        title = "Requests",
        onBack = onBack,
        bottomBar = {
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
                    SecondaryButton(
                        label = "Friend's code",
                        onClick = { navigate(Screen.EMERGENCY) },
                    )
                }
            }
        },
    ) {
        // Status of the Sunday review window
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (inSettingsWindow) Icons.Filled.CheckCircle else Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = if (inSettingsWindow) Palette.Ok else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    if (inSettingsWindow) "Sunday review window is active" else "Sunday review window is closed",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                if (inSettingsWindow) "The weekly review window is open until ${config.windowEnd}."
                else "Next review window: Sunday 20:00 to 21:00 (" + TIME.format(schedule.nextWindowStart(now).atZone(zone)) + ").",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "During the week, loosening changes are recorded as requests. On Sunday 20:00 to 21:00 you can approve or dismiss requests made before the window started.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutcomeText(outcome)

        Heading("Waiting requests (${waiting.size})")
        if (waiting.isEmpty()) {
            AppCard {
                Text(
                    "No requests waiting. Any loosening change you ask for during the week appears here to review on Sunday.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            waiting.forEach { request ->
                val decision = gate.decideSundayReview(request, now)
                val canApprove = decision is Decision.Allowed
                val (title, targetLabel) = describeKind(request.kind, request.target)
                val timesAsked = if (request.count == 1) "Asked 1 time this week" else "Asked ${request.count} times this week"

                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            if (targetLabel != null) {
                                Text(targetLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            }
                            Text(timesAsked, style = MaterialTheme.typography.bodySmall, color = Palette.Attention, fontWeight = FontWeight.Medium)
                        }
                        StatusBadge(
                            kind = if (canApprove) StatusKind.OK else StatusKind.ATTENTION,
                            label = if (canApprove) "Ready" else "Waiting",
                        )
                    }

                    Text(
                        if (canApprove) "Requested before window started: ready to approve now."
                        else decision.let { (it as? Decision.Denied)?.why ?: "Waits for Sunday review." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            enabled = canApprove,
                            onClick = {
                                scope.background({ manager.approveSundayRequest(request.id) }) {
                                    outcome = it
                                    version++
                                }
                            },
                        ) {
                            Text(if (canApprove) "Approve" else "Waits for Sunday")
                        }

                        Spacer(Modifier.weight(1f))

                        TextButton(
                            onClick = {
                                scope.background({ manager.dismissSundayRequest(request.id) }) {
                                    outcome = it
                                    version++
                                }
                            },
                        ) {
                            Text("Dismiss", color = Palette.Closed)
                        }
                    }
                }
            }
        }

        // History Section
        Heading("History (${history.size})")
        if (history.isEmpty()) {
            AppCard {
                Text(
                    "No resolved requests in history yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AppCard {
                history.forEachIndexed { index, item ->
                    val (title, targetLabel) = describeKind(item.kind, item.target)
                    val countText = if (item.count == 1) "Asked 1 time" else "Asked ${item.count} times"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(targetLabel ?: title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "$countText • ${TIME.format(item.resolvedAt.atZone(zone))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusBadge(
                            kind = if (item.resolution == RequestResolution.APPROVED) StatusKind.OK else StatusKind.ATTENTION,
                            label = if (item.resolution == RequestResolution.APPROVED) "Approved" else "Dismissed",
                        )
                    }
                    if (index < history.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }

        Heading("Held-back new apps (${held.size})")
        Note("Apps installed since protection started stay suspended until reviewed. Letting one through is a request that waits for Sunday; blocking it is instant.")

        if (held.isEmpty()) {
            AppCard {
                Text(
                    "No new apps held back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AppCard {
                held.forEachIndexed { index, pkg ->
                    val label = inventory.label(pkg)
                    AppRow(
                        packageName = pkg,
                        title = label,
                        subtitle = "Installed recently (quarantined)",
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                scope.background({ manager.releaseApp(pkg) }) {
                                    outcome = it
                                    version++
                                }
                            }) {
                                Text("Ask to let through")
                            }
                            TextButton(onClick = {
                                scope.background({
                                    manager.editList(AppList.ALWAYS_BLOCKED, ListOp.ADD, pkg).also {
                                        if (it is Outcome.Done) state.release(pkg)
                                    }
                                }) {
                                    outcome = it
                                    version++
                                }
                            }) {
                                Text("Block", color = Palette.Closed)
                            }
                        }
                    }
                    if (index < held.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}
