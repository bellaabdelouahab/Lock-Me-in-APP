package app.lockmein

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Runs a policy pass as soon as a new app is installed, so blocked or window apps are suspended immediately. */
class PackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_PACKAGE_ADDED) {
            PolicyRunner(context).runIfActive()
        }
    }
}
