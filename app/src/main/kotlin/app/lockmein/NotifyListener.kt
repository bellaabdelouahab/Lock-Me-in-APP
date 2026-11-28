package app.lockmein

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.lockmein.core.UseKind

/**
 * Notification access (FEATURES 3.21, spec F3): which app posted a notification and which ones were tapped open, for the
 * phone-use log, so "what started it" can be answered. Only the package and the time are kept, on the phone; never the title,
 * the sender or the text. Ongoing ones (music, a running timer, Lock Me In's own) and group summaries are not counted.
 * Switched on once by the owner in Settings (Android lets no app grant it to itself).
 */
class NotifyListener : NotificationListenerService() {
    override fun onListenerConnected() { connected = true }
    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (!counts(n)) return
        PhoneUseLog(this).add(UseKind.NOTIFICATION, n.packageName)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        val n = sbn ?: return
        if (reason == REASON_CLICK && counts(n)) PhoneUseLog(this).add(UseKind.NOTIFICATION_OPENED, n.packageName)
    }

    private fun counts(n: StatusBarNotification): Boolean {
        val flags = n.notification.flags
        return n.packageName != packageName && !n.isOngoing &&
            flags and Notification.FLAG_GROUP_SUMMARY == 0 && flags and Notification.FLAG_FOREGROUND_SERVICE == 0
    }

    companion object {
        /** True while Android has the listener bound: the filter then leaves notifications to it (no double count). */
        @Volatile
        var connected = false
            private set

        fun enabled(context: Context): Boolean {
            val me = ComponentName(context, NotifyListener::class.java).flattenToString()
            val list = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            return list.split(':').any { it == me }
        }
    }
}
