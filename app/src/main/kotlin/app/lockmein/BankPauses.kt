package app.lockmein

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import app.lockmein.core.BankPause
import app.lockmein.core.EventKind
import app.lockmein.core.Reports
import java.time.Duration

/**
 * The screen filter's pause for a bank app (spec D1). Simple, Assafa and Cih refuse to open while any accessibility service is
 * on, so while one of them is in front the filter switches itself off (disableSelf). Every watched app stays closed meanwhile:
 * FilterWatcher sees the filter off and closes them, as for any time it is off.
 *
 * Coming back on needs WRITE_SECURE_SETTINGS (Android lets no app, device owner included, switch an accessibility service on
 * otherwise; granted once by adb). With it, the filter is switched back on when the bank app leaves (seen with usage access,
 * also granted by adb), when the screen goes off, or after [BankPause.CAP_MS], whichever comes first. Without it, he switches it
 * back on by hand in Accessibility, and the watched apps stay closed until he does.
 */
object BankPauses {
    private const val TAG = "BankPause"

    /** Windows that come up over an app without leaving it: Android's dialogs, permission prompts, the system bars. */
    private val NOT_LEAVING = setOf("android", "com.android.systemui", "com.google.android.permissioncontroller", "com.android.permissioncontroller")

    /** Emulator tests shorten the cap. */
    @Volatile
    var capMsForTesting: Long? = null

    fun canSwitchBackOn(context: Context): Boolean =
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    /** From the filter, when [pkg] came to the front. True when the filter is pausing itself (it must stop at once). */
    fun shouldPause(context: Context, pkg: String): Boolean {
        val state = LockState(context)
        val config = ConfigStore(context).load() ?: return false
        if (pkg != state.bankCapped) state.bankCapped = null
        if (BuildConfig.PREVIEW || !ConfigStore(context).protectionActive) return false
        if (pkg !in config.filterPauseApps || pkg == state.bankCapped || state.bankPause != null) return false
        state.bankPause = BankPause(pkg, System.currentTimeMillis(), canSwitchBackOn(context))
        state.downForBank = true
        return true
    }

    /** From the watcher's beat and the screen-off broadcast: ends an automatic pause when its time has come. */
    fun tick(context: Context, screenOff: Boolean = false) {
        val state = LockState(context)
        val pause = state.bankPause ?: return
        if (!pause.auto) return
        val front = foreground(context, pause.startMs)
        val reason = BankPause.endReason(pause, System.currentTimeMillis(), front, screenOff, capMsForTesting ?: BankPause.CAP_MS) ?: return
        end(context, pause, if (reason == BankPause.LEFT && front != null) "$reason (to ${DeviceInventory(context).label(front).ifBlank { front }})" else reason)
        if (reason == BankPause.CAP) state.bankCapped = pause.pkg
        if (!switchFilterOn(context)) {
            EventLog(context).add(EventKind.PASS_FAILED, "bank pause: could not switch the screen filter back on; the watched apps stay closed")
        }
    }

    /** Logs the end of [pause] and clears it. */
    fun end(context: Context, pause: BankPause, reason: String) {
        LockState(context).bankPause = null
        val took = Reports.hoursMinutes(Duration.ofMillis((System.currentTimeMillis() - pause.startMs).coerceAtLeast(0)))
        EventLog(context).add(EventKind.BANK_PAUSE_ENDED, "${DeviceInventory(context).label(pause.pkg).ifBlank { pause.pkg }}, $took, $reason")
    }

    /** Adds the filter back to Android's enabled accessibility services. Needs WRITE_SECURE_SETTINGS. */
    private fun switchFilterOn(context: Context): Boolean = runCatching {
        val me = ComponentName(context, FilterService::class.java).flattenToString()
        val r = context.contentResolver
        val list = Settings.Secure.getString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').filter { it.isNotBlank() }
        if (list.none { ComponentName.unflattenFromString(it) == ComponentName.unflattenFromString(me) }) {
            Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (list + me).joinToString(":"))
        }
        Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        true
    }.onFailure { Log.w(TAG, "switching the filter back on failed", it) }.getOrDefault(false)

    /** The app last brought to the front since [sinceMs], from usage access when it was granted (null when unknown). */
    private fun foreground(context: Context, sinceMs: Long): String? {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = runCatching { ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) }.getOrNull()
        if (mode != AppOpsManager.MODE_ALLOWED) return null
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(sinceMs - 5_000, System.currentTimeMillis())
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && e.packageName != context.packageName && e.packageName !in NOT_LEAVING) last = e.packageName
        }
        return last
    }
}
