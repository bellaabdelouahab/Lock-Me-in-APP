package app.lockmein

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import app.lockmein.core.ChromePolicy
import app.lockmein.core.PolicyPlan
import app.lockmein.core.Restrictions

/** What the phone refused or could not do, for the log. Empty means the plan is in force. */
data class ApplyReport(val failed: List<String>, val unsuspendable: List<String> = emptyList()) {
    val ok get() = failed.isEmpty()
}

/**
 * Writes a [PolicyPlan] into Android's own device policy. The policy then holds without this app running.
 * Blocks (the DNS call checks the resolver), so call it off the main thread.
 */
class PolicyApplier(context: Context) {
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, AdminReceiver::class.java)
    private val packageName = context.packageName
    private val pm = context.packageManager

    fun apply(plan: PolicyPlan): ApplyReport {
        check(dpm.isDeviceOwnerApp(packageName)) { "this app is not the device owner" }
        val failed = mutableListOf<String>()

        for (key in ALL_RESTRICTIONS) {
            if (key in plan.restrictions) dpm.addUserRestriction(admin, key) else dpm.clearUserRestriction(admin, key)
        }

        // Already pinned to the host: nothing to set. Setting it again makes Android test the server, which fails whenever the phone
        // is offline (asleep at night, no Wi-Fi) and reported a failure every pass while the DNS was in fact in force.
        val pinned = dpm.getGlobalPrivateDnsMode(admin) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME &&
            dpm.getGlobalPrivateDnsHost(admin) == plan.privateDnsHost
        if (!pinned) {
            // The DNS call is refused while the Private DNS restriction is on, so the host is set first.
            dpm.clearUserRestriction(admin, Restrictions.CONFIG_PRIVATE_DNS)
            val dns = setDnsHost(plan.privateDnsHost)
            if (dns == DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING) failed += "private DNS ${plan.privateDnsHost} did not answer (offline?), tried again at the next pass"
            else if (dns != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) failed += "private DNS ${plan.privateDnsHost}: Android refused (code $dns)"
        }
        if (Restrictions.CONFIG_PRIVATE_DNS in plan.restrictions) dpm.addUserRestriction(admin, Restrictions.CONFIG_PRIVATE_DNS)

        // The clock and the zone come from the network and cannot be changed by hand (the restriction above closes the Settings page).
        dpm.setAutoTimeEnabled(admin, true)
        dpm.setAutoTimeZoneEnabled(admin, true)

        dpm.setApplicationRestrictions(admin, ChromePolicy.PACKAGE, if (plan.managedChrome) chromeBundle() else Bundle())

        for (pkg in plan.uninstallBlocked) dpm.setUninstallBlocked(admin, pkg, true)
        dpm.setUserControlDisabledPackages(admin, plan.userControlDisabled.toList())

        // Android refuses to suspend some system apps for any app (on Xiaomi: Security, the Play Store). That is a limit of the
        // phone, not a failure of the rules: noted apart, and left out of the next plans. Any other refusal is a failure.
        val refused = dpm.setPackagesSuspended(admin, plan.suspended.toTypedArray(), true).toList()
        val (system, other) = refused.partition { isSystemApp(it) }
        failed += other.map { "suspend $it" }
        failed += dpm.setPackagesSuspended(admin, plan.unsuspended.toTypedArray(), false).map { "unsuspend $it" }
        return ApplyReport(failed, system)
    }

    /**
     * Takes every policy this app set back off, so the phone is as it was: no restrictions, nothing suspended, uninstall allowed,
     * private DNS back to automatic. Used when the protection is removed, before this app stops being the device owner.
     */
    fun release(suspended: Set<String>): ApplyReport {
        check(dpm.isDeviceOwnerApp(packageName)) { "this app is not the device owner" }
        val failed = mutableListOf<String>()
        for (key in ALL_RESTRICTIONS + Restrictions.CONFIG_PRIVATE_DNS) dpm.clearUserRestriction(admin, key)
        dpm.setGlobalPrivateDnsModeOpportunistic(admin)
        dpm.setApplicationRestrictions(admin, ChromePolicy.PACKAGE, Bundle())
        dpm.setUninstallBlocked(admin, packageName, false)
        dpm.setUserControlDisabledPackages(admin, emptyList())
        if (suspended.isNotEmpty()) failed += dpm.setPackagesSuspended(admin, suspended.toTypedArray(), false).map { "unsuspend $it" }
        return ApplyReport(failed)
    }

    private fun isSystemApp(pkg: String) = runCatching {
        pm.getApplicationInfo(pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
    }.getOrDefault(false)

    private fun chromeBundle() = Bundle().apply {
        ChromePolicy.STRINGS.forEach(::putString)
        ChromePolicy.INTS.forEach(::putInt)
        ChromePolicy.BOOLEANS.forEach(::putBoolean)
    }

    /**
     * Android only accepts the host after the DNS server answers, so right after boot (no network yet) the first tries can fail.
     * A few tries cover that; if all fail, the report says so and the watchdog tries again later.
     */
    private fun setDnsHost(host: String): Int {
        var code = DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING
        repeat(DNS_ATTEMPTS) { attempt ->
            code = dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, host)
            if (code == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) return code
            if (attempt < DNS_ATTEMPTS - 1) Thread.sleep(DNS_PAUSE_MS)
        }
        return code
    }

    companion object {
        private const val DNS_ATTEMPTS = 4
        private const val DNS_PAUSE_MS = 5_000L

        /** Every restriction the planner can set, so one that leaves the plan is cleared. */
        val ALL_RESTRICTIONS = listOf(
            Restrictions.DEBUGGING,
            Restrictions.SAFE_BOOT,
            Restrictions.FACTORY_RESET,
            Restrictions.CONFIG_DATE_TIME,
            Restrictions.ADD_USER,
            Restrictions.ADD_MANAGED_PROFILE,
            Restrictions.ADD_PRIVATE_PROFILE,
            Restrictions.CONFIG_VPN,
            Restrictions.INSTALL_UNKNOWN_SOURCES_GLOBALLY,
        )
    }
}
