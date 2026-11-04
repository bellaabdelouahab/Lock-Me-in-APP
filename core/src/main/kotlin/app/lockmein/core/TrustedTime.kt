package app.lockmein.core

/**
 * A moment the network vouched for, pinned to the phone's time since boot. Time since boot can't be set by hand, so the
 * network time plus how long the phone has been running since is the real time, whatever the phone's clock says.
 */
data class TimeAnchor(val networkMs: Long, val elapsedMs: Long, val boot: Int)

object TrustedTime {
    /** The real time now, or null when the anchor can't vouch for it (none yet, another boot, or a counter that went back). */
    fun at(anchor: TimeAnchor?, elapsedMs: Long, boot: Int): Long? =
        anchor?.takeIf { it.boot == boot && elapsedMs >= it.elapsedMs }?.let { it.networkMs + (elapsedMs - it.elapsedMs) }

    /** True when the anchor should be renewed from the network: unusable, or older than [maxAgeMs] (the counter drifts a little). */
    fun stale(anchor: TimeAnchor?, elapsedMs: Long, boot: Int, maxAgeMs: Long): Boolean =
        at(anchor, elapsedMs, boot) == null || elapsedMs - anchor!!.elapsedMs > maxAgeMs
}
