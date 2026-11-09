package app.lockmein

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import app.lockmein.core.EventKind

class LockMeInApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
            addDataScheme("package")
        }
        val receiver = PackageReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        recordCrashes()
    }

    /** Puts the app's own crashes in the event log, so a crash on the phone can be traced without a cable. */
    private fun recordCrashes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prefs = getSharedPreferences("crash_log", MODE_PRIVATE)
        val seen = prefs.getLong("last", 0L)
        val exits = getSystemService(android.app.ActivityManager::class.java).getHistoricalProcessExitReasons(packageName, 0, 10)
        val crashes = exits.filter {
            it.timestamp > seen && it.reason in setOf(
                android.app.ApplicationExitInfo.REASON_CRASH, android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY, android.app.ApplicationExitInfo.REASON_ANR,
            )
        }
        crashes.forEach {
            EventLog(this).add(EventKind.PASS_FAILED, "app stopped (reason ${it.reason}, ${it.processName}): ${it.description ?: ""}".take(300), java.time.Instant.ofEpochMilli(it.timestamp))
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("last", maxOf(it, seen)).apply() }
    }
}
