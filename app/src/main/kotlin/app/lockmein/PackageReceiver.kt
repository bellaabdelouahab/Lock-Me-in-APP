package app.lockmein

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import app.lockmein.core.EventKind
import kotlin.concurrent.thread

/**
 * Runs a policy pass as soon as a new app is installed, so new apps and blocked/window apps are suspended immediately.
 * Belt and braces: suspends the package directly in onReceive before running the full pass on a background thread.
 */
class PackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return

        val pkgName = intent.data?.schemeSpecificPart ?: return
        if (pkgName.isBlank() || pkgName == context.packageName) return

        val store = ConfigStore(context)
        if (!store.protectionActive) return

        // Belt and braces: immediately quarantine and suspend if device owner
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, AdminReceiver::class.java)
        if (dpm != null && dpm.isDeviceOwnerApp(context.packageName)) {
            val snapshot = DeviceInventory(context).read()
            if (pkgName !in snapshot.protectedPackages) {
                val state = LockState(context)
                state.quarantine(pkgName)
                runCatching {
                    dpm.setPackagesSuspended(admin, arrayOf(pkgName), true)
                    dpm.setShortSupportMessage(admin, "Blocked by Lock Me In. Laptop only, 20:00 to 21:30.")
                }
                EventLog(context).add(EventKind.APP_QUARANTINED, "Immediate suspend on install: $pkgName")
            }
        }

        val pendingResult = goAsync()
        thread(isDaemon = true) {
            try {
                PolicyRunner(context).runIfActive()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
