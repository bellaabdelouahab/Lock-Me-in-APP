package app.lockmein.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import app.lockmein.AppInfo
import app.lockmein.BuildConfig
import app.lockmein.ChangeManager
import app.lockmein.Outcome
import app.lockmein.core.ConfigRules
import app.lockmein.core.DnsHost
import app.lockmein.core.FriendCode
import app.lockmein.core.KnownBrowsers
import app.lockmein.core.LockConfig
import app.lockmein.core.NightRisk
import java.time.Instant
import kotlinx.coroutines.delay

/** The apps a setup step can offer, read once from the phone. */
data class SetupApps(val browsers: List<AppInfo>, val launchable: List<AppInfo>, val suggestedNight: Set<String>)

private fun isDeviceOwner(context: Context) = context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)
private fun devOptionsOn(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
private fun usbDebuggingOn(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
private const val BACKUP_PHRASE = "I backed up"

@Composable
fun StepHeader(icon: ImageVector, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun openSettings(context: Context, action: String) {
    val opened = runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!opened) runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun Chore(number: Int, text: String, ticked: Boolean, onTick: (Boolean) -> Unit, button: String? = null, onButton: (() -> Unit)? = null) {
    AppCard {
        Row(Modifier.fillMaxWidth().clickable { onTick(!ticked) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (ticked) Palette.Ok else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (ticked) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                else Text("$number", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            }
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
        if (button != null && onButton != null) SecondaryButton(button, onButton)
    }
}

@Composable
fun OwnerStep(onBecameOwner: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val wasOwner = remember { isDeviceOwner(context) }
    var owner by remember { mutableStateOf(wasOwner) }
    var backupPhrase by rememberSaveable { mutableStateOf("") }
    var accounts by rememberSaveable { mutableStateOf(false) }
    var developer by remember { mutableStateOf(devOptionsOn(context)) }
    var usb by remember { mutableStateOf(usbDebuggingOn(context)) }
    val backup = backupPhrase.trim().equals(BACKUP_PHRASE, ignoreCase = true)
    val command = "adb shell dpm set-device-owner ${context.packageName}/.AdminReceiver"

    LaunchedEffect(Unit) {
        if (BuildConfig.PREVIEW || wasOwner) return@LaunchedEffect
        while (!owner) {
            delay(2_000)
            developer = devOptionsOn(context)
            usb = usbDebuggingOn(context)
            owner = isDeviceOwner(context)
        }
        onBecameOwner()
    }

    StepHeader(Icons.Filled.Usb, "Make this app the device owner", "Android's management layer enforces the policy even when the app is closed. Only a computer can activate it over USB.")
    if (BuildConfig.PREVIEW) {
        AppCard {
            Text("Preview build", fontWeight = FontWeight.SemiBold)
            Text("This preview build cannot become device owner and changes nothing on your phone. This step is skipped. Tap Next to continue.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    if (owner) {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.CheckCircle, null, tint = Palette.Ok, modifier = Modifier.size(24.dp))
                Text("This app is the device owner.", fontWeight = FontWeight.SemiBold)
            }
        }
        return
    }
    AppCard {
        Text("1. Back up your phone", fontWeight = FontWeight.SemiBold)
        Text("Photos, files and authenticator entries. Type \"$BACKUP_PHRASE\" once completed.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = backupPhrase,
            onValueChange = { backupPhrase = it },
            label = { Text("Type: $BACKUP_PHRASE") },
            singleLine = true,
            shape = CardShape,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (backup) Icon(Icons.Filled.CheckCircle, null, tint = Palette.Ok) },
        )
    }
    Chore(
        2,
        "Remove every account (Google, Xiaomi, others) and turn off Second Space. Android refuses the command while accounts exist; you add them back afterwards.",
        accounts,
        { accounts = it },
        "Open Accounts",
        { openSettings(context, Settings.ACTION_SYNC_SETTINGS) },
    )
    CheckRow(
        "3. Developer options",
        if (developer) CheckState.PASS else CheckState.FAIL,
        if (developer) "On. Checked on the phone." else "Off. Open About phone and tap the OS version seven times.",
        "Open About phone",
    ) { openSettings(context, Settings.ACTION_DEVICE_INFO_SETTINGS) }
    CheckRow(
        "4. USB debugging",
        if (usb) CheckState.PASS else if (developer) CheckState.FAIL else CheckState.CONFIRM,
        when {
            usb -> "On. Checked on the phone."
            !developer -> "Switch on developer options first."
            else -> "Off. In Developer options turn on USB debugging."
        },
        if (developer) "Open Developer options" else null,
    ) { openSettings(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
    val ready = backup && accounts && developer && usb
    AppCard {
        Text("5. On a computer with adb", fontWeight = FontWeight.SemiBold)
        Text("Connect the phone, allow the prompt on the screen, and run:", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Surface(
            shape = CardShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(command, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { clipboard.setText(AnnotatedString(command)) }, enabled = ready) {
                    Icon(Icons.Filled.ContentCopy, "Copy command")
                }
            }
        }
        if (!ready) {
            Text(
                "The command unlocks once earlier steps are checked.",
                color = Palette.Attention,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            Column(Modifier.weight(1f)) {
                Text("Waiting for command", fontWeight = FontWeight.SemiBold)
                Text("Checks automatically every two seconds.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        SecondaryButton("Check now", { owner = isDeviceOwner(context); if (owner) onBecameOwner() })
    }
}

@Composable
fun DnsStep(draft: LockConfig, onChange: (LockConfig) -> Unit, onChecked: (String?) -> Unit) {
    StepHeader(Icons.Filled.Shield, "Private DNS", "Every app asks this server before connecting. It blocks adult content at the source, and the setting is locked.")
    AppCard { DnsFields(draft, onChange) }
    val host = draft.dnsHost
    var result by remember(host) { mutableStateOf<String?>(null) }
    var checking by remember(host) { mutableStateOf(false) }
    var problem by remember(host) { mutableStateOf<String?>(null) }
    LaunchedEffect(host) {
        onChecked(null)
        if (DnsHost.problem(host) != null) return@LaunchedEffect
        delay(700)
        checking = true
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.lockmein.DnsProbe.problem(host) }
        checking = false
        problem = found
        result = if (found == null) host else null
        onChecked(result)
    }
    when {
        DnsHost.problem(host) != null -> Unit
        checking -> CheckRow("Testing $host", CheckState.CHECKING, "Connecting on the secure DNS port...")
        result == host -> CheckRow("$host verified", CheckState.PASS, "Secure DNS server answered with a valid certificate.")
        problem != null -> CheckRow("DNS check problem", CheckState.FAIL, problem)
    }
}

@Composable
private fun PickRow(selected: Boolean, label: String, tag: String? = null, packageName: String? = null, onSelect: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onSelect),
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioButton(selected, onClick = onSelect)
            if (packageName != null) AppIcon(packageName, 36.dp)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                if (tag != null) Text(tag, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun BrowserStep(draft: LockConfig, apps: SetupApps?, onChange: (LockConfig) -> Unit, onChosen: () -> Unit = {}) {
    StepHeader(Icons.Filled.Language, "One browser stays open", "Every other browser is closed: private windows and internal DNS can bypass filters.")
    if (apps == null) return Note("Reading installed browsers...")
    apps.browsers.sortedByDescending { it.packageName == KnownBrowsers.CHROME }.forEach { app ->
        PickRow(draft.allowedBrowser == app.packageName, app.label, if (app.packageName == KnownBrowsers.CHROME) "Recommended: locks safe search, disables private windows and Chrome secure DNS" else null, app.packageName) {
            onChange(draft.copy(allowedBrowser = app.packageName, alwaysBlocked = draft.alwaysBlocked - app.packageName)); onChosen()
        }
    }
    PickRow(draft.allowedBrowser == null, "No browser stays open") { onChange(draft.copy(allowedBrowser = null)); onChosen() }
    Note("A closed browser can be requested later through the 7-day change gate.")
}

@Composable
fun NightStep(draft: LockConfig, apps: SetupApps?, onChange: (LockConfig) -> Unit) {
    StepHeader(Icons.Filled.NightsStay, "Apps to close at night", "Select the apps that distract you. From the night start until fajr they are suspended; all other apps keep running.")
    if (apps == null) return Note("Reading installed apps...")
    apps.launchable.forEach { app ->
        val on = app.packageName in draft.nightClosed
        Card(
            Modifier.fillMaxWidth().clickable { onChange(toggle(draft, app.packageName, !on)) },
            shape = CardShape,
            colors = CardDefaults.cardColors(
                containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
            border = BorderStroke(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(app.packageName, 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.titleMedium)
                    NightRisk.warning(app.packageName)?.let { Text(it, color = Palette.Attention, style = MaterialTheme.typography.bodySmall) }
                }
                Checkbox(on, onCheckedChange = { onChange(toggle(draft, app.packageName, it)) })
            }
        }
    }
}

private fun toggle(draft: LockConfig, packageName: String, on: Boolean) =
    draft.copy(nightClosed = if (on) draft.nightClosed + packageName else draft.nightClosed - packageName)

@Composable
fun RhythmStep(draft: LockConfig, onChange: (LockConfig) -> Unit) {
    StepHeader(Icons.Filled.Schedule, "Rhythm and place", "Set when the night begins, when the weekly open window runs, and your coordinates so fajr ends the night.")
    AppCard { RhythmFields(draft, onChange) }
    AppCard { LocationFields(draft, onChange) }
}

@Composable
private fun HowItWorks(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun FriendStep(manager: ChangeManager, verified: Boolean, onVerified: () -> Unit) {
    val scope = rememberCoroutineScope()
    var secret by remember { mutableStateOf<ByteArray?>(null) }
    var typed by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    StepHeader(Icons.Filled.People, "Friend's code", "A trusted friend holds the key for emergencies and uninstallation. Do this step with them present.")
    val shown = secret
    if (shown == null) {
        AppCard {
            HowItWorks(1, "The app displays a QR code once.")
            HowItWorks(2, "Your friend scans it using an authenticator app on their phone.")
            HowItWorks(3, "They read you the 6-digit code to verify the pairing.")
            Text(
                "No text secret can be copied, links cannot be sent, and screenshots are blocked. The key stays outside your reach.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (verified) {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Palette.Ok, modifier = Modifier.size(24.dp))
                    Text("Friend code is verified and ready.", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        SecondaryButton(
            label = if (manager.hasFriendSecret) "Generate new QR code (replaces earlier one)" else "Show QR code",
            onClick = { secret = manager.newFriendSecret() },
        )
        return
    }
    SecretScreen()
    AppCard {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.clip(CardShape).background(Color.White).padding(14.dp)) {
                QrImage(FriendCode.otpauthUri(shown))
            }
        }
        Text(
            "Your friend scans this with their authenticator app, then reads the 6-digit code to you. Type it below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    CodeField(typed) {
        typed = it
        wrong = false
    }
    if (wrong) Problem("That code does not match. Check the authenticator entry and try the next code.")

    PrimaryButton(label = "Verify code", enabled = typed.length == 6) {
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
fun FilterStep(onEnabled: () -> Unit) {
    val context = LocalContext.current
    var isEnabled by remember { mutableStateOf(app.lockmein.FilterService.isEnabled(context)) }

    LaunchedEffect(Unit) {
        if (BuildConfig.PREVIEW) return@LaunchedEffect
        while (!isEnabled) {
            delay(1_500)
            isEnabled = app.lockmein.FilterService.isEnabled(context)
            if (isEnabled) onEnabled()
        }
    }

    StepHeader(
        Icons.Filled.Visibility,
        "Turn on the screen filter",
        "The on-device AI screen filter watches selected apps to detect sexual or revealing content. It runs entirely on the phone: no image is ever saved, stored, or sent.",
    )

    if (BuildConfig.PREVIEW) {
        AppCard {
            Text("Preview build", fontWeight = FontWeight.SemiBold)
            Text("In this preview build the accessibility service is simulated. Tap Next to continue.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    if (isEnabled) {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.CheckCircle, null, tint = Palette.Ok, modifier = Modifier.size(24.dp))
                Column {
                    Text("Screen filter is on", fontWeight = FontWeight.SemiBold)
                    Text("The accessibility service is active and ready.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        return
    }

    AppCard {
        Text("1. Allow restricted settings (sideloaded apps)", fontWeight = FontWeight.SemiBold)
        Text(
            "On Android 13+ (including HyperOS), sideloaded apps need permission before turning on an accessibility service:\n" +
            "Open App info for Lock Me In, tap the three dots in the top corner, and tap \"Allow restricted settings\".",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        SecondaryButton("Open App info", {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        })
    }

    AppCard {
        Text("2. Turn on Lock Me In Screen Filter", fontWeight = FontWeight.SemiBold)
        Text(
            "In Accessibility settings -> Downloaded apps (or Installed services), find \"Lock Me In\" and toggle it on.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        SecondaryButton("Open Accessibility settings", {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        })
    }

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            Column(Modifier.weight(1f)) {
                Text("Waiting for service to turn on", fontWeight = FontWeight.SemiBold)
                Text("Checks automatically every two seconds.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        SecondaryButton("Check now", {
            isEnabled = app.lockmein.FilterService.isEnabled(context)
            if (isEnabled) onEnabled()
        })
    }
}

@Composable
fun ReviewStep(draft: LockConfig, apps: SetupApps?, friendVerified: Boolean, filterOn: Boolean = true, outcome: Outcome?, working: Boolean, onStart: () -> Unit) {
    val problems = ConfigRules.problems(draft)
    fun label(pkg: String) = apps?.launchable?.firstOrNull { it.packageName == pkg }?.label ?: pkg
    StepHeader(Icons.Filled.CheckCircle, "Ready to start", "Review your rules. Nothing is enforced until you tap Start protection.")
    AppCard {
        Text("Summary", style = MaterialTheme.typography.titleMedium)
        Text("Private DNS: ${draft.dnsHost}", style = MaterialTheme.typography.bodyMedium)
        Text("Browser: ${draft.allowedBrowser?.let(::label) ?: "No browser stays open"}", style = MaterialTheme.typography.bodyMedium)
        Text("Night apps: ${draft.nightClosed.size} apps selected", style = MaterialTheme.typography.bodyMedium)
        Text("Night starts: ${draft.nightStart}", style = MaterialTheme.typography.bodyMedium)
        Text("Weekly window: day ${draft.windowDay} (${draft.windowStart} to ${draft.windowEnd})", style = MaterialTheme.typography.bodyMedium)
        Text("Friend code: " + if (friendVerified) "Verified" else "Not verified yet", style = MaterialTheme.typography.bodyMedium)
        Text("Screen filter: " + if (filterOn) "Active (${draft.watchedApps.size} watched apps)" else "Service off", style = MaterialTheme.typography.bodyMedium)
    }
    problems.forEach { Problem(it) }
    if (!friendVerified) Problem("Verify your friend's code first.")
    if (!filterOn && !BuildConfig.PREVIEW) Problem("Turn on the screen filter accessibility service first.")
    Note("Once protection starts, any change that opens things up is a 7-day request carried out in the weekly window. Closing things is immediate.")
    OutcomeText(outcome)
    PrimaryButton(
        label = if (working) "Starting..." else "Start protection",
        enabled = !working && problems.isEmpty() && friendVerified && (filterOn || BuildConfig.PREVIEW),
        onClick = onStart,
    )
}
