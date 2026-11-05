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
import androidx.compose.foundation.layout.navigationBarsPadding
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
    // A redo of a completed setup skips the computer step and the friend's code: both are already done.
    val redo = remember { store.redoing }
    var step by rememberSaveable { mutableIntStateOf(if (redo) 1 else if (BuildConfig.PREVIEW) ((context as? android.app.Activity)?.intent?.getIntExtra("step", 0) ?: 0).coerceIn(0, LAST_STEP) else 0) }
    var draft by remember { mutableStateOf(store.load() ?: LockConfig(dnsHost = "")) }
    var friendVerified by rememberSaveable { mutableStateOf(redo) }
    // The host the DNS step reached for real (null until it answers), and whether a browser was picked on purpose.
    var dnsChecked by rememberSaveable { mutableStateOf<String?>(null) }
    var browserChosen by rememberSaveable { mutableStateOf(redo) }
    var afterStart by remember { mutableStateOf<List<app.lockmein.HealthItem>?>(null) }
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
    // On a first setup, pre-tick the feed and video apps once the list of apps is known.
    LaunchedEffect(apps) {
        val suggested = apps?.suggestedNight.orEmpty()
        if (suggested.isNotEmpty() && draft.nightClosed.isEmpty() && store.load() == null) draft = draft.copy(nightClosed = suggested)
    }

    val owner = context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)
    val canContinue = when (step) {
        0 -> owner || BuildConfig.PREVIEW
        1 -> DnsHost.problem(draft.dnsHost) == null && dnsChecked == draft.dnsHost
        2 -> browserChosen
        // Everything but the DNS host, which has its own step, must be valid.
        4 -> ConfigRules.problems(draft.copy(dnsHost = "valid.example.com")).isEmpty()
        5 -> friendVerified
        else -> true
    }

    val hint = when (step) {
        0 -> "This page moves on by itself once the computer command has worked."
        1 -> if (DnsHost.problem(draft.dnsHost) != null) "Enter a private DNS host to continue." else "Next unlocks once the DNS server answers."
        2 -> "Pick the browser that stays open, or none."
        4 -> "Fix the highlighted fields to continue."
        5 -> "Enter your friend's code to continue."
        else -> null
    }

    Page(
        "Setup",
        onBack = { if (step == 0 || (redo && step == 1)) onExit() else step = previous(step, redo) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(Palette.Paper).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Rule()
                    // Seven square segments: filled for the steps done, the current one in the signal colour.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        STEP_TITLES.indices.forEach { index ->
                            Box(Modifier.weight(1f).height(6.dp).background(when { index < step -> Palette.Ink; index == step -> Palette.Signal; else -> Palette.Line }))
                        }
                    }
                    if (step < LAST_STEP) {
                        if (!canContinue && hint != null) Text(hint, color = Palette.Signal, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        ButtonRow {
                            if (step > 0 && !(redo && step == 1)) SecondaryButtonSmall("Back") { step = previous(step, redo) }
                            Button(
                                shape = androidx.compose.ui.graphics.RectangleShape,
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Palette.Ink, contentColor = Palette.Paper, disabledContainerColor = Palette.Line, disabledContentColor = Palette.Muted),
                                onClick = {
                                    if (DnsHost.problem(draft.dnsHost) == null) store.save(draft)
                                    step = if (redo && step == 4) LAST_STEP else step + 1
                                },
                                enabled = canContinue,
                                modifier = Modifier.weight(1f),
                            ) { Text("NEXT  \u2192", style = MaterialTheme.typography.labelLarge) }
                        }
                    }
            }
        },
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("0${step + 1}", style = MaterialTheme.typography.displayMedium, color = Palette.Signal)
            Text("/ 0${LAST_STEP + 1}  ${STEP_TITLES[step].uppercase()}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 10.dp))
        }
        when (step) {
            0 -> OwnerStep { ownerChecked++; if (step == 0) step = 1 }
            1 -> DnsStep(draft, { draft = it }, { dnsChecked = it })
            2 -> BrowserStep(draft, apps, { draft = it }, { browserChosen = true })
            3 -> NightStep(draft, apps) { draft = it }
            4 -> RhythmStep(draft) { draft = it }
            5 -> FriendStep(manager, friendVerified) { friendVerified = true }
            else -> ReviewStep(draft, apps, friendVerified, outcome, working) {
                working = true
                outcome = null
                store.save(draft)
                afterStart = null
                // Started is not enough: Android is asked whether every rule really holds before the setup says it is done.
                scope.background({ manager.startProtection().let { it to if (it is Outcome.Done) app.lockmein.Health(context).check() else null } }) { (result, health) ->
                    working = false
                    outcome = result
                    afterStart = health
                    if (result is Outcome.Done && (BuildConfig.PREVIEW || health.orEmpty().all { it.ok })) {
                        store.setupComplete = true
                        navigate(Screen.HOME)
                    }
                }
            }
        }
        ResetSetup(onReset = { step = 0; draft = LockConfig(dnsHost = ""); friendVerified = false; dnsChecked = null; browserChosen = false; afterStart = null; outcome = null })
        afterStart?.filter { !it.ok }?.takeIf { it.isNotEmpty() && !BuildConfig.PREVIEW }?.let { failed ->
            Heading("Not in force yet")
            failed.forEach { CheckRow(it.title, CheckState.FAIL, it.detail) }
            SecondaryButton("Open the full check", { navigate(Screen.HEALTH) })
        }
    }
}

private fun previous(step: Int, redo: Boolean) = if (redo && step == LAST_STEP) 4 else step - 1
