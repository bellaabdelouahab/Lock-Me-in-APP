package app.lockmein.ui

import android.app.admin.DevicePolicyManager
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.lockmein.BuildConfig
import app.lockmein.ConfigStore
import app.lockmein.LockState
import app.lockmein.Rules
import app.lockmein.core.Phase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val navigate = LocalNavigate.current
    val store = remember { ConfigStore(context) }
    val state = remember { LockState(context) }
    val config = store.load()
    val owner = context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)
    val active = store.protectionActive && config != null
    val zone = ZoneId.systemDefault()
    val now = Instant.now()

    Page("Lock Me In") {
        InfoCard {
            Text(if (active) "Protection is on." else "Protection is off.")
            Text(if (owner) "This app is the device owner." else "This app is not the device owner yet.")
            Text(if (BuildConfig.DEBUG) "Test build: can be removed over USB." else "Sealed build.")
            if (active && config != null) {
                val schedule = Rules.schedule(config)
                val phase = schedule.phaseAt(now)
                Text(
                    when (phase) {
                        Phase.DAY -> "Now: day."
                        Phase.NIGHT -> "Now: night. Only the night apps run."
                        Phase.OPEN_WINDOW -> "Now: the open window. You can change things."
                    },
                )
                Text("Next change: ${TIME.format(schedule.nextTransition(now).atZone(zone))}")
                Text("Open window next: ${TIME.format(schedule.nextWindowStart(now).atZone(zone))}")
                if (state.quarantined.isNotEmpty()) Text("${state.quarantined.size} new app(s) are held back until you review them.")
                state.emergencyUntil?.takeIf { it.isAfter(now) }?.let { Text("Emergency window open until ${TIME.format(it.atZone(zone))}.") }
            }
        }

        if (!active) {
            Note("Set it up once: it takes about ten minutes and explains each step.")
            MenuButton("Set up") { navigate(Screen.SETUP) }
        } else {
            MenuButton("The open window") { navigate(Screen.WINDOW) }
            SecondaryButton("Night and blocked apps", { navigate(Screen.LISTS) })
            SecondaryButton("Browsers", { navigate(Screen.BROWSERS) })
            SecondaryButton("This week's report", { navigate(Screen.REPORT) })
            SecondaryButton("Settings", { navigate(Screen.SETTINGS) })
            SecondaryButton("Emergency (friend's code)", { navigate(Screen.EMERGENCY) })
            SecondaryButton("Remove protection", { navigate(Screen.REMOVE) })
        }
    }
}
