package app.lockmein.core

import java.time.Duration
import java.time.Instant

enum class ClockTrust {
    /** The phone clock and the network clock agree. */
    AGREE,

    /** They differ by more than the tolerance: someone moved the clock, or the network lies. */
    DISAGREE,

    /** No network time to compare with. */
    UNKNOWN,
}

/**
 * Loosening a lock depends on the clock (windows, waits), so it needs proof the clock is right.
 * Tightening never does.
 */
class ClockGuard(private val tolerance: Duration = Duration.ofMinutes(5)) {
    fun trust(device: Instant, network: Instant?): ClockTrust {
        if (network == null) return ClockTrust.UNKNOWN
        return if (Duration.between(device, network).abs() <= tolerance) ClockTrust.AGREE else ClockTrust.DISAGREE
    }

    fun mayLoosen(device: Instant, network: Instant?): Boolean = trust(device, network) == ClockTrust.AGREE
}
