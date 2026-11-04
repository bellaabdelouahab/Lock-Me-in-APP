package app.lockmein

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import app.lockmein.core.ChromePolicy
import app.lockmein.core.EventKind
import app.lockmein.core.Restrictions
import java.time.Duration
import java.time.Instant

/** One thing the protection depends on, as the phone reports it right now. */
data class HealthItem(val title: String, val ok: Boolean, val detail: String)

/**
 * Reads back from Android what is really in force and compares it with what the rules say should be, so the app never claims to
 * protect the phone when it does not. Nothing here trusts the app's own bookkeeping; each line asks the system.
 * Blocks (reads every app), so call it off the main thread.
 */
class Health(private val context: Context) {
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, AdminReceiver::class.java)

    fun check(now: Instant = TrustedClock(context).now()): List<HealthItem> {
        val config = ConfigStore(context).load() ?: return listOf(HealthItem("Setup", false, "The setup is not finished."))
        if (BuildConfig.PREVIEW) return listOf(HealthItem("Preview build", false, "This build only shows the screens. Nothing is enforced on this phone."))
        val owner = dpm.isDeviceOwnerApp(context.packageName)
        if (!owner) return listOf(HealthItem("Device owner", false, "This app is not the device owner, so Android enforces none of the rules."))

        val plan = PolicyRunner(context).planFor(config, now)
        val restrictions = context.getSystemService(UserManager::class.java).userRestrictions
        val items = mutableListOf(HealthItem("Device owner", true, "Android's management layer holds the rules, even with this app closed."))

        val dnsMode = dpm.getGlobalPrivateDnsMode(admin)
        val dnsHost = dpm.getGlobalPrivateDnsHost(admin)
        items += HealthItem(
            "Private DNS",
            dnsMode == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME && dnsHost == config.dnsHost && restrictions.getBoolean(Restrictions.CONFIG_PRIVATE_DNS),
            when {
                dnsMode != DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME -> "Not pinned: the phone does not use your DNS host."
                dnsHost != config.dnsHost -> "Pinned to $dnsHost instead of ${config.dnsHost}."
                !restrictions.getBoolean(Restrictions.CONFIG_PRIVATE_DNS) -> "Uses ${config.dnsHost}, but the setting is not locked."
                else -> "Pinned to ${config.dnsHost} and locked."
            },
        )

        // Private space (and its restriction) exists from Android 15; older versions have nothing to close.
        val applicable = plan.restrictions.filterNot { it == Restrictions.ADD_PRIVATE_PROFILE && android.os.Build.VERSION.SDK_INT < 35 }
        val missing = applicable.filterNot { restrictions.getBoolean(it) }
        items += HealthItem(
            "Phone restrictions",
            missing.isEmpty(),
            if (missing.isEmpty()) "${applicable.size} restrictions in force (VPN, users, unknown apps, safe mode...)." else "Not in force: ${missing.joinToString { it.removePrefix("no_").removePrefix("disallow_").replace('_', ' ') }}.",
        )

        val time = dpm.getAutoTimeEnabled(admin) && dpm.getAutoTimeZoneEnabled(admin)
        items += HealthItem("Automatic time", time, if (time) "Time and zone come from the network." else "Automatic time or zone is off: the schedule can be fooled.")

        val clock = TrustedClock(context)
        val drift = clock.drift()
        items += HealthItem(
            "Network time",
            drift != null && drift < Duration.ofMinutes(5),
            when {
                drift == null -> "No network time since the phone started: the rules follow the phone's clock until it is online."
                drift >= Duration.ofMinutes(5) -> "The phone's clock is ${drift.toMinutes()} min off. The rules ignore it and follow network time."
                else -> "The rules follow network time; the phone's clock agrees."
            },
        )

        val uninstall = dpm.isUninstallBlocked(admin, context.packageName)
        items += HealthItem("Can't be uninstalled", uninstall, if (uninstall) "The uninstall button is blocked for Lock Me In." else "Lock Me In can be uninstalled right now.")

        val untouchable = context.packageName in dpm.getUserControlDisabledPackages(admin)
        items += HealthItem("Can't be stopped or wiped", untouchable, if (untouchable) "Force stop and Clear data are blocked for Lock Me In." else "Lock Me In can be force-stopped or its data cleared from Settings.")

        // Tampering from outside the phone's screens: a computer (USB debugging), a factory reset, safe mode (which skips this app).
        val adbOn = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) == 1
        items += if (BuildConfig.TEST_BUILD) HealthItem("Computer access", true, if (adbOn) "Test build: USB debugging stays on so the test build can be removed. The sealed build closes it." else "USB debugging is off.")
        else HealthItem(
            "Computer access",
            !adbOn && restrictions.getBoolean(Restrictions.DEBUGGING),
            when {
                adbOn -> "USB debugging is on: a computer can remove the protection."
                !restrictions.getBoolean(Restrictions.DEBUGGING) -> "USB debugging is off but not locked."
                else -> "USB debugging is off and locked."
            },
        )
        val reset = BuildConfig.TEST_BUILD || restrictions.getBoolean(Restrictions.FACTORY_RESET)
        items += HealthItem("Factory reset", reset, if (BuildConfig.TEST_BUILD) "Test build: factory reset stays possible. The sealed build blocks it." else if (reset) "Factory reset from Settings is blocked." else "Factory reset is possible: it removes everything.")
        val safe = restrictions.getBoolean(Restrictions.SAFE_BOOT)
        items += HealthItem("Safe mode", safe, if (safe) "Safe mode (which would start without Lock Me In) is blocked." else "Safe mode is possible and starts the phone without the rules.")

        val pm = context.packageManager
        val stillOpen = plan.suspended.filter { pkg -> runCatching { !pm.isPackageSuspended(pkg) }.getOrDefault(false) }
        items += HealthItem(
            "Apps closed now",
            stillOpen.isEmpty(),
            when {
                stillOpen.isNotEmpty() -> "Should be closed but open: ${stillOpen.take(4).joinToString { DeviceInventory(context).label(it) }}${if (stillOpen.size > 4) " and ${stillOpen.size - 4} more" else ""}."
                plan.suspended.isEmpty() -> "Nothing has to be closed at this hour."
                else -> "All ${plan.suspended.size} apps that should be closed are closed."
            },
        )

        if (plan.managedChrome) {
            val chrome = !dpm.getApplicationRestrictions(admin, ChromePolicy.PACKAGE).isEmpty
            items += HealthItem("Chrome settings", chrome, if (chrome) "Private windows off, safe search on, YouTube strict." else "Chrome is not configured: private windows and its own DNS work.")
        }

        val lastOk = EventLog(context).read().lastOrNull { it.kind == EventKind.PASS_OK || it.kind == EventKind.PASS_FAILED }
        val fresh = lastOk != null && lastOk.kind == EventKind.PASS_OK && Duration.between(lastOk.at, now) < WATCHDOG_LATE
        items += HealthItem(
            "Watchdog",
            fresh,
            when {
                lastOk == null -> "The rules were never applied."
                lastOk.kind == EventKind.PASS_FAILED -> "The last pass failed: ${lastOk.detail.ifBlank { "unknown reason" }}."
                !fresh -> "No pass for ${Duration.between(lastOk.at, now).toMinutes()} minutes. It should run every 15."
                else -> "Rules re-applied ${Duration.between(lastOk.at, now).toMinutes()} min ago; it runs at least every 15."
            },
        )
        return items
    }

    companion object {
        /** A pass runs at least every 15 minutes; past this, the alarm chain is broken. */
        val WATCHDOG_LATE: Duration = Duration.ofMinutes(35)
    }
}
