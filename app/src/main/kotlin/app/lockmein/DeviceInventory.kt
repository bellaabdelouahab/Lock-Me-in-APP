package app.lockmein

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager

/** What is installed, which of it is a browser, and which packages the phone cannot work without. */
data class DeviceSnapshot(
    val installed: Set<String>,
    val browsers: Set<String>,
    val protectedPackages: Set<String>,
)

data class AppInfo(val packageName: String, val label: String)

class DeviceInventory(private val context: Context) {
    private val pm: PackageManager = context.packageManager

    /** Apps with an icon, with the name people know them by. */
    fun launchableApps(): List<AppInfo> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun label(packageName: String): String = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)

    /** Feed and video apps, the usual night pull. Used only to pre-tick the setup list. */
    fun suggestedNightApps(): Set<String> = launchableApps().map { it.packageName }.filter { app.lockmein.core.NightRisk.warning(it) != null }.toSet()

    fun read(): DeviceSnapshot {
        val installed = pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS).map { it.packageName }.toSet()
        return DeviceSnapshot(installed, browsers(), protectedPackages(installed))
    }

    /** Anything that opens a plain web link as a browser does. */
    private fun browsers(): Set<String> {
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val opensAnyLink = pm.queryIntentActivities(web, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }.toSet()
        // Only apps that call themselves a browser: work apps (Teams, Outlook...) can also open plain links.
        val browserApps = pm.queryIntentActivities(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER), PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.toSet()
        return opensAnyLink.intersect(browserApps)
    }

    /**
     * A system app with no icon is a background service (telephony, providers, Play services): suspending it breaks the phone,
     * so it is protected. System apps with an icon (Gallery, Play Store, video) are apps like any other and can be suspended.
     * The launcher, dialer, messages, keyboard, Settings and SystemUI are protected by name.
     */
    private fun protectedPackages(installed: Set<String>): Set<String> {
        val withIcon = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.toSet()
        val backgroundSystem = installed.filter { name ->
            val info = runCatching { pm.getApplicationInfo(name, 0) }.getOrNull()
            info != null && info.flags and ApplicationInfo.FLAG_SYSTEM != 0 && name !in withIcon
        }.toSet()
        val testPackages = installed.filter { it == "${context.packageName}.test" || it.endsWith(".test") }.toSet()
        return backgroundSystem + named() + testPackages
    }

    private fun named(): Set<String> {
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        val keyboard = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        val dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        return setOfNotNull(
            home?.activityInfo?.packageName,
            keyboard,
            dialer,
            Telephony.Sms.getDefaultSmsPackage(context),
            "com.android.settings",
            "com.android.systemui",
            "com.android.shell",
            context.packageName,
            "${context.packageName}.test",
        )
    }
}
