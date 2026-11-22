package app.lockmein.ui

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lockmein.DeviceInventory
import app.lockmein.Outcome
import app.lockmein.PhoneUseLog
import app.lockmein.core.DayUse
import app.lockmein.core.PhoneUse
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How the phone was used, day by day: screen time, unlocks, and time per app. Kept on the phone; exported only when asked. */
@Composable
fun PhoneUseScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val today = remember { LocalDate.now(zone) }
    var day by remember { mutableStateOf(today) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    val scope = rememberCoroutineScope()
    val inventory = remember { DeviceInventory(context) }
    // The launcher and the system bars are where you pass through, not what you use.
    val ignore = remember {
        val home = context.packageManager.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        setOfNotNull(home, "com.android.systemui")
    }
    // Two small files (the day and the one before), read right here so the page shows at once.
    val use: DayUse? = remember(day) { runCatching { PhoneUse.summarize(PhoneUseLog(context).read(day), day, zone, Instant.now(), ignore) }.getOrNull() }

    Page("Phone use", onBack) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { day = day.minusDays(1) }, enabled = day > today.minusDays(PhoneUseLog.KEEP_DAYS)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Day before")
            }
            Text(
                when (day) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> day.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { day = day.plusDays(1) }, enabled = day < today) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Day after")
            }
        }

        val u = use
        if (u == null) {
            Note("Reading...")
        } else {
            InfoCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat(duration(u.screenMillis), "screen time")
                    Stat(u.unlocks.toString(), "unlocks")
                    Stat(u.notifications.toString(), "notifications")
                }
                val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
                val first = u.firstUnlock?.let { "First unlock ${hm.format(it)}" }
                val last = u.lastScreenOff?.let { "last screen off ${hm.format(it)}" }
                listOfNotNull(first, last).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Heading("Tries on closed apps")
            InfoCard {
                val named = u.apps.filter { it.tries > 0 }.sortedByDescending { it.tries }
                Text(
                    when (u.blockedTries) {
                        0 -> "No tries to open a closed app."
                        1 -> "1 try to open a closed app."
                        else -> "${u.blockedTries} tries to open a closed app."
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                named.forEachIndexed { i, a ->
                    AppRow(packageName = a.pkg, title = inventory.label(a.pkg), subtitle = "last ${u.tries.last { it.second == a.pkg }.first.atZone(zone).toLocalTime().withNano(0).withSecond(0)}") {
                        Text("${a.tries}", style = MaterialTheme.typography.titleSmall)
                    }
                    if (i < named.size - 1) RowDivider()
                }
                val unnamed = u.tries.count { it.second.isEmpty() }
                if (unnamed > 0) Note("$unnamed not named (the dialog did not say which app).")
            }

            Heading("Apps")
            val apps = u.apps.filter { it.millis >= 60_000 || it.opens > 0 }
            if (apps.isEmpty()) {
                InfoCard { Note("Nothing recorded for this day yet. Recording starts with this version, while protection is on.") }
            } else {
                InfoCard {
                    apps.take(30).forEachIndexed { i, a ->
                        AppRow(
                            packageName = a.pkg,
                            title = inventory.label(a.pkg),
                            subtitle = listOfNotNull(
                                "${a.opens} ${if (a.opens == 1) "open" else "opens"}",
                                a.notifications.takeIf { it > 0 }?.let { "$it ${if (it == 1) "notification" else "notifications"}" },
                                a.tries.takeIf { it > 0 }?.let { "$it ${if (it == 1) "try while closed" else "tries while closed"}" },
                            ).joinToString(", "),
                        ) {
                            Text(duration(a.millis), style = MaterialTheme.typography.titleSmall)
                        }
                        if (i < minOf(apps.size, 30) - 1) RowDivider()
                    }
                }
            }
        }

        SecondaryButton("Export to Downloads (CSV)", {
            scope.background({
                runCatching {
                    val events = PhoneUseLog(context).readAll()
                    val csv = PhoneUse.csv(events, zone) { inventory.label(it) }
                    val name = "lockmein-phone-use-$today.csv"
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    }
                    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no Downloads")
                    context.contentResolver.openOutputStream(uri)!!.use { it.write(csv.toByteArray()) }
                    "${events.size} events saved to Downloads as $name."
                }
            }) { r -> outcome = r.fold({ Outcome.Done(it) }, { Outcome.Refused("Could not export: ${it.message}") }) }
        })
        OutcomeText(outcome)
        Note("Recorded on this phone only: which app was in front and for how long, unlocks, screen on and off, how many notifications each app sent, and each try to open a closed app. Never what was on the screen or what you typed; the name of an icon tapped is held for 3 seconds only, to name the app behind a \"blocked\" dialog. Kept ${PhoneUseLog.KEEP_DAYS} days.")
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun duration(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}
