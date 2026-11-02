package app.lockmein.core

import java.time.Duration
import java.time.Instant

enum class ChangeKind {
    /** Makes the phone more closed. Always allowed. */
    TIGHTEN,

    /** Opens something up: an app, a site, a lock, a setting. It is a request that waits, then applies in the open window. */
    LOOSEN,
}

sealed interface Decision {
    data class Allowed(val why: String) : Decision
    data class Denied(val why: String) : Decision
}

/**
 * The one place that says whether a change may happen now.
 *
 * Tightening is instant. Loosening is never instant: it is a [ChangeRequest] that waits [wait] (seven days by default) and then
 * applies only inside the open window, with a phone clock the network agrees with. There is no other way in: the friend's emergency
 * code unblocks one app for an hour (see [Emergency]) but changes no setting.
 */
class ChangeGate(
    private val schedule: Schedule,
    private val clockGuard: ClockGuard = ClockGuard(),
    val wait: Duration = Duration.ofDays(7),
) {
    fun decide(kind: ChangeKind): Decision = when (kind) {
        ChangeKind.TIGHTEN -> Decision.Allowed("tightening is always allowed")
        ChangeKind.LOOSEN -> Decision.Denied("opening something up is a request: it waits ${wait.toDays()} days, then applies in the open window")
    }

    fun readyAt(request: ChangeRequest): Instant = request.requestedAt.plus(wait)

    /** May this waiting request be carried out now? */
    fun decideApply(request: ChangeRequest, now: Instant, networkTime: Instant?): Decision {
        when (clockGuard.trust(now, networkTime)) {
            ClockTrust.DISAGREE -> return Decision.Denied("the phone clock and the network clock disagree")
            ClockTrust.UNKNOWN -> return Decision.Denied("no network time to confirm the clock")
            else -> Unit
        }
        val ready = readyAt(request)
        if (now < ready) return Decision.Denied("the wait ends at $ready")
        if (schedule.phaseAt(now) != Phase.OPEN_WINDOW) return Decision.Denied("changes apply in the open window, which starts at ${schedule.nextWindowStart(now)}")
        return Decision.Allowed("waited and in the open window")
    }
}
