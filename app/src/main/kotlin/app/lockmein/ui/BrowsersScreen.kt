package app.lockmein.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.lockmein.ChangeManager
import app.lockmein.ConfigStore
import app.lockmein.DeviceInventory
import app.lockmein.LockState
import app.lockmein.Outcome
import app.lockmein.Rules
import app.lockmein.core.BrowserApproval
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

/** The closed browsers: ask for one and it opens after the wait, if that moment falls in the day. */
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
    val closed = (snapshot.browsers - setOfNotNull(config.allowedBrowser) - state.approvedBrowsers).sorted()
    val approval = BrowserApproval(Rules.schedule(config), Duration.ofMinutes(config.browserWaitMinutes.toLong()))
    val zone = ZoneId.systemDefault()

    Page("Browsers", onBack) {
        Note("Open: ${config.allowedBrowser?.let(inventory::label) ?: "none"}. Others are closed. Ask for one and it opens ${config.browserWaitMinutes} minutes later, only if that falls before ${config.daytimeEnd}; otherwise at the next fajr.")
        OutcomeText(outcome)
        if (closed.isEmpty()) Note("No other browser is closed.")
        closed.forEach { pkg ->
            InfoCard {
                Text(inventory.label(pkg))
                val requestedAt = state.browserRequests[pkg]
                if (requestedAt == null) {
                    SecondaryButton("Request this browser", {
                        scope.background({ manager.requestBrowser(pkg) }) {
                            outcome = it
                            version++
                        }
                    })
                } else {
                    Text("Requested. Opens ${TIME.format(approval.approvalTime(requestedAt).atZone(zone))}.")
                }
            }
        }
    }
}
