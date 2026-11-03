package app.lockmein.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
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

/** The apps a setup step can offer, read once from the phone. */
data class SetupApps(val browsers: List<AppInfo>, val launchable: List<AppInfo>, val suggestedNight: Set<String>)

private fun isDeviceOwner(context: Context) = context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)

@Composable
fun OwnerStep(onChanged: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var owner by remember { mutableStateOf(isDeviceOwner(context)) }
    val command = "adb shell dpm set-device-owner ${context.packageName}/.AdminReceiver"
    Heading("1. Make this app the device owner")
    if (BuildConfig.PREVIEW) {
        Note("This is the preview build. Look around, change the settings, try the steps. It cannot become the device owner and cannot start protection. Do not run the adb command for it: install the test build for the real thing.")
        return
    }
    Note("Android's own management layer keeps the rules in force even when this app is closed. It can only be switched on from a computer, once, over USB.")
    if (owner) {
        Note("Done: this app is the device owner.")
        return
    }
    Note("Before you start: back up your phone, remove every account (Settings, Accounts) and turn off Second Space and Dual Apps. Android refuses while accounts exist. You add them back afterwards.")
    Note("Then turn on USB debugging (on Xiaomi also \"USB debugging (Security settings)\"), plug the phone into a computer with adb installed and run:")
    InfoCard { Text(command, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium) }
    SecondaryButton("Copy the command", { clipboard.setText(AnnotatedString(command)) })
    SecondaryButton("I ran it: check again", { owner = isDeviceOwner(context); onChanged() })
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
        Text(if (BuildConfig.DEBUG) "Build: test (can be removed over USB)" else "Build: sealed (USB debugging and factory reset are blocked)")
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
