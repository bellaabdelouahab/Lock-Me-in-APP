package app.lockmein.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
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

    var hideRecents by remember { mutableStateOf(app.lockmein.Recents.hidden(context)) }

    Page("Settings", onBack) {
        Heading("Updates")
        UpdateCard()
        Heading("Recent apps")
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Hide from recent apps", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (store.protectionActive) "Lock Me In stays out of the recent apps screen, so it can't be swiped away or opened into App info. It can't be switched off while protection is on."
                        else "Lock Me In stays out of the recent apps screen, so it can't be swiped away or opened into App info.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                    )
                }
                Switch(hideRecents, onCheckedChange = { want ->
                    if (app.lockmein.Recents.setHidden(context, want)) {
                        hideRecents = want
                        (context as? android.app.Activity)?.let(app.lockmein.Recents::apply)
                    } else outcome = Outcome.Refused("Hiding from recent apps is part of the protection, so it stays on until the protection is removed.")
                })
            }
        }
        Heading("Rules")
        Note(if (store.protectionActive) "Changes here open the phone up, so they work only in the open window or with an emergency." else "Protection is not on yet, so everything can change freely.")
        DnsFields(current) { draft = it }
        RhythmFields(current) { draft = it }
        LocationFields(current) { draft = it }
        OutcomeText(outcome)
        MenuButton("Save settings") {
            scope.background({ manager.saveSettings(current) }) { outcome = it }
        }

        RedoSetup()

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
