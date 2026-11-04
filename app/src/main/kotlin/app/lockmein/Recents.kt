package app.lockmein

import android.app.Activity
import android.app.ActivityManager
import android.content.Context

/**
 * Keeps Lock Me In out of the recent apps screen, so it can't be swiped away or long-pressed into App info. On by default.
 * Once protection is on it can only be switched on, never off: hiding is part of the protection.
 */
object Recents {
    private fun prefs(context: Context) = context.getSharedPreferences("lock_ui", Context.MODE_PRIVATE)

    fun hidden(context: Context) = prefs(context).getBoolean(HIDE, true)

    /** False when the change is refused (switching it off while protection is on). */
    fun setHidden(context: Context, hide: Boolean): Boolean {
        if (!hide && ConfigStore(context).protectionActive) return false
        prefs(context).edit().putBoolean(HIDE, hide).apply()
        return true
    }

    fun apply(activity: Activity) {
        val hide = hidden(activity)
        activity.getSystemService(ActivityManager::class.java).appTasks.forEach { runCatching { it.setExcludeFromRecents(hide) } }
    }

    private const val HIDE = "hide_from_recents"
}
