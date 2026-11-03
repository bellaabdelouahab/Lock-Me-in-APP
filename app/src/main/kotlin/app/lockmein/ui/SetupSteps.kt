package app.lockmein.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lockmein.AppInfo
import app.lockmein.BuildConfig
import app.lockmein.ChangeManager
import app.lockmein.core.ConfigRules
import app.lockmein.core.FriendCode
import app.lockmein.core.KnownBrowsers
import app.lockmein.core.LockConfig
import app.lockmein.core.NightRisk
import java.time.Instant
import kotlinx.coroutines.delay

/** The apps a setup step can offer, read once from the phone. */
data class SetupApps(val browsers: List<AppInfo>, val launchable: List<AppInfo>, val suggestedNight: Set<String>)

private fun isDeviceOwner(context: Context) = context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)

/** The top of every step: a big icon, the step's name, and one sentence about it. */
@Composable
fun StepHeader(icon: ImageVector, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IconCircle(icon, Palette.Bar, 72.dp)
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

private fun openSettings(context: Context, action: String) {
    // Some phones (HyperOS among them) hide or rename these pages, so a missing one falls back to Settings itself.
    val opened = runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!opened) runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** One thing to do: a numbered circle that turns into a tick, the instruction, and a button to the page where it is done. */
@Composable
private fun Chore(number: Int, text: String, ticked: Boolean, onTick: (Boolean) -> Unit, button: String? = null, onButton: (() -> Unit)? = null) {
    InfoCard {
        Row(Modifier.fillMaxWidth().clickable { onTick(!ticked) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(if (ticked) Palette.Day else Palette.Bar), contentAlignment = Alignment.Center) {
                if (ticked) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) else Text("$number", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
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

    StepHeader(Icons.Filled.Usb, "Make this app the device owner", "Android's own management layer keeps the rules in force, even when this app is closed. Only a computer can switch it on, once, over USB.")
    if (BuildConfig.PREVIEW) {
        InfoCard {
            Text("Preview build", fontWeight = FontWeight.SemiBold)
            Text("This build is for looking around. It cannot become the device owner and applies nothing to your phone, so this step is skipped. Tap Next to see the rest of the setup.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    if (owner) {
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.CheckCircle, null, tint = Palette.Day, modifier = Modifier.size(32.dp))
                Text("This app is the device owner.", fontWeight = FontWeight.SemiBold)
            }
        }
        return
    }
    Chore(1, "Back up your phone. Photos, files, and move your authenticator codes somewhere safe.", backup, { backup = it })
    Chore(2, "Remove every account (Google, Xiaomi, others) and turn off Second Space and Dual Apps. Android refuses while accounts exist. You add them back afterwards.", accounts, { accounts = it },
        "Open Accounts", { openSettings(context, Settings.ACTION_SYNC_SETTINGS) })
    Chore(3, "Switch on developer options: open About phone and tap the OS or MIUI version seven times.", developer, { developer = it },
        "Open About phone", { openSettings(context, Settings.ACTION_DEVICE_INFO_SETTINGS) })
    Chore(4, "In Developer options turn on USB debugging. On Xiaomi also turn on \"USB debugging (Security settings)\".", usb, { usb = it },
        "Open Developer options", { openSettings(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) })
    val ready = backup && accounts && developer && usb
    InfoCard {
        Text("5. On a computer with adb", fontWeight = FontWeight.SemiBold)
        Text("Plug the phone in, allow the USB prompt on the phone, and run this one line:", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B1B1B)).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(command, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = if (ready) Color.White else Palette.Muted)
            IconButton(onClick = { clipboard.setText(AnnotatedString(command)) }, enabled = ready) { Icon(Icons.Filled.ContentCopy, "Copy the command") }
        }
        if (!ready) Text("Tick the four steps above first. Skipping them can lose data or make the command fail.", color = Palette.Open, style = MaterialTheme.typography.bodySmall)
    }
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
            Column(Modifier.weight(1f)) {
                Text("Waiting for the command", fontWeight = FontWeight.SemiBold)
                Text("Checks every two seconds. If the computer says \"accounts on the device\" or \"users\", go back to step 2.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        SecondaryButton("Check now", { owner = isDeviceOwner(context); if (owner) onBecameOwner() })
    }
}

@Composable
fun DnsStep(draft: LockConfig, onChange: (LockConfig) -> Unit) {
    StepHeader(Icons.Filled.Shield, "Private DNS", "Every app and browser asks this server before it loads a site. It blocks adult sites at the source, and the setting is locked afterwards.")
    InfoCard { DnsFields(draft, onChange) }
}

@Composable
private fun PickRow(selected: Boolean, label: String, tag: String? = null, packageName: String? = null, onSelect: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = if (selected) Palette.RuleDark else MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioButton(selected, onClick = onSelect)
            if (packageName != null) AppIcon(packageName, 36.dp)
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Medium)
                if (tag != null) Text(tag, color = Palette.Open, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun BrowserStep(draft: LockConfig, apps: SetupApps?, onChange: (LockConfig) -> Unit) {
    StepHeader(Icons.Filled.Language, "One browser stays open", "Every other browser is closed: their private windows and own secure DNS can go around the filter.")
    if (apps == null) return Note("Reading your apps...")
    apps.browsers.sortedByDescending { it.packageName == KnownBrowsers.CHROME }.forEach { app ->
        PickRow(draft.allowedBrowser == app.packageName, app.label, if (app.packageName == KnownBrowsers.CHROME) "Recommended: the app locks its private windows, secure DNS and safe search" else null, app.packageName) {
            onChange(draft.copy(allowedBrowser = app.packageName, alwaysBlocked = draft.alwaysBlocked - app.packageName))
        }
    }
    PickRow(draft.allowedBrowser == null, "No browser stays open") { onChange(draft.copy(allowedBrowser = null)) }
    Note("A closed browser can be requested later. It waits 7 days and opens in the weekly window.")
}

@Composable
fun NightStep(draft: LockConfig, apps: SetupApps?, onChange: (LockConfig) -> Unit) {
    StepHeader(Icons.Filled.NightsStay, "Apps for the night", "From the night start until fajr only these apps and the phone's essentials work. Best: an offline audio player, the clock and an authenticator.")
    if (apps == null) return Note("Reading your apps...")
    apps.launchable.forEach { app ->
        val on = app.packageName in draft.nightAllowlist
        Card(
            Modifier.fillMaxWidth().clickable { onChange(toggle(draft, app.packageName, !on)) },
            colors = CardDefaults.cardColors(containerColor = if (on) Palette.RuleDark else MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(app.packageName, 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(app.label, fontWeight = FontWeight.Medium)
                    NightRisk.warning(app.packageName)?.let { Text(it, color = Palette.Open, style = MaterialTheme.typography.bodySmall) }
                }
                Checkbox(on, onCheckedChange = { onChange(toggle(draft, app.packageName, it)) })
            }
        }
    }
}

private fun toggle(draft: LockConfig, packageName: String, on: Boolean) =
    draft.copy(nightAllowlist = if (on) draft.nightAllowlist + packageName else draft.nightAllowlist - packageName, alwaysBlocked = draft.alwaysBlocked - packageName)

@Composable
fun RhythmStep(draft: LockConfig, onChange: (LockConfig) -> Unit) {
    StepHeader(Icons.Filled.Schedule, "Rhythm and place", "When the night starts, when the weekly window opens, and where you are, so fajr ends the night.")
    InfoCard { RhythmFields(draft, onChange) }
    InfoCard { LocationFields(draft, onChange) }
}

@Composable
private fun HowItWorks(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Palette.Bar), contentAlignment = Alignment.Center) { Text("$number", color = Color.White, fontWeight = FontWeight.Bold) }
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun FriendStep(manager: ChangeManager, verified: Boolean, onVerified: () -> Unit) {
    val scope = rememberCoroutineScope()
    var secret by remember { mutableStateOf<ByteArray?>(null) }
    var typed by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    StepHeader(Icons.Filled.People, "Your friend's code", "A friend holds the key to emergencies and to taking the protection off. Do this step with them next to you.")
    val shown = secret
    if (shown == null) {
        InfoCard {
            HowItWorks(1, "The app shows a QR code, once.")
            HowItWorks(2, "Your friend scans it with an authenticator app on their own phone.")
            HowItWorks(3, "They read you the 6-digit code to prove it worked.")
            Text("There is no text to copy and no link to send, and the screen cannot be captured. The app keeps the secret where you cannot read it back.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (verified) InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.CheckCircle, null, tint = Palette.Day, modifier = Modifier.size(28.dp))
                Text("Confirmed: your friend's code works.", fontWeight = FontWeight.SemiBold)
            }
        }
        SecondaryButton(if (manager.hasFriendSecret) "Make a new QR code (replaces the old one)" else "Show the QR code", { secret = manager.newFriendSecret() })
        if (manager.hasFriendSecret && !verified) Note("A secret exists but was not confirmed with your friend yet. Make a new one to confirm it.")
        return
    }
    SecretScreen()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp)) { QrImage(FriendCode.otpauthUri(shown)) }
    }
    Note("Your friend scans this, then reads you the current 6-digit code. Type it below. The QR code disappears once it matches.")
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it.filter(Char::isDigit).take(6); wrong = false },
        label = { Text("Code from your friend") },
        isError = wrong,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (wrong) Problem("That code does not match. Check the authenticator entry and try the next code.")
    MenuButton("Check the code", enabled = typed.length == 6) {
        scope.background({ manager.verifyEnrolment(typed, Instant.now()) }) { ok ->
            wrong = !ok
            if (ok) {
                secret = null
                onVerified()
            }
        }
    }
}

@Composable
fun ReviewStep(draft: LockConfig, apps: SetupApps?, friendVerified: Boolean, outcome: app.lockmein.Outcome?, working: Boolean, onStart: () -> Unit) {
    val problems = ConfigRules.problems(draft)
    fun label(pkg: String) = apps?.launchable?.firstOrNull { it.packageName == pkg }?.label ?: pkg
    StepHeader(Icons.Filled.CheckCircle, "Ready to start", "Check the summary. Until you press Start, nothing is applied to your phone.")
    SectionCard("Private DNS", draft.dnsHost, leading = Icons.Filled.Shield)
    SectionCard("Browser", draft.allowedBrowser?.let(::label) ?: "No browser stays open", listOfNotNull(draft.allowedBrowser), Icons.Filled.Language)
    SectionCard("Night apps", if (draft.nightAllowlist.isEmpty()) "Only the phone's essentials" else draft.nightAllowlist.joinToString { label(it) }, draft.nightAllowlist.toList(), Icons.Filled.NightsStay)
    SectionCard("Rhythm", "Night from ${draft.nightStart}. Weekly window: day ${draft.windowDay} (1 = Monday) ${draft.windowStart} to ${draft.windowEnd}.", leading = Icons.Filled.Schedule)
    SectionCard("Friend's code", if (friendVerified) "Confirmed" else "Not confirmed yet", leading = Icons.Filled.People)
    problems.forEach { Problem(it) }
    if (!friendVerified) Problem("Confirm the friend's code first.")
    Note(
        when {
            BuildConfig.PREVIEW -> "Preview: Start shows the finished app. Nothing is applied to your phone."
            BuildConfig.TEST_BUILD -> "Test build: can be removed over USB."
            else -> "Sealed build: USB debugging and factory reset are blocked."
        },
    )
    Note("After you start, anything that opens the phone up is a request: it waits 7 days, then you carry it out in the weekly window. Closing things works at once. Your friend's code unblocks one app for an hour and changes nothing else.")
    OutcomeText(outcome)
    MenuButton(if (working) "Starting..." else "Start protection", enabled = !working && problems.isEmpty() && friendVerified, onClick = onStart)
}
