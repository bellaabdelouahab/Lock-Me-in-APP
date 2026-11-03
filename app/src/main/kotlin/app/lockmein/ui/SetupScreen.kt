package app.lockmein.ui

import android.app.admin.DevicePolicyManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.lockmein.BuildConfig
import app.lockmein.ChangeManager
import app.lockmein.ConfigStore
import app.lockmein.DeviceInventory
import app.lockmein.Outcome
import app.lockmein.core.ConfigRules
import app.lockmein.core.DnsHost
import app.lockmein.core.LockConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LAST_STEP = 6

private val STEP_TITLES = listOf("Device owner", "Private DNS", "Browser", "Night apps", "Rhythm and place", "Friend's code", "Review and start")

/** The first-time setup: seven steps, each saved as you go. Nothing is applied to the phone until the last button. */
@Composable
fun SetupScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ConfigStore(context) }
    val manager = remember { ChangeManager(context) }
    val scope = rememberCoroutineScope()
    val navigate = LocalNavigate.current

    // The preview build can open on a given step (adb shell am start --ei step 3), which is how the screens are photographed.
    var step by rememberSaveable { mutableIntStateOf(if (BuildConfig.PREVIEW) ((context as? android.app.Activity)?.intent?.getIntExtra("step", 0) ?: 0).coerceIn(0, LAST_STEP) else 0) }
    var draft by remember { mutableStateOf(store.load() ?: LockConfig(dnsHost = "")) }
    var friendVerified by rememberSaveable { mutableStateOf(false) }
    var ownerChecked by remember { mutableIntStateOf(0) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var working by remember { mutableStateOf(false) }

    val apps by produceState<SetupApps?>(null) {
        value = withContext(Dispatchers.IO) {
            val inventory = DeviceInventory(context)
            val browsers = inventory.read().browsers.map { app.lockmein.AppInfo(it, inventory.label(it)) }.sortedBy { it.label.lowercase() }
            SetupApps(browsers, inventory.launchableApps(), inventory.suggestedNightApps())
        }
    }
    // On a first setup, pre-tick the clock and authenticator apps once the list of apps is known.
    LaunchedEffect(apps) {
        val suggested = apps?.suggestedNight.orEmpty()
        if (suggested.isNotEmpty() && draft.nightAllowlist.isEmpty() && store.load() == null) draft = draft.copy(nightAllowlist = suggested)
    }

    val owner = context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)
    val canContinue = when (step) {
        0 -> owner || BuildConfig.PREVIEW
        1 -> DnsHost.problem(draft.dnsHost) == null
        // Everything but the DNS host, which has its own step, must be valid.
        4 -> ConfigRules.problems(draft.copy(dnsHost = "valid.example.com")).isEmpty()
        5 -> friendVerified
        else -> true
    }

    val hint = when (step) {
        0 -> "This page moves on by itself once the computer command has worked."
        1 -> "Enter a valid private DNS host to continue."
        4 -> "Fix the highlighted fields to continue."
        5 -> "Enter your friend's code to continue."
        else -> null
    }

    Page(
        "Set up (${step + 1} of ${LAST_STEP + 1})",
        onBack = { if (step == 0) onExit() else step-- },
        bottomBar = {
            Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        STEP_TITLES.indices.forEach { index ->
                            Box(Modifier.padding(3.dp).size(if (index == step) 10.dp else 7.dp).clip(CircleShape).background(if (index <= step) Palette.Rule else Palette.Muted.copy(alpha = 0.4f)))
                        }
                    }
                    if (step < LAST_STEP) {
                        if (!canContinue && hint != null) Text(hint, color = Palette.Open, style = MaterialTheme.typography.bodySmall)
                        ButtonRow {
                            if (step > 0) OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Back") }
                            Button(
                                onClick = {
                                    if (DnsHost.problem(draft.dnsHost) == null) store.save(draft)
                                    step++
                                },
                                enabled = canContinue,
                                modifier = Modifier.weight(1f).height(48.dp),
                            ) { Text("Next") }
                        }
                    }
                }
            }
        },
    ) {
        LinearProgressIndicator(progress = { (step + 1f) / (LAST_STEP + 1) }, modifier = Modifier.fillMaxWidth(), color = Palette.Rule, trackColor = Palette.Card)
        Text(STEP_TITLES[step], color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        when (step) {
            0 -> OwnerStep { ownerChecked++; if (step == 0) step = 1 }
            1 -> DnsStep(draft) { draft = it }
            2 -> BrowserStep(draft, apps) { draft = it }
            3 -> NightStep(draft, apps) { draft = it }
            4 -> RhythmStep(draft) { draft = it }
            5 -> FriendStep(manager, friendVerified) { friendVerified = true }
            else -> ReviewStep(draft, apps, friendVerified, outcome, working) {
                working = true
                outcome = null
                store.save(draft)
                scope.background({ manager.startProtection() }) { result ->
                    working = false
                    outcome = result
                    if (result is Outcome.Done) navigate(Screen.HOME)
                }
            }
        }
    }
}
