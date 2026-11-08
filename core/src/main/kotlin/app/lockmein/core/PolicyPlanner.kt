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
    /** Null when no browser is meant to stay open. */
    val allowedBrowser: String?,
    /** Every browser installed; all but [allowedBrowser] are suspended unless approved. */
    val browsers: Set<String>,
    /** The apps the owner picked to close at night. Everything else keeps running. */
    val nightClosed: Set<String>,
    /** Launcher, keyboard, SystemUI, Settings, dialer: never suspended, in any phase. */
    val protectedPackages: Set<String>,
    /** Apps the owner blocked for good: suspended in every phase, the open window included. */
    val alwaysBlocked: Set<String> = emptySet(),
    /** Apps suspended except during the daily open window (20:00 to 21:30). */
    val windowApps: Set<String> = WINDOW_APPS_DEFAULT,
    /** Apps watched by the screen filter: suspended if the filter accessibility service is turned off. */
    val watchedApps: Set<String> = emptySet(),
)

data class Inventory(
    val installed: Set<String>,
    /** Browsers whose request waited its days and was carried out in the window. */
    val approvedBrowsers: Set<String> = emptySet(),
    /** New apps that arrived since the last open window, suspended until reviewed. */
    val quarantined: Set<String> = emptySet(),
    /** Temporary unblocks. */
    val temporarilyAllowed: Set<String> = emptySet(),
    /** Apps suspended temporarily by the screen filter (30 minutes). */
    val filterBlocked: Set<String> = emptySet(),
    /** Whether the screen filter accessibility service is enabled. If false, watched apps are suspended. */
    val filterServiceEnabled: Boolean = true,
)

data class PolicyPlan(
    val restrictions: Set<String>,
    val privateDnsHost: String,
    val uninstallBlocked: Set<String>,
    val userControlDisabled: Set<String>,
    val suspended: Set<String>,
    val unsuspended: Set<String>,
    /** Chrome gets managed configuration (secure DNS off, private windows off, safe search forced). */
    val managedChrome: Boolean,
)

/** Turns the phase, the build mode and what is installed into the exact policy to apply. Pure, so it is tested here. */
class PolicyPlanner(private val rules: AppRules, private val privateDnsHost: String) {
    fun plan(
        phase: Phase,
        mode: BuildMode,
        inventory: Inventory,
        dailyWindowOpen: Boolean = false,
    ): PolicyPlan {
        val suspended = suspendedFor(phase, inventory, dailyWindowOpen)
        val managed = inventory.installed - rules.protectedPackages - rules.appPackage
        return PolicyPlan(
            restrictions = restrictionsFor(mode),
            privateDnsHost = privateDnsHost,
            uninstallBlocked = setOf(rules.appPackage),
            userControlDisabled = setOf(rules.appPackage),
            suspended = suspended,
            unsuspended = managed - suspended,
            managedChrome = KnownBrowsers.CHROME in inventory.installed,
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

    private fun suspendedFor(phase: Phase, inventory: Inventory, dailyWindowOpen: Boolean): Set<String> {
        val managed = inventory.installed - rules.protectedPackages - rules.appPackage
        val otherBrowsers = ((rules.browsers + KnownBrowsers.ALL) - setOfNotNull(rules.allowedBrowser) - inventory.approvedBrowsers).intersect(managed)
        val quarantined = inventory.quarantined.intersect(managed)
        val window = if (!dailyWindowOpen) rules.windowApps.intersect(managed) else emptySet()
        val nightOnly = if (phase == Phase.NIGHT) rules.nightClosed.intersect(managed) else emptySet()
        val blocked = rules.alwaysBlocked.intersect(managed)
        val filterBlocked = inventory.filterBlocked.intersect(managed)
        val filterServiceOff = if (!inventory.filterServiceEnabled) rules.watchedApps.intersect(managed) else emptySet()
        return (otherBrowsers + quarantined + window + nightOnly + blocked + filterBlocked + filterServiceOff) - inventory.temporarilyAllowed
    }
}
