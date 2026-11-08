package app.lockmein.ui

import android.app.admin.DevicePolicyManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

private const val LAST_STEP = 7

private val STEP_TITLES = listOf(
    "Device owner",
    "Private DNS",
    "Browser",
    "Night apps",
    "Rhythm and place",
    "Friend's code",
    "Screen filter",
    "Review and start",
)

@Composable
fun SetupScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ConfigStore(context) }
    val manager = remember { ChangeManager(context) }
    val scope = rememberCoroutineScope()
    val navigate = LocalNavigate.current

    val redo = remember { store.redoing }
    var step by rememberSaveable {
        mutableIntStateOf(
            if (redo) 1
            else if (BuildConfig.PREVIEW) {
                ((context as? android.app.Activity)?.intent?.getIntExtra("step", 0) ?: 0).coerceIn(0, LAST_STEP)
            } else 0,
        )
    }
    var draft by remember { mutableStateOf(store.load() ?: LockConfig(dnsHost = "")) }
    var friendVerified by rememberSaveable { mutableStateOf(redo) }
    var dnsChecked by rememberSaveable { mutableStateOf<String?>(null) }
    var browserChosen by rememberSaveable { mutableStateOf(redo) }
    var filterEnabled by remember { mutableStateOf(app.lockmein.FilterService.isEnabled(context)) }
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

    LaunchedEffect(apps) {
        val suggested = apps?.suggestedNight.orEmpty()
        if (suggested.isNotEmpty() && draft.nightClosed.isEmpty() && store.load() == null) {
            draft = draft.copy(nightClosed = suggested)
        }
    }

    val owner = context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)
    val canContinue = when (step) {
        0 -> owner || BuildConfig.PREVIEW
        1 -> DnsHost.problem(draft.dnsHost) == null && dnsChecked == draft.dnsHost
        2 -> browserChosen
        4 -> ConfigRules.problems(draft.copy(dnsHost = "valid.example.com")).isEmpty()
        5 -> friendVerified
        6 -> filterEnabled || BuildConfig.PREVIEW
        else -> true
    }

    val hint = when (step) {
        0 -> "This step moves on by itself once the computer command succeeds."
        1 -> if (DnsHost.problem(draft.dnsHost) != null) "Enter a valid private DNS host to continue." else "Next unlocks once the DNS server answers."
        2 -> "Pick the browser that stays open, or choose none."
        4 -> "Fix any invalid fields above to continue."
        5 -> "Verify the code with your friend to continue."
        6 -> "Turn on the screen filter accessibility service to continue."
        else -> null
    }

    Page(
        title = "Setup: ${STEP_TITLES[step]}",
        onBack = { if (step == 0 || (redo && step == 1)) onExit() else step = previous(step, redo) },
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!canContinue && hint != null) {
                        Text(
                            hint,
                            color = Palette.Attention,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (step < LAST_STEP) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (step > 0 && !(redo && step == 1)) {
                                SecondaryButtonSmall("Back") { step = previous(step, redo) }
                            }
                            Button(
                                onClick = {
                                    if (DnsHost.problem(draft.dnsHost) == null) store.save(draft)
                                    step = if (redo && step == 4) LAST_STEP else step + 1
                                },
                                enabled = canContinue,
                                shape = ButtonShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                            ) {
                                Text("Next", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        },
    ) {
        // Stepper: "Step X of 7" + thin linear progress
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Step ${step + 1} of ${LAST_STEP + 1}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            LinearProgressIndicator(
                progress = { (step + 1f) / (LAST_STEP + 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }

        when (step) {
            0 -> OwnerStep { ownerChecked++; if (step == 0) step = 1 }
            1 -> DnsStep(draft, { draft = it }, { dnsChecked = it })
            2 -> BrowserStep(draft, apps, { draft = it }, { browserChosen = true })
            3 -> NightStep(draft, apps) { draft = it }
            4 -> RhythmStep(draft) { draft = it }
            5 -> FriendStep(manager, friendVerified) { friendVerified = true }
            6 -> FilterStep { filterEnabled = true }
            else -> ReviewStep(draft, apps, friendVerified, filterEnabled || BuildConfig.PREVIEW, outcome, working) {
                working = true
                outcome = null
                store.save(draft)
                afterStart = null
                scope.background({
                    manager.startProtection().let { it to if (it is Outcome.Done) app.lockmein.Health(context).check() else null }
                }) { (result, health) ->
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

        ResetSetup(onReset = {
            step = 0
            draft = LockConfig(dnsHost = "")
            friendVerified = false
            dnsChecked = null
            browserChosen = false
            afterStart = null
            outcome = null
        })

        afterStart?.filter { !it.ok }?.takeIf { it.isNotEmpty() && !BuildConfig.PREVIEW }?.let { failed ->
            Heading("Not in force yet")
            failed.forEach { CheckRow(it.title, CheckState.FAIL, it.detail) }
            SecondaryButton("Open the full check", { navigate(Screen.HEALTH) })
        }
    }
}

private fun previous(step: Int, redo: Boolean) = if (redo && step == LAST_STEP) 4 else step - 1
