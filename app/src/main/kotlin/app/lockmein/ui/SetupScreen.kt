package app.lockmein.ui

import android.app.admin.DevicePolicyManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxWidth
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

    var step by rememberSaveable { mutableIntStateOf(0) }
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

    Page("Set up Lock Me In (step ${step + 1} of ${LAST_STEP + 1})", onBack = { if (step == 0) onExit() else step-- }) {
        StepProgress(step, doneThrough = if (owner || BuildConfig.PREVIEW) maxOf(step - 1, 0) else step - 1)
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
        if (step < LAST_STEP) {
            if (!canContinue) Note(when (step) {
                0 -> "This page moves on by itself once the computer command has worked."
                1 -> "Enter a valid private DNS host to continue."
                4 -> "Fix the highlighted fields to continue."
                else -> "Finish this step to continue."
            })
            MenuButton("Next", enabled = canContinue) {
                if (DnsHost.problem(draft.dnsHost) == null) store.save(draft)
                step++
            }
        }
    }
}

/** A bar and the seven step names, so the person always sees where they are and what is left. */
@androidx.compose.runtime.Composable
private fun StepProgress(step: Int, doneThrough: Int) {
    androidx.compose.material3.LinearProgressIndicator(progress = { (step + 1f) / (LAST_STEP + 1) }, modifier = androidx.compose.ui.Modifier.fillMaxWidth())
    STEP_TITLES.forEachIndexed { index, title ->
        val mark = when {
            index <= doneThrough && index < step -> "done"
            index == step -> "now"
            else -> "later"
        }
        androidx.compose.material3.Text(
            "${index + 1}. $title  ($mark)",
            style = if (index == step) androidx.compose.material3.MaterialTheme.typography.titleSmall else androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )
    }
}
