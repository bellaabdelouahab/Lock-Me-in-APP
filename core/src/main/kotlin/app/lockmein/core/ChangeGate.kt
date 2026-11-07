package app.lockmein.core

import java.time.Duration
import java.time.Instant

enum class ChangeKind {
    /** Makes the phone more closed. Always allowed. */
    TIGHTEN,

    /** Opens something up: an app, a site, a lock, a setting. Must be requested and reviewed on Sunday, or done via friend's code. */
    LOOSEN,
}

sealed interface Decision {
    data class Allowed(val why: String) : Decision
    data class Denied(val why: String) : Decision
}

/**
 * Rules for approving and reviewing changes.
 *
 * Tightening is instant at any time.
 * Loosening requires a request reviewed on Sunday 20:00-21:00 (must be requested before the window starts).
 * Requests made during the Sunday window wait until next Sunday.
 */
class ChangeGate(
    private val schedule: Schedule,
    private val clockGuard: ClockGuard = ClockGuard(),
) {
    fun decide(kind: ChangeKind): Decision = when (kind) {
        ChangeKind.TIGHTEN -> Decision.Allowed("tightening is always allowed")
        ChangeKind.LOOSEN -> Decision.Denied("opening something up must be requested and reviewed on Sunday")
    }

    /**
     * Decides whether a waiting request may be approved right now on Sunday.
     * Requests made before the current Sunday window start are approvable.
     * Requests made during the window wait until next Sunday.
     */
    fun decideSundayReview(request: ChangeRequest, now: Instant, networkTime: Instant? = null): Decision {
        if (networkTime != null) {
            when (clockGuard.trust(now, networkTime)) {
                ClockTrust.DISAGREE -> return Decision.Denied("the phone clock and the network clock disagree")
                ClockTrust.UNKNOWN -> return Decision.Denied("no network time to confirm the clock")
                else -> Unit
            }
        }
        if (!schedule.inSettingsWindow(now)) {
            return Decision.Denied("requests can only be approved during the Sunday settings window (20:00 to 21:00)")
        }
        val windowStart = schedule.currentSettingsWindowStart(now)
            ?: return Decision.Denied("not in Sunday settings window")
        if (request.requestedAt.isBefore(windowStart)) {
            return Decision.Allowed("requested before Sunday settings window")
        }
        return Decision.Denied("requests made during the Sunday window wait until next Sunday")
    }

    /** Alias for Sunday review. */
    fun decideApply(request: ChangeRequest, now: Instant, networkTime: Instant? = null): Decision =
        decideSundayReview(request, now, networkTime)

    /** Giving up or dismissing a request is always allowed. */
    fun decideDismiss(request: ChangeRequest): Decision = Decision.Allowed("dismissing a request is always allowed")
}
