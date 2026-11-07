package app.lockmein.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import app.lockmein.ChangeManager
import app.lockmein.ConfigStore
import app.lockmein.DeviceInventory
import app.lockmein.LockState
import app.lockmein.Outcome
import app.lockmein.core.KnownBrowsers

/** The closed browsers. */
@Composable
fun BrowsersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { ChangeManager(context) }
    val store = remember { ConfigStore(context) }
    val state = remember { LockState(context) }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    val config = store.load() ?: return Page("Browsers", onBack) { Note("Finish the setup first.") }

    val inventory = remember { DeviceInventory(context) }
    val snapshot = remember(version) { inventory.read() }
    val closed = ((snapshot.browsers + KnownBrowsers.ALL).filter { it in snapshot.installed } - setOfNotNull(config.allowedBrowser) - state.approvedBrowsers).sorted()

    Page("Browsers", onBack) {
        AppCard {
            Text("Browser policy", style = MaterialTheme.typography.titleMedium)
            Text(
                "Open: ${config.allowedBrowser?.let(inventory::label) ?: "None"}. Other browsers are closed to prevent filter bypass. Approving another is allowed during the Sunday settings window (20:00 to 21:00) or using a holder code session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutcomeText(outcome)

        if (closed.isEmpty()) {
            AppCard {
                Text("No other browser is installed and closed.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        closed.forEach { pkg ->
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(pkg, size = 40.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(inventory.label(pkg), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Closed browser",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        scope.background({ manager.approveBrowser(pkg) }) {
                            outcome = it
                            version++
                        }
                    }) {
                        Text("Approve")
                    }
                }
            }
        }
    }
}
