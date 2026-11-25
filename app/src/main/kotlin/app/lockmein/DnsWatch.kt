package app.lockmein

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import app.lockmein.core.DnsFailover
import app.lockmein.core.EventKind

/**
 * The DNS fallback (FEATURES 3.17), run once a minute by [FilterWatcher]. It probes only when something looks wrong: the
 * network is not validated while the server is pinned (Android's own sign that the private DNS fails), or the phone has
 * fallen back. On a change it logs it and runs a policy pass, which pins the server or sets automatic DNS.
 */
object DnsWatch {
    private const val TAG = "DnsWatch"
    @Volatile private var lastProbe = 0L

    /** Blocks while probing; called off the main thread. */
    fun tick(context: Context) {
        if (BuildConfig.PREVIEW) return
        val store = ConfigStore(context)
        if (!store.protectionActive) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val config = store.load() ?: return
        val state = LockState(context)
        val failover = state.dnsFailover
        val now = System.currentTimeMillis()
        if (now - lastProbe < DnsFailover.PROBE_EVERY_MS - 5_000) return
        // Only the host Android actually took can be what breaks the network: a host it refused (it kept the old one) is never probed
        // into a fall back to unfiltered DNS.
        val admin = android.content.ComponentName(context, AdminReceiver::class.java)
        if (!failover.isFallback && dpm.getGlobalPrivateDnsHost(admin) != config.dnsHost) return
        if (!failover.isFallback && !looksBroken(context)) {
            if (failover.fails > 0) state.dnsFailover = failover.copy(fails = 0, firstFailAt = 0)
            return
        }
        lastProbe = now
        val (serverOk, networkOk) = DnsProbe.check(context, config.dnsHost)
        val (next, change) = failover.after(serverOk, networkOk, now)
        state.dnsFailover = next
        when (change) {
            DnsFailover.Change.NONE -> return
            DnsFailover.Change.FALL_BACK -> EventLog(context).add(EventKind.DNS_FALLBACK, "${config.dnsHost} did not answer 3 times in a minute while the network worked")
            DnsFailover.Change.RESTORE -> {
                val minutes = (now - (failover.fallbackSince ?: now)) / 60_000
                EventLog(context).add(EventKind.DNS_RESTORED, "${config.dnsHost} answers again; $minutes min on automatic DNS")
            }
        }
        Log.i(TAG, "DNS $change")
        runCatching { PolicyRunner(context).runIfActive() }.onFailure { Log.w(TAG, "pass after DNS $change failed", it) }
    }

    /** A network that is up but not validated: with the private DNS pinned, the usual sign that the server does not answer. */
    private fun looksBroken(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
