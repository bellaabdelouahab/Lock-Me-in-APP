package app.lockmein.core

/** TEST build: removable over USB, debugging stays on. SEALED build: the real lock. */
enum class BuildMode { TEST, SEALED }

/** Android user-restriction keys (android.os.UserManager.DISALLOW_*), kept as plain strings so this module stays free of Android. */
object Restrictions {
    const val DEBUGGING = "no_debugging_features"
    const val SAFE_BOOT = "no_safe_boot"
    const val FACTORY_RESET = "no_factory_reset"
    const val CONFIG_DATE_TIME = "no_config_date_time"
    const val ADD_USER = "no_add_user"
    const val ADD_MANAGED_PROFILE = "no_add_managed_profile"
    const val ADD_PRIVATE_PROFILE = "no_add_private_profile"
    const val CONFIG_VPN = "no_config_vpn"
    const val INSTALL_UNKNOWN_SOURCES_GLOBALLY = "no_install_unknown_sources_globally"
    const val CONFIG_PRIVATE_DNS = "disallow_config_private_dns"
}

/** What the owner chose (docs/DECISIONS.md), as package names. */
data class AppRules(
    val appPackage: String,
    val allowedBrowser: String,
    /** Every browser package installed or known; all but [allowedBrowser] are suspended unless approved. */
    val browsers: Set<String>,
    val youtubeApp: String = "com.google.android.youtube",
    val youtubeMusic: String = "com.google.android.apps.youtube.music",
    /** Phone, Messages, Clock, Authenticator and the local audio player: the only apps that run at night. */
    val nightAllowlist: Set<String>,
    /** Launcher, keyboard, SystemUI, Settings, dialer: never suspended, in any phase. */
    val protectedPackages: Set<String>,
    /** Apps the owner blocked for good: suspended in every phase, the open window included. */
    val alwaysBlocked: Set<String> = emptySet(),
)

data class Inventory(
    val installed: Set<String>,
    /** Browsers whose 30-minute request has been approved. */
    val approvedBrowsers: Set<String> = emptySet(),
    /** New apps that arrived since the last open window, suspended until reviewed. */
    val quarantined: Set<String> = emptySet(),
)

data class PolicyPlan(
    val restrictions: Set<String>,
    val privateDnsHost: String,
    val uninstallBlocked: Set<String>,
    val userControlDisabled: Set<String>,
    val suspended: Set<String>,
    val unsuspended: Set<String>,
)

/** Turns the phase, the build mode and what is installed into the exact policy to apply. Pure, so it is tested here. */
class PolicyPlanner(private val rules: AppRules, private val privateDnsHost: String) {
    fun plan(phase: Phase, mode: BuildMode, inventory: Inventory): PolicyPlan {
        val suspended = suspendedFor(phase, inventory)
        val managed = inventory.installed - rules.protectedPackages - rules.appPackage
        return PolicyPlan(
            restrictions = restrictionsFor(mode),
            privateDnsHost = privateDnsHost,
            uninstallBlocked = setOf(rules.appPackage),
            userControlDisabled = setOf(rules.appPackage),
            suspended = suspended,
            unsuspended = managed - suspended,
        )
    }

    private fun restrictionsFor(mode: BuildMode): Set<String> {
        val always = setOf(
            Restrictions.CONFIG_PRIVATE_DNS,
            Restrictions.SAFE_BOOT,
            Restrictions.CONFIG_DATE_TIME,
            Restrictions.ADD_USER,
            Restrictions.ADD_MANAGED_PROFILE,
            Restrictions.ADD_PRIVATE_PROFILE,
            Restrictions.CONFIG_VPN,
            Restrictions.INSTALL_UNKNOWN_SOURCES_GLOBALLY,
        )
        // The sealed build also closes USB debugging and the factory reset in Settings; the test build leaves a way back.
        return if (mode == BuildMode.SEALED) always + Restrictions.DEBUGGING + Restrictions.FACTORY_RESET else always
    }

    private fun suspendedFor(phase: Phase, inventory: Inventory): Set<String> {
        val managed = inventory.installed - rules.protectedPackages - rules.appPackage
        val otherBrowsers = (rules.browsers - rules.allowedBrowser - inventory.approvedBrowsers).intersect(managed)
        val quarantined = inventory.quarantined.intersect(managed)
        val youtube = if (phase == Phase.OPEN_WINDOW) emptySet() else setOf(rules.youtubeApp).intersect(managed)
        val nightOnly = if (phase == Phase.NIGHT) managed - rules.nightAllowlist else emptySet()
        val musicAtNight = if (phase == Phase.NIGHT) setOf(rules.youtubeMusic).intersect(managed) else emptySet()
        val blocked = rules.alwaysBlocked.intersect(managed)
        return otherBrowsers + quarantined + youtube + nightOnly + musicAtNight + blocked
    }
}
