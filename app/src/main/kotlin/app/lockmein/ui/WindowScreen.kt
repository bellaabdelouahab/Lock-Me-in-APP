package app.lockmein.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
    val waitDuration = Duration.ofDays(config.loosenWaitDays.toLong())
    val gate = ChangeGate(schedule, wait = waitDuration)
    val now = app.lockmein.TrustedClock(context).now()
    val zone = ZoneId.systemDefault()
    val open = schedule.phaseAt(now) == Phase.OPEN_WINDOW
    val waiting = remember(version) { manager.requests() }
    val held = remember(version) { state.quarantined.sorted() }

    fun describe(request: ChangeRequest): Pair<String, String?> = when (request.kind) {
        RequestKind.NIGHT_ADD -> "Old request (no effect)" to request.target
        RequestKind.NIGHT_REMOVE -> "Stop closing at night" to inventory.label(request.target)
        RequestKind.BLOCKED_REMOVE -> "Unblock app" to inventory.label(request.target)
        RequestKind.BROWSER -> "Approve browser" to inventory.label(request.target)
        RequestKind.RELEASE_APP -> "Let new app through" to inventory.label(request.target)
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
                        label = "Emergency: use friend's code",
                        onClick = { navigate(Screen.EMERGENCY) },
                    )
                }
            }
        },
    ) {
        // Status of the open window
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (open) Icons.Filled.CheckCircle else Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = if (open) Palette.Ok else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    if (open) "Open window is active now" else "Open window is closed",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                StatusBadge(
                    kind = if (open) StatusKind.OK else StatusKind.ATTENTION,
                    label = if (open) "Open now" else "Opens " + TIME.format(schedule.nextWindowStart(now).atZone(zone)),
                )
            }
            Text(
                "Opening the phone up waits ${config.loosenWaitDays} days and applies only in the weekly window. Closing things works at once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutcomeText(outcome)

        Heading("Waiting requests (${waiting.size})")
        if (waiting.isEmpty()) {
            AppCard {
                Text(
                    "No requests waiting. Any opening you ask for will wait ${config.loosenWaitDays} days, then appear here to carry out.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            waiting.forEach { request ->
                val ready = gate.readyAt(request)
                val isReady = now >= ready
                val (title, targetLabel) = describe(request)
                val totalWaitMillis = waitDuration.toMillis().coerceAtLeast(1)
                val elapsedMillis = Duration.between(request.requestedAt, now).toMillis().coerceAtLeast(0)
                val progress = (elapsedMillis.toFloat() / totalWaitMillis).coerceIn(0f, 1f)

                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            if (targetLabel != null) {
                                Text(targetLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            }
                        }
                        StatusBadge(
                            kind = if (isReady) StatusKind.OK else StatusKind.ATTENTION,
                            label = if (isReady) "Wait over" else "Waiting",
                        )
                    }

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = if (isReady) Palette.Ok else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )

                    Text(
                        if (isReady) "Ready to carry out in the open window."
                        else "Can be carried out after ${TIME.format(ready.atZone(zone))}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (request.kind == RequestKind.REMOVE_PROTECTION) {
                            TextButton(onClick = { navigate(Screen.REMOVE) }) {
                                Text("Remove with friend's code", color = Palette.Closed)
                            }
                        } else {
                            val canApply = isReady && open
                            TextButton(
                                enabled = canApply,
                                onClick = {
                                    scope.background({ manager.applyRequest(request.id) }) {
                                        outcome = it
                                        version++
                                    }
                                },
                            ) {
                                Text(
                                    if (canApply) "Carry out now"
                                    else if (!isReady) "Wait not over"
                                    else "Window not open",
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                scope.background({ manager.cancelRequest(request.id) }) {
                                    outcome = it
                                    version++
                                }
                            },
                        ) {
                            Text("Cancel", color = Palette.Closed)
                        }
                    }
                }
            }
        }

        Heading("Held-back new apps (${held.size})")
        Note("Apps installed since protection started stay suspended until reviewed. Letting one through is a request that waits; blocking it is instant.")

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
