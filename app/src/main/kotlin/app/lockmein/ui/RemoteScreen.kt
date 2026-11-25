package app.lockmein.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import app.lockmein.Outcome
import app.lockmein.RemoteLink
import kotlinx.coroutines.delay

/** Connects the phone to the Lock Me In relay so the MCP can read it and ask for changes, under the app's own rules. */
@Composable
fun RemoteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    val paired = remember(version) { RemoteLink.isPaired(context) }
    var url by remember { mutableStateOf(RemoteLink.server(context) ?: "") }
    var code by remember { mutableStateOf("") }
    // The link's state changes on its own (connects, drops, reconnects): refreshed every few seconds while the page is open.
    LaunchedEffect(Unit) { while (true) { delay(3_000); version++ } }

    Page("Connect Claude", onBack) {
        InfoCard {
            Text(
                when {
                    !paired -> "Not connected."
                    RemoteLink.connected -> "Connected to ${RemoteLink.server(context)}."
                    else -> "Connecting to ${RemoteLink.server(context)}..." + (RemoteLink.lastError?.let { " Last problem: $it." } ?: "")
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Note("Through the MCP, Claude can read phone use, the reports, the requests and the filter's blocks, and ask for the same changes you can make here. Closing more applies at once; opening anything up becomes a request for the Sunday review. No friend's code, no removal. Each change is logged.")
        }
        OutcomeText(outcome)
        if (!paired) {
            OutlinedTextField(url, { url = it }, label = { Text("Server, e.g. lockmein.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(code, { code = it }, label = { Text("Pair code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            PrimaryButton("Connect", enabled = url.isNotBlank() && code.length >= 8) {
                scope.background({ runCatching { RemoteLink.pair(context, url, code) } }) { r ->
                    outcome = r.fold({ Outcome.Done("Paired. The link stays open in the background.") }, { Outcome.Refused("Could not pair: ${it.message}") })
                    code = ""
                    version++
                }
            }
        } else {
            SecondaryButton("Disconnect", {
                RemoteLink.disconnect(context)
                outcome = Outcome.Done("Disconnected. The server can no longer reach this phone.")
                version++
            })
        }
    }
}
