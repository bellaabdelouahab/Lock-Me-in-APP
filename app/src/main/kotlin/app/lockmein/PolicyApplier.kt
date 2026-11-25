package app.lockmein

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import app.lockmein.core.ChromePolicy
import app.lockmein.core.PolicyPlan
import app.lockmein.core.Restrictions

/** What the phone refused or could not do, for the log. Empty means the plan is in force. */
data class ApplyReport(
    val failed: List<String>,
    val unsuspendable: List<String> = emptyList(),
    /** Android refused the planned DNS host and still holds this other host, the last one it accepted. */
    val dnsRefused: DnsRefusal? = null,
) {
    val ok get() = failed.isEmpty()
}

/** A DNS host Android would not take ([code] from DevicePolicyManager), and the host it kept. */
data class DnsRefusal(val host: String, val code: Int, val kept: String) {
    val reason get() = PolicyApplier.dnsError(code)
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

        // Restrictions first, the Private DNS lock among them: it is never lifted while the protection is on. The device owner sets
        // the DNS with the lock in place (the lock only closes the Settings page), so no pass, even one killed halfway, leaves it open.
        for (key in ALL_RESTRICTIONS) {
            if (key in plan.restrictions) dpm.addUserRestriction(admin, key) else dpm.clearUserRestriction(admin, key)
        }
        var dnsRefused: DnsRefusal? = null

        val host = plan.privateDnsHost
        if (host == null) {
            // The server is down (DnsWatch): plain automatic DNS until it answers again, as he chose (FEATURES 3.17).
            if (dpm.getGlobalPrivateDnsMode(admin) != DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC) {
                val code = dpm.setGlobalPrivateDnsModeOpportunistic(admin)
                if (code != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) failed += "automatic DNS: Android refused (code $code)"
            }
        } else {
            // Already pinned to the host: nothing to set. Setting it again makes Android test the server, which fails whenever the phone
            // is offline (asleep at night, no Wi-Fi) and reported a failure every pass while the DNS was in fact in force.
            val pinned = dpm.getGlobalPrivateDnsMode(admin) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME &&
                dpm.getGlobalPrivateDnsHost(admin) == host
            if (!pinned) {
                val dns = setDnsHost(host)
                if (dns != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                    failed += "private DNS $host: ${dnsError(dns)}${if (dns == DNS_THREW) " ($lastDnsError)" else ""}"
                    // Android keeps the host it had. When that is another named host, it is the one that works: the caller goes back to it.
                    val kept = dpm.getGlobalPrivateDnsHost(admin)
                    if (dpm.getGlobalPrivateDnsMode(admin) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME && !kept.isNullOrBlank() && kept != host) {
                        dnsRefused = DnsRefusal(host, dns, kept)
                    }
                }
            }
        }

        // The clock and the zone come from the network and cannot be changed by hand (the restriction above closes the Settings page).
        dpm.setAutoTimeEnabled(admin, true)
        dpm.setAutoTimeZoneEnabled(admin, true)

        dpm.setShortSupportMessage(admin, SUPPORT_MESSAGE)

        dpm.setApplicationRestrictions(admin, ChromePolicy.PACKAGE, if (plan.managedChrome) chromeBundle() else Bundle())
        // Edge and Brave read the same Chromium policies: private windows off, their own DNS off, safe search forced.
        for (pkg in app.lockmein.core.KnownBrowsers.CHROMIUM - ChromePolicy.PACKAGE) {
            runCatching { dpm.setApplicationRestrictions(admin, pkg, if (pkg in plan.managedBrowsers) chromeBundle() else Bundle()) }
        }

        for (pkg in plan.uninstallBlocked) dpm.setUninstallBlocked(admin, pkg, true)
        dpm.setUserControlDisabledPackages(admin, plan.userControlDisabled.toList())

        // Android refuses to suspend some system apps for any app (on Xiaomi: Security, the Play Store). That is a limit of the
        // phone, not a failure of the rules: noted apart, and left out of the next plans. Any other refusal is a failure.
        val refused = dpm.setPackagesSuspended(admin, plan.suspended.toTypedArray(), true).toList()
        val (system, other) = refused.partition { isSystemApp(it) }
        failed += other.map { "suspend $it" }
        failed += dpm.setPackagesSuspended(admin, plan.unsuspended.toTypedArray(), false).map { "unsuspend $it" }
        return ApplyReport(failed, system, dnsRefused)
    }

    /**
     * Switches the private DNS to [host] for a settings change, with the lock left on. Android tests the host first and keeps the
     * old one when it does not answer DNS-over-TLS. Returns null when [host] is in force (read back from Android), or why not.
     */
    fun switchDns(host: String): String? {
        check(dpm.isDeviceOwnerApp(packageName)) { "this app is not the device owner" }
        dpm.addUserRestriction(admin, Restrictions.CONFIG_PRIVATE_DNS)
        val code = setDnsHost(host, attempts = 2)
        if (code != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) return if (code == DNS_THREW) "Android threw $lastDnsError" else dnsError(code)
        val now = dpm.getGlobalPrivateDnsHost(admin)
        return if (dpm.getGlobalPrivateDnsMode(admin) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME && now == host) null
        else "Android reported success but holds ${now ?: "no host"}"
    }

    /**
     * Takes every policy this app set back off, so the phone is as it was: no restrictions, nothing suspended, uninstall allowed,
     * private DNS back to automatic. Used when the protection is removed, before this app stops being the device owner.
     */
    fun release(suspended: Set<String>): ApplyReport {
        check(dpm.isDeviceOwnerApp(packageName)) { "this app is not the device owner" }
        val failed = mutableListOf<String>()
        for (key in ALL_RESTRICTIONS) dpm.clearUserRestriction(admin, key)
        dpm.setGlobalPrivateDnsModeOpportunistic(admin)
        dpm.setApplicationRestrictions(admin, ChromePolicy.PACKAGE, Bundle())
        for (pkg in app.lockmein.core.KnownBrowsers.CHROMIUM) runCatching { dpm.setApplicationRestrictions(admin, pkg, Bundle()) }
        dpm.setUninstallBlocked(admin, packageName, false)
        dpm.setUserControlDisabledPackages(admin, emptyList())
        dpm.setShortSupportMessage(admin, null)
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
    private fun setDnsHost(host: String, attempts: Int = DNS_ATTEMPTS): Int {
        var code = DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING
        repeat(attempts) { attempt ->
            code = setDnsHostOnce(host)
            if (code == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) return code
            if (attempt < attempts - 1) Thread.sleep(DNS_PAUSE_MS)
        }
        return code
    }

    /**
     * One try, given at most [DNS_TIMEOUT_MS]: Android's own test of the host can hang on a server that never answers, and a pass
     * stuck there was killed by Android before it finished. A try that runs out counts as the host not serving.
     */
    private fun setDnsHostOnce(host: String): Int {
        val task = java.util.concurrent.FutureTask { dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, host) }
        Thread(task, "lock-me-in-dns").apply { isDaemon = true }.start()
        return try {
            task.get(DNS_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            DNS_TIMED_OUT
        } catch (e: java.util.concurrent.ExecutionException) {
            lastDnsError = e.cause?.toString()
            DNS_THREW
        }
    }

    /** What Android threw on the last try, for the reason given. */
    private var lastDnsError: String? = null

    companion object {
        private const val DNS_ATTEMPTS = 3
        private const val DNS_PAUSE_MS = 5_000L
        const val DNS_TIMEOUT_MS = 10_000L

        /** Not Android's codes: a try that ran out of time, or one where Android threw. */
        const val DNS_TIMED_OUT = -2
        const val DNS_THREW = -3

        fun dnsError(code: Int) = when (code) {
            DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING -> "the host did not answer DNS-over-TLS"
            DNS_TIMED_OUT -> "Android's test of the host took over 10 s"
            DNS_THREW -> "Android threw an error"
            DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING -> "Android could not save it"
            else -> "Android refused (code $code)"
        }

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
            Restrictions.CONFIG_PRIVATE_DNS,
        )
    }
}
