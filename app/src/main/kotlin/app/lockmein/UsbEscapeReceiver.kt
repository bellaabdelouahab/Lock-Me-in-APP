package app.lockmein

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The test build's way out over USB, in place of a test-only APK (which Android lets only a computer install, so the in-app update
 * could never install it): `adb shell am broadcast -n app.lockmein/.UsbEscapeReceiver`. Only the shell holds the DUMP permission the
 * manifest asks of the sender, so no app on the phone can call it. Takes every policy off and ends device ownership.
 * Does nothing in the sealed build.
 */
class UsbEscapeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.TEST_BUILD) return
        val pending = goAsync()
        Thread {
            runCatching {
                val dpm = context.getSystemService(DevicePolicyManager::class.java)
                if (dpm.isDeviceOwnerApp(context.packageName)) {
                    PolicyApplier(context).release(DeviceInventory(context).read().installed)
                    PolicyRunner(context).cancelAlarm()
                    dpm.clearDeviceOwnerApp(context.packageName)
                }
                ConfigStore(context).run { protectionActive = false; setupComplete = false; redoing = false }
            }
            pending.finish()
        }.start()
    }
}
