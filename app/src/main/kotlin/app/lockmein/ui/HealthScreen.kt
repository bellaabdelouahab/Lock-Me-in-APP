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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lockmein.BuildConfig
import app.lockmein.ConfigStore
import app.lockmein.Health
import app.lockmein.HealthItem
import app.lockmein.PolicyRunner
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val AT = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
fun HealthScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<HealthItem>?>(null) }
    var checkedAt by remember { mutableStateOf<Instant?>(null) }
    var repairing by remember { mutableStateOf(false) }
    var repairNote by remember { mutableStateOf<String?>(null) }

    fun recheck() {
        items = null
        scope.background({ Health(context).check() }) {
            items = it
            checkedAt = Instant.now()
        }
    }

    LaunchedEffect(Unit) { recheck() }

    val failing = items?.filter { !it.ok }.orEmpty()
    val hasFailing = failing.isNotEmpty()

    Page(
        title = "Is it working?",
        onBack = onBack,
        actions = {
            IconButton(onClick = { recheck() }, enabled = items != null) {
                Icon(Icons.Filled.Refresh, contentDescription = "Check again")
            }
        },
        bottomBar = {
            if (hasFailing && !BuildConfig.PREVIEW && ConfigStore(context).protectionActive) {
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
                            label = if (repairing) "Repairing..." else "Repair now",
                            enabled = !repairing,
                            onClick = {
                                repairing = true
                                repairNote = null
                                scope.background({
                                    runCatching {
                                        ConfigStore(context).load()?.let { PolicyRunner(context).run(it) }
                                    }.exceptionOrNull()?.message
                                }) { error ->
                                    repairing = false
                                    if (error != null) repairNote = "Repair failed: $error"
                                    recheck()
                                }
                            },
                        )
                    }
                }
            }
        },
    ) {
        val list = items
        val total = list?.size ?: 0
        val passed = list?.count { it.ok } ?: 0

        // Status Card
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (list == null) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Asking Android...", style = MaterialTheme.typography.titleMedium)
                } else if (hasFailing) {
                    Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Palette.Attention, modifier = Modifier.size(22.dp))
                    Text(
                        if (failing.size == 1) "1 item needs attention" else "${failing.size} items need attention",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    StatusBadge(StatusKind.ATTENTION, "Attention")
                } else {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Palette.Ok, modifier = Modifier.size(22.dp))
                    Text("All rules verified", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    StatusBadge(StatusKind.OK, "OK ($passed/$total)")
                }
            }
            Text(
                "Each line is read back from Android itself, not from the app's internal notes. If a rule was undone, it shows here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            checkedAt?.let {
                Text(
                    "Checked at ${AT.format(it.atZone(ZoneId.systemDefault()))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        repairNote?.let { Problem(it) }

        Heading("Checks")

        if (list == null) {
            CheckRow("Asking Android", CheckState.CHECKING, "Reading what is really in force...")
        } else {
            // Failing ones first, verified after
            val sortedList = list.sortedBy { if (it.ok) 1 else 0 }
            sortedList.forEach { item ->
                CheckRow(
                    title = item.title,
                    state = if (item.ok) CheckState.PASS else CheckState.FAIL,
                    detail = item.detail,
                )
            }
        }
    }
}
