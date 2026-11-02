package app.lockmein.ui

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.lockmein.ChangeManager
import app.lockmein.ConfigStore
import app.lockmein.Outcome
import app.lockmein.core.ConfigCodec
import app.lockmein.core.LockConfig

/** DNS, rhythm and place, plus export and import so a setup can be shared. After protection starts every change needs the open window. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ConfigStore(context) }
    val manager = remember { ChangeManager(context) }
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(store.load()) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var importText by remember { mutableStateOf("") }
    val current = draft ?: return Page("Settings", onBack) { Note("Finish the setup first.") }

    Page("Settings", onBack) {
        Note(if (store.protectionActive) "Changes here open the phone up, so they work only in the open window or with an emergency." else "Protection is not on yet, so everything can change freely.")
        DnsFields(current) { draft = it }
        RhythmFields(current) { draft = it }
        LocationFields(current) { draft = it }
        OutcomeText(outcome)
        MenuButton("Save settings") {
            scope.background({ manager.saveSettings(current) }) { outcome = it }
        }

        Heading("Share this setup")
        Note("The exported text holds your settings only: no secrets, no friend's code. Someone else can import it, then do their own friend's code.")
        SecondaryButton("Export settings", {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ConfigCodec.encode(current))
            context.startActivity(Intent.createChooser(send, "Share settings").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        OutlinedTextField(
            value = importText,
            onValueChange = { importText = it },
            label = { Text("Paste settings to import") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        SecondaryButton("Import settings", {
            val imported: Result<LockConfig> = runCatching { ConfigCodec.decode(importText) }
            imported.onSuccess { draft = it; outcome = Outcome.Done("Imported. Review the fields above, then Save settings.") }
            imported.onFailure { outcome = Outcome.Refused(it.message ?: "Could not read those settings.") }
        }, enabled = importText.isNotBlank())
    }
}
