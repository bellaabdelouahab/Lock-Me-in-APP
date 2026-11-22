package app.lockmein

import android.app.WallpaperManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import app.lockmein.core.Personalization

/**
 * Themes, wallpapers and the always-on display stay his to change. Lock Me In never closes them (they are protected), but HyperOS
 * itself hides the Themes app on a phone that has a device owner (reported for OwnDroid and Dhizuku too). Each pass undoes what a
 * device owner may undo: a hidden or disabled Themes app is shown and enabled again. What HyperOS refuses inside its own apps
 * ("blocked by company policy") no app can change; the wallpaper can still be set from here.
 */
class Personalize(private val context: Context) {
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, AdminReceiver::class.java)
    private val pm = context.packageManager

    enum class State { MISSING, HIDDEN, DISABLED, SUSPENDED, NO_ICON, OK }

    fun state(pkg: String = THEMES): State {
        val info = runCatching {
            pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS)
        }.getOrNull() ?: return State.MISSING
        val owner = runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)
        if (owner && runCatching { dpm.isApplicationHidden(admin, pkg) }.getOrDefault(false)) return State.HIDDEN
        if (info.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED == 0) return State.HIDDEN
        if (!info.enabled) return State.DISABLED
        if (info.flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED != 0) return State.SUSPENDED
        if (pm.getLaunchIntentForPackage(pkg) == null) return State.NO_ICON
        return State.OK
    }

    /** Shows and enables every personalization app the device owner can. Returns what it changed, for the log. */
    fun restore(): List<String> {
        if (!runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)) return emptyList()
        val changed = mutableListOf<String>()
        for (pkg in Personalization.ALL) {
            when (state(pkg)) {
                State.HIDDEN -> if (runCatching { dpm.setApplicationHidden(admin, pkg, false) }.getOrDefault(false)) changed += "showed $pkg"
                State.DISABLED -> if (runCatching { dpm.enableSystemApp(admin, pkg) }.isSuccess) changed += "enabled $pkg"
                State.SUSPENDED -> if (runCatching { dpm.setPackagesSuspended(admin, arrayOf(pkg), false) }.getOrNull()?.isEmpty() == true) changed += "opened $pkg"
                else -> Unit
            }
        }
        return changed
    }

    /** Opens the Themes app directly (not through Settings), or null when the phone has none that opens. */
    fun themesIntent(): Intent? = (pm.getLaunchIntentForPackage(THEMES)
        ?: Intent(Intent.ACTION_MAIN).setClassName(THEMES, "com.android.thememanager.ThemeResourceTabActivity").takeIf { it.resolveActivity(pm) != null })
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Android's own wallpaper picker, when the phone has one. */
    fun wallpaperPickerIntent(): Intent? =
        Intent(Intent.ACTION_SET_WALLPAPER).takeIf { it.resolveActivity(pm) != null }?.let { Intent.createChooser(it, "Wallpaper").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    /** Sets a photo as the wallpaper of the home screen, the lock screen or both. Blocks: call off the main thread. */
    fun setWallpaper(uri: Uri, home: Boolean, lock: Boolean) {
        val flags = (if (home) WallpaperManager.FLAG_SYSTEM else 0) or (if (lock) WallpaperManager.FLAG_LOCK else 0)
        context.contentResolver.openInputStream(uri).use { input ->
            WallpaperManager.getInstance(context).setStream(checkNotNull(input) { "the photo could not be read" }, null, true, flags)
        }
    }

    fun describe(state: State): String = when (state) {
        State.MISSING -> "The Themes app is not on this phone (uninstalled, or this phone has none)."
        State.HIDDEN -> "HyperOS hid the Themes app. Lock Me In shows it again at each pass; if it stays hidden, HyperOS is undoing that."
        State.DISABLED -> "The Themes app is switched off. Lock Me In switches it on at each pass."
        State.SUSPENDED -> "The Themes app is closed. It should never be: Lock Me In opens it at each pass."
        State.NO_ICON -> "The Themes app is installed but has no icon to open. Use the buttons below."
        State.OK -> "The Themes app is installed and open. If Settings still says \"company policy\", that is HyperOS refusing a phone with a device owner, not Lock Me In."
    }

    companion object {
        const val THEMES = "com.android.thememanager"
    }
}
