package app.lockmein.core

/**
 * Decides, every few seconds, whether the screen filter is really working: switched on in Accessibility AND answering.
 * The filter service sends a heartbeat every [BEAT_EVERY_MS]; a silent one is treated as off after [STALE_AFTER_MS].
 * It fails closed: once it says off, it says on again only after the service is back on AND [BEATS_TO_RECOVER] new heartbeats
 * arrived, so a service that flickers on and off cannot reopen the watched apps.
 *
 * Times are on a clock that stops while the phone sleeps (Android's uptime), so a phone waking up does not look like a silent filter.
 * Pure, so it is tested here.
 */
class FilterWatch(
    /** When watching started. The filter gets [STALE_AFTER_MS] from here to send its first heartbeat (the app just started). */
    startedAt: Long,
    /** Whether the filter was last known to be off (kept across restarts, so a phone that boots with it off stays closed). */
    wasDown: Boolean,
) {
    var down: Boolean = wasDown
        private set
    private var lastBeat: Long = startedAt
    private var beatsSinceDown = 0

    /** The filter service is alive. */
    fun beat(at: Long) {
        lastBeat = maxOf(lastBeat, at)
        if (down) beatsSinceDown++
    }

    /**
     * [listed]: the filter is switched on in Accessibility (Accessibility itself on, and our service in its list).
     * Returns true when the filter is working.
     */
    fun check(now: Long, listed: Boolean): Boolean {
        val silent = now - lastBeat > STALE_AFTER_MS
        if (!listed || silent) {
            if (!down) beatsSinceDown = 0
            down = true
            // Heartbeats sent while the filter was switched off or silent do not count toward reopening.
            if (!listed) beatsSinceDown = 0
        } else if (down && beatsSinceDown >= BEATS_TO_RECOVER) {
            down = false
        }
        return !down
    }

    companion object {
        const val BEAT_EVERY_MS = 5_000L
        const val STALE_AFTER_MS = 15_000L
        const val BEATS_TO_RECOVER = 2

        /** Whether the filter service is in Android's ENABLED_ACCESSIBILITY_SERVICES list (full or short component name). */
        fun isListed(accessibilityEnabled: Boolean, enabledServices: String?, packageName: String, serviceClass: String): Boolean {
            if (!accessibilityEnabled) return false
            val short = serviceClass.removePrefix(packageName)
            val names = setOf("$packageName/$serviceClass", "$packageName/$short")
            return enabledServices.orEmpty().split(':').any { it.trim() in names }
        }
    }
}
