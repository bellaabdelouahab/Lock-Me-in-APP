package app.lockmein

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/** Re-runs the policy on boot, after an update, when the time zone changes, and when the alarm set by the last run fires. */
class ReapplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)) return
        val pending = goAsync()
        // The DNS step blocks, so the pass runs off the main thread.
        thread(name = "lock-me-in-run") {
            try {
                val result = PolicyRunner(context.applicationContext).runIfActive()
                if (result != null && !result.report.ok) Log.w(TAG, "policy pass left problems: ${result.report.failed}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RUN = "app.lockmein.action.RUN"
        private const val TAG = "LockMeIn"
    }
}
