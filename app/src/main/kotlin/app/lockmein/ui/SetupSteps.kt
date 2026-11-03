package app.lockmein.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.lockmein.AppInfo
import app.lockmein.BuildConfig
import app.lockmein.ChangeManager
import app.lockmein.core.KnownBrowsers
import app.lockmein.core.NightRisk
import app.lockmein.core.ConfigRules
import app.lockmein.core.FriendCode
import app.lockmein.core.LockConfig
import java.time.Instant
import kotlinx.coroutines.delay

/** The apps a setup step can offer, read once from the phone. */
data class SetupApps(val browsers: List<AppInfo>, val launchable: List<AppInfo>, val suggestedNight: Set<String>)

private fun isDeviceOwner(context: Context) = context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)

private fun openSettings(context: Context, action: String) {
    // Some phones (HyperOS among them) hide or rename these pages, so a missing one falls back to Settings itself.
    val opened = runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!opened) runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** One thing to do, with a tick the person sets and a button that opens the page where it is done. */
@Composable
private fun Chore(number: Int, text: String, ticked: Boolean, onTick: (Boolean) -> Unit, button: String? = null, onButton: (() -> Unit)? = null) {
    InfoCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(ticked, onCheckedChange = onTick)
            Text("$number. $text", Modifier.padding(start = 4.dp))
        }
        if (button != null && onButton != null) SecondaryButton(button, onButton)
    }
}

/**
 * Step 1, guided. Android allows the device-owner role only from a computer, so the app cannot do it itself; what it does is take the
 * person to each settings page in turn, show the exact command, and notice by itself the moment the command has worked.
 */
@Composable
fun OwnerStep(onBecameOwner: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val wasOwner = remember { isDeviceOwner(context) }
    var owner by remember { mutableStateOf(wasOwner) }
    var backup by rememberSaveable { mutableStateOf(false) }
    var accounts by rememberSaveable { mutableStateOf(false) }
    var developer by rememberSaveable { mutableStateOf(false) }
    var usb by rememberSaveable { mutableStateOf(false) }
    val command = "adb shell dpm set-device-owner ${context.packageName}/.AdminReceiver"

    // Checks every two seconds, and moves on by itself once the command has worked.
    LaunchedEffect(Unit) {
        if (BuildConfig.PREVIEW || wasOwner) return@LaunchedEffect
        while (!owner) {
            delay(2_000)
            owner = isDeviceOwner(context)
        }
        onBecameOwner()
    }

    Heading("1. Make this app the device owner")
    if (BuildConfig.PREVIEW) {
        InfoCard {
            Text("Preview build")
            Text("This is the preview build, for looking around. It cannot become the device owner and cannot start protection, so this step is skipped. Tap Next and try the other steps; nothing is applied to your phone.")
        }
        return
    }
    Note("Android's own management layer keeps the rules in force even when this app is closed. Only a computer can switch it on, once, over USB. This page walks you through it; it moves on by itself when it works.")
    if (owner) {
        InfoCard { Text("Done: this app is the device owner.") }
        return
    }
    Chore(1, "Back up your phone (photos, files, and move your authenticator codes somewhere safe).", backup, { backup = it })
    Chore(2, "Remove every account (Google, Xiaomi, others) and turn off Second Space and Dual Apps. Android refuses while accounts exist. You add them back afterwards.", accounts, { accounts = it },
        "Open Accounts", { openSettings(context, Settings.ACTION_SYNC_SETTINGS) })
    Chore(3, "Switch on developer options: open About phone and tap the OS or MIUI version seven times.", developer, { developer = it },
        "Open About phone", { openSettings(context, Settings.ACTION_DEVICE_INFO_SETTINGS) })
    Chore(4, "In Developer options turn on USB debugging, and on Xiaomi also \"USB debugging (Security settings)\".", usb, { usb = it },
        "Open Developer options", { openSettings(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) })
    val ready = backup && accounts && developer && usb
    InfoCard {
        Text("5. Plug the phone into a computer that has adb, allow the USB prompt on the phone, and run this one line:")
        Text(command, style = MaterialTheme.typography.bodyMedium)
        SecondaryButton("Copy the command", { clipboard.setText(AnnotatedString(command)) }, enabled = ready)
        if (!ready) Text("Tick the four things above first. Skipping them can lose data or make the command fail.", style = MaterialTheme.typography.bodySmall)
    }
    InfoCard {
        Text("Waiting for the command...")
        Text("This page checks every two seconds. If the computer says \"accounts on the device\" or \"users\", go back to chore 2.", style = MaterialTheme.typography.bodySmall)
        SecondaryButton("Check now", { owner = isDeviceOwner(context); if (owner) onBecameOwner() })
    }
}

@Composable
fun DnsStep(draft: LockConfig, onChange: (LockConfig) -> Unit) {
    Heading("2. Private DNS")
    Note("Every app and browser on the phone asks this server for sites. It blocks adult content before a page loads, and the setting is locked afterwards.")
    DnsFields(draft, onChange)
}

@Composable
fun BrowserStep(draft: LockConfig, apps: SetupApps?, onChange: (LockConfig) -> Unit) {
    Heading("3. Which browser stays open")
    Note("One browser stays open; every other browser (Brave, Mi Browser, Opera, Firefox, the Google app) is closed, because their private windows and own secure-DNS can go around the filter. Chrome is the recommended choice: this app configures it so private windows are off, its own secure DNS is off, safe search is forced and YouTube restricted mode is strict. A closed browser can be requested later; it waits 7 days and opens in the weekly window.")
    if (apps == null) return Note("Reading your apps...")
    ChoiceRow("No browser stays open", draft.allowedBrowser == null) { onChange(draft.copy(allowedBrowser = null)) }
    apps.browsers.sortedByDescending { it.packageName == KnownBrowsers.CHROME }.forEach { app ->
        ChoiceRow("${app.label}${if (app.packageName == KnownBrowsers.CHROME) " (recommended)" else ""}", draft.allowedBrowser == app.packageName) {
            onChange(draft.copy(allowedBrowser = app.packageName, alwaysBlocked = draft.alwaysBlocked - app.packageName))
        }
    }
}

@Composable
fun NightStep(draft: LockConfig, apps: SetupApps?, onChange: (LockConfig) -> Unit) {
    Heading("4. Apps that may run at night")
    Note("From the night start until fajr only these apps and the phone's essentials (calls, messages, keyboard, settings) work. Pick your music player, alarm clock and authenticator. Pick audio-only apps: music apps that also play video (YouTube Music, Spotify, Deezer) are a way back to video, so the best choice is an offline audio player with downloaded files. Adding an app later waits 7 days and applies in the weekly window.")
    if (apps == null) return Note("Reading your apps...")
    apps.launchable.forEach { app ->
        val on = app.packageName in draft.nightAllowlist
        Row(Modifier.fillMaxWidth().selectable(on, onClick = { onChange(toggle(draft, app.packageName, !on)) }), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(on, onCheckedChange = { onChange(toggle(draft, app.packageName, it)) })
            Column(Modifier.padding(start = 4.dp)) {
                Text(app.label)
                NightRisk.warning(app.packageName)?.let { Text("Careful: $it", style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun toggle(draft: LockConfig, packageName: String, on: Boolean) =
    draft.copy(nightAllowlist = if (on) draft.nightAllowlist + packageName else draft.nightAllowlist - packageName, alwaysBlocked = draft.alwaysBlocked - packageName)

@Composable
fun RhythmStep(draft: LockConfig, onChange: (LockConfig) -> Unit) {
    Heading("5. Rhythm and place")
    RhythmFields(draft, onChange)
    LocationFields(draft, onChange)
}

@Composable
fun FriendStep(manager: ChangeManager, verified: Boolean, onVerified: () -> Unit) {
    val scope = rememberCoroutineScope()
    var secret by remember { mutableStateOf<ByteArray?>(null) }
    var typed by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    Heading("6. The friend's code")
    Note("A friend holds the key to emergencies and to taking the protection off. Do this step with your friend next to you. The app shows a QR code once; your friend scans it with an authenticator app on their own phone. There is no text to copy and no link to send, and the screen cannot be captured. The app keeps the secret where you cannot read it back.")
    val shown = secret
    if (shown == null) {
        SecondaryButton(if (manager.hasFriendSecret) "Make a new secret (replaces the old one)" else "Make the friend's secret", { secret = manager.newFriendSecret() })
        if (manager.hasFriendSecret && !verified) Note("A secret exists but was not confirmed with your friend yet. Make a new one to confirm it.")
        return
    }
    SecretScreen()
    QrImage(FriendCode.otpauthUri(shown))
    Note("Your friend scans this in their authenticator app, then reads you the current 6-digit code. Type it here. This proves it reached them; the QR code disappears once it matches.")
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it.filter(Char::isDigit).take(6); wrong = false },
        label = { Text("Code from your friend") },
        isError = wrong,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (wrong) Problem("That code does not match. Check the authenticator entry and try the next code.")
    if (verified) Note("Confirmed: your friend's code works.")
    SecondaryButton("Check the code", {
        scope.background({ manager.verifyEnrolment(typed, Instant.now()) }) { ok ->
            wrong = !ok
            if (ok) {
                secret = null
                onVerified()
            }
        }
    }, enabled = typed.length == 6)
}

@Composable
fun ReviewStep(draft: LockConfig, apps: SetupApps?, friendVerified: Boolean, outcome: app.lockmein.Outcome?, working: Boolean, onStart: () -> Unit) {
    val problems = ConfigRules.problems(draft)
    fun label(pkg: String) = apps?.launchable?.firstOrNull { it.packageName == pkg }?.label ?: pkg
    Heading("7. Review and start")
    InfoCard {
        Text("DNS: ${draft.dnsHost}")
        Text("Browser: ${draft.allowedBrowser?.let(::label) ?: "none stays open"}")
        Text("Night apps: ${draft.nightAllowlist.joinToString { label(it) }.ifBlank { "none" }}")
        Text("Night starts ${draft.nightStart}; weekly window on day ${draft.windowDay} (1 = Monday) ${draft.windowStart} to ${draft.windowEnd}")
        Text("Fajr from ${draft.latitude}, ${draft.longitude} at ${draft.sunAngleDegrees} degrees")
        Text("Friend's code: ${if (friendVerified) "confirmed" else "not confirmed"}")
        Text(if (BuildConfig.TEST_BUILD) "Build: test (can be removed over USB)" else "Build: sealed (USB debugging and factory reset are blocked)")
    }
    problems.forEach { Problem(it) }
    if (!friendVerified) Problem("Confirm the friend's code first.")
    Note("After you start, anything that opens the phone up is a request: it waits 7 days, then you carry it out in the weekly window. Closing things works at once. Your friend's code unblocks one app for an hour in a real emergency, and changes nothing else. Adding accounts back is fine.")
    OutcomeText(outcome)
    MenuButton(if (working) "Starting..." else "Start protection", enabled = !working && problems.isEmpty() && friendVerified, onClick = onStart)
}

@Composable
fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, onClick = onSelect), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
        RadioButton(selected, onClick = onSelect)
        Text(label, Modifier.padding(start = 4.dp))
    }
}
