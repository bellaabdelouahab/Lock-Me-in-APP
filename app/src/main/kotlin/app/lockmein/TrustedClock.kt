package app.lockmein

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import app.lockmein.core.TimeAnchor
import app.lockmein.core.TrustedTime
import java.time.Duration
import java.time.Instant

/**
 * The time the rules follow: the network's, carried forward by the phone's time since boot, so changing the phone's clock moves
 * nothing. Renewed from the network at each policy pass when older than [RENEW]. Before the first network answer after a boot it
 * falls back to the phone's clock (automatic time is forced on and the date settings are locked, so that is the network's too).
 */
class TrustedClock(context: Context) {
    private val prefs = context.getSharedPreferences("lock_clock", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver

    private fun boot() = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, 0)

    private fun anchor(): TimeAnchor? = if (!prefs.contains(NET)) null
    else TimeAnchor(prefs.getLong(NET, 0), prefs.getLong(ELAPSED, 0), prefs.getInt(BOOT, -1))

    /** The trusted time, or the phone's clock when the network has not vouched for one since this boot. */
    fun now(): Instant = trustedNow() ?: Instant.now()

    /** Null when no network time is known since this boot. */
    fun trustedNow(): Instant? = TrustedTime.at(anchor(), SystemClock.elapsedRealtime(), boot())?.let(Instant::ofEpochMilli)

    /** How far the phone's own clock is from the trusted time, or null when there is no trusted time yet. */
    fun drift(): Duration? = trustedNow()?.let { Duration.between(it, Instant.now()).abs() }

    /** Asks the network when the anchor is missing or old. Blocks; call it off the main thread. */
    fun renewIfStale() {
        if (!TrustedTime.stale(anchor(), SystemClock.elapsedRealtime(), boot(), RENEW.toMillis())) return
        val elapsedBefore = SystemClock.elapsedRealtime()
        val network = NetworkClock.now() ?: return
        // Pinned to the middle of the request, so the wait for the answer doesn't skew it.
        val elapsed = (elapsedBefore + SystemClock.elapsedRealtime()) / 2
        prefs.edit().putLong(NET, network.toEpochMilli()).putLong(ELAPSED, elapsed).putInt(BOOT, boot()).apply()
    }

    companion object {
        val RENEW: Duration = Duration.ofHours(6)
        private const val NET = "network_ms"
        private const val ELAPSED = "elapsed_ms"
        private const val BOOT = "boot"
    }
}
