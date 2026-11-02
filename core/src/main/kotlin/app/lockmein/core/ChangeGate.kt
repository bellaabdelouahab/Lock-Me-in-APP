package app.lockmein.core

import java.time.Duration
import java.time.Instant

enum class ChangeKind {
    /** Makes the phone more closed. Always allowed. */
    TIGHTEN,

    /** Opens something up: an app, a site, a lock, a window. */
    LOOSEN,
}

sealed interface Decision {
    data class Allowed(val why: String) : Decision
    data class Denied(val why: String) : Decision
}

/** The one place that says whether a change may happen now. */
class ChangeGate(private val schedule: Schedule, private val clockGuard: ClockGuard = ClockGuard()) {
    fun decide(kind: ChangeKind, now: Instant, networkTime: Instant?, emergencyUntil: Instant? = null): Decision {
        if (kind == ChangeKind.TIGHTEN) return Decision.Allowed("tightening is always allowed")

        val trust = clockGuard.trust(now, networkTime)
        if (trust == ClockTrust.DISAGREE) return Decision.Denied("the phone clock and the network clock disagree")

        if (emergencyUntil != null && now < emergencyUntil) return Decision.Allowed("emergency window")

        if (trust == ClockTrust.UNKNOWN) return Decision.Denied("no network time to confirm the clock")
        return if (schedule.phaseAt(now) == Phase.OPEN_WINDOW) {
            Decision.Allowed("open window")
        } else {
            Decision.Denied("changes open at ${schedule.nextWindowStart(now)}")
        }
    }
}

/** A suspended browser the owner asked for: approved after a wait, and only at a daytime moment. */
class BrowserApproval(private val schedule: Schedule, private val wait: Duration = Duration.ofMinutes(30)) {
    fun approvalTime(requestedAt: Instant): Instant = schedule.nextDaytime(requestedAt.plus(wait))

    fun isApproved(requestedAt: Instant, now: Instant): Boolean = now >= approvalTime(requestedAt)

    /** The packages whose wait is over. */
    fun due(requests: Map<String, Instant>, now: Instant): Set<String> = requests.filterValues { isApproved(it, now) }.keys

    /** Asking again never restarts the wait: the first request counts. */
    fun requested(requests: Map<String, Instant>, packageName: String, now: Instant): Map<String, Instant> =
        if (packageName in requests) requests else requests + (packageName to now)
}
