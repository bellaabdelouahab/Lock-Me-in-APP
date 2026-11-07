package app.lockmein.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.lockmein.ChangeManager
import app.lockmein.ConfigStore
import app.lockmein.LockState
import app.lockmein.Outcome
import app.lockmein.Rules
import app.lockmein.TrustedClock
import app.lockmein.core.CountdownFormatter
import app.lockmein.core.FriendCode
import java.time.Duration
import java.time.Instant

@Composable
fun CodeField(code: String, onChange: (String) -> Unit) = OutlinedTextField(
    value = code,
    onValueChange = { onChange(it.filter(Char::isDigit).take(6)) },
    label = { Text("6-digit holder code") },
    singleLine = true,
    shape = CardShape,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    modifier = Modifier.fillMaxWidth(),
)

/**
 * Holder code screen:
 * Ask Mom or your brother for today's code.
 * A valid code opens a 15-minute change session.
 * 5 wrong codes lock out for 30 minutes.
 * Re-keying requires entering current valid code first.
 */
@Composable
fun EmergencyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { ChangeManager(context) }
    val state = remember { LockState(context) }
    val scope = rememberCoroutineScope()

    var code by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var working by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }

    // Re-keying state
    var rekeyingStep by remember { mutableIntStateOf(0) } // 0 = idle, 1 = enter current code, 2 = show new QR
    var newSecret by remember { mutableStateOf<ByteArray?>(null) }
    var confirmNewCode by remember { mutableStateOf("") }
    var rekeyError by remember { mutableStateOf<String?>(null) }

    val now = TrustedClock(context).now()
    val inSession = state.inSession(now)
    val isLockedOut = state.isLockedOut(now)

    Page("Holder code", onBack) {
        when {
            inSession -> {
                val rem = Duration.between(now, state.sessionUntil!!)
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Filled.HourglassTop, contentDescription = null, tint = Palette.Ok, modifier = Modifier.size(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Change session active", style = MaterialTheme.typography.titleMedium, color = Palette.Ok)
                            Text(
                                "${CountdownFormatter.formatDurationHM(rem)} remaining. All settings and lists can be changed freely.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            isLockedOut -> {
                val remMinutes = Duration.between(now, state.lockoutUntil!!).toMinutes().coerceAtLeast(1)
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = Palette.Closed, modifier = Modifier.size(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Locked out after 5 wrong codes", style = MaterialTheme.typography.titleMedium, color = Palette.Closed)
                            Text(
                                "Too many wrong attempts. Try again in $remMinutes minutes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            else -> {
                AppCard {
                    Text(
                        "Ask Mom or your brother for today's code.",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "A valid code opens a 15-minute change session. During the session, you can change settings and approve apps just like in the Sunday window.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                CodeField(code) {
                    code = it
                    outcome = null
                }

                OutcomeText(outcome)

                PrimaryButton(
                    label = if (working) "Checking code..." else "Start 15-minute session",
                    enabled = code.length == 6 && !working,
                    onClick = {
                        working = true
                        scope.background({ manager.openSession(code) }) { res ->
                            working = false
                            outcome = res
                            if (res is Outcome.Done) {
                                code = ""
                            }
                            version++
                        }
                    },
                )

                Spacer(Modifier.height(8.dp))

                SecondaryButton(
                    label = "Re-key holder code (new QR)",
                    onClick = {
                        rekeyingStep = 1
                        code = ""
                        rekeyError = null
                    },
                )
            }
        }
    }

    // Re-keying Dialog Flow
    if (rekeyingStep == 1) {
        var currentCodeInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rekeyingStep = 0 },
            shape = CardShape,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Re-key holder code", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter the current valid holder code first to verify your identity.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    CodeField(currentCodeInput) { currentCodeInput = it }
                    if (rekeyError != null) {
                        Problem(rekeyError)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = currentCodeInput.length == 6,
                    onClick = {
                        val genSecret = manager.reKeySecret(currentCodeInput)
                        if (genSecret != null) {
                            newSecret = genSecret
                            rekeyingStep = 2
                            rekeyError = null
                        } else {
                            rekeyError = "Code was wrong or already used."
                        }
                    },
                ) { Text("Verify") }
            },
            dismissButton = {
                TextButton(onClick = { rekeyingStep = 0 }) { Text("Cancel") }
            },
        )
    } else if (rekeyingStep == 2 && newSecret != null) {
        val secret = newSecret!!
        AlertDialog(
            onDismissRequest = { /* Must confirm or cancel */ },
            shape = CardShape,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Scan new holder QR", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(Modifier.clip(CardShape).background(Color.White).padding(10.dp)) {
                            QrImage(FriendCode.otpauthUri(secret))
                        }
                    }
                    Text(
                        "Have Mom or your brother scan this with their authenticator app. Once confirmed, this QR code is never shown again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CodeField(confirmNewCode) { confirmNewCode = it }
                    if (rekeyError != null) {
                        Problem(rekeyError)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = confirmNewCode.length == 6,
                    onClick = {
                        val ok = manager.verifyEnrolment(confirmNewCode)
                        if (ok) {
                            rekeyingStep = 0
                            newSecret = null
                            outcome = Outcome.Done("Holder code successfully updated.")
                            version++
                        } else {
                            rekeyError = "Code does not match. Try the next 6-digit code."
                        }
                    },
                ) { Text("Confirm and finish") }
            },
            dismissButton = {
                TextButton(onClick = {
                    rekeyingStep = 0
                    newSecret = null
                }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Monthly uninstall screen:
 * Shown on the 1st of each month between 20:00 and 21:00.
 * Confirms with exact text: "You chose this a month ago. Still want it? Type REMOVE"
 */
@Composable
fun RemoveScreen(onBack: () -> Unit, onRemoved: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { ChangeManager(context) }
    val store = remember { ConfigStore(context) }
    val scope = rememberCoroutineScope()
    var confirmText by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var working by remember { mutableStateOf(false) }

    val config = store.load()
    val now = TrustedClock(context).now()
    val inUninstallWindow = config?.let { Rules.schedule(it).inUninstallWindow(now) } ?: false

    Page("Remove Lock Me In", onBack) {
        AppCard {
            Text("Danger zone", style = MaterialTheme.typography.titleMedium, color = Palette.Closed)
            if (!inUninstallWindow) {
                Text(
                    "Lock Me In can only be removed during the monthly uninstall window: on the 1st of each month between 20:00 and 21:00.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "You chose this a month ago. Still want it? Type REMOVE",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "This releases all policies, clears device owner, and uninstalls Lock Me In.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutcomeText(outcome)

        if (inUninstallWindow) {
            OutlinedTextField(
                value = confirmText,
                onValueChange = { confirmText = it },
                label = { Text("Type REMOVE to confirm") },
                singleLine = true,
                shape = CardShape,
                modifier = Modifier.fillMaxWidth(),
            )

            PrimaryButton(
                label = if (working) "Removing..." else "Remove Lock Me In",
                enabled = confirmText.trim() == "REMOVE" && !working,
                onClick = {
                    working = true
                    scope.background({ manager.removeLockMeIn() }) { res ->
                        working = false
                        outcome = res
                        if (res is Outcome.Done) {
                            runCatching {
                                val uninstall = Intent(Intent.ACTION_DELETE).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(uninstall)
                            }
                            onRemoved()
                        }
                    }
                },
            )
        }
    }
}
