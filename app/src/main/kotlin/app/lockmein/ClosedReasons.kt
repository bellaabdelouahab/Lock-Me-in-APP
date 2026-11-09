package app.lockmein

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import app.lockmein.core.LockConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Why each app is closed right now, in one sentence, and whether Android really holds it closed. Blocks: call off the main thread. */
object ClosedReasons {
    data class Reason(val packageName: String, val why: String, val closedNow: Boolean, val held: Boolean)

    private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    fun all(context: Context, config: LockConfig, now: Instant = TrustedClock(context).now()): List<Reason> {
        val browsers = DeviceInventory(context).read().browsers
        val state = LockState(context)
        val held = state.quarantined
        val filterBlocked = state.filterBlocked(now)
        val filterOff = !FilterService.isEnabled(context)
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, AdminReceiver::class.java)
        val owner = dpm.isDeviceOwnerApp(context.packageName)
        return PolicyRunner(context).planFor(config, now).suspended.map { pkg ->
            val why = when {
                filterBlocked[pkg] != null -> "Closed by the screen filter until ${clock.format(filterBlocked[pkg])}."
                filterOff && pkg in config.watchedApps -> "Watched app: closed while the screen filter is off. Turn it on in Accessibility."
                pkg in held -> "New app: closed until you let it through (friend's code, or approve it on Sunday 20:00 to 21:00)."
                pkg in config.alwaysBlocked -> "Always blocked."
                pkg in config.windowApps -> "Window app: open only from 20:00 to 21:30."
                pkg in config.nightClosed -> "Closed at night, until fajr."
                pkg in browsers -> "Other browser: only ${config.allowedBrowser?.let { DeviceInventory(context).label(it) } ?: "none"} stays open."
                else -> "Closed by the rules."
            }
            Reason(pkg, why, owner && runCatching { dpm.isPackageSuspended(admin, pkg) }.getOrDefault(false), pkg in held)
        }.sortedBy { it.packageName }
    }

    fun of(context: Context, packageName: String): String? {
        val config = ConfigStore(context).load() ?: return null
        return all(context, config).firstOrNull { it.packageName == packageName }?.why
    }
}

/** Shown by Android's own "app not available" dialog. Each app's own reason is on the screen Lock Me In opens over that dialog. */
const val SUPPORT_MESSAGE = "Closed by Lock Me In. Open Lock Me In, Apps, Now to see why and when it opens."
