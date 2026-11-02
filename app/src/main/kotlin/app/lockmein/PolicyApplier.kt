package app.lockmein

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import app.lockmein.core.PolicyPlan
import app.lockmein.core.Restrictions

/** What the phone refused or could not do, for the log. Empty means the plan is in force. */
data class ApplyReport(val failed: List<String>) {
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

    fun apply(plan: PolicyPlan): ApplyReport {
        check(dpm.isDeviceOwnerApp(packageName)) { "this app is not the device owner" }
        val failed = mutableListOf<String>()

        for (key in ALL_RESTRICTIONS) {
            if (key in plan.restrictions) dpm.addUserRestriction(admin, key) else dpm.clearUserRestriction(admin, key)
        }

        // The DNS call is refused while the Private DNS restriction is on, so the host is set first.
        dpm.clearUserRestriction(admin, Restrictions.CONFIG_PRIVATE_DNS)
        val dns = setDnsHost(plan.privateDnsHost)
        if (dns != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) failed += "private dns host ${plan.privateDnsHost}: code $dns"
        if (Restrictions.CONFIG_PRIVATE_DNS in plan.restrictions) dpm.addUserRestriction(admin, Restrictions.CONFIG_PRIVATE_DNS)

        for (pkg in plan.uninstallBlocked) dpm.setUninstallBlocked(admin, pkg, true)
        dpm.setUserControlDisabledPackages(admin, plan.userControlDisabled.toList())

        failed += dpm.setPackagesSuspended(admin, plan.suspended.toTypedArray(), true).map { "suspend $it" }
        failed += dpm.setPackagesSuspended(admin, plan.unsuspended.toTypedArray(), false).map { "unsuspend $it" }
        return ApplyReport(failed)
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
