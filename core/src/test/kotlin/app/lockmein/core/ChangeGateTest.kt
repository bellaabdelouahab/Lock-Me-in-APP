package app.lockmein.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChangeGateTest {
    private val zone = ZoneId.of("Africa/Casablanca")
    private val sundayConfig = ScheduleConfig(windowDay = DayOfWeek.SUNDAY, windowStart = LocalTime.of(20, 0), windowEnd = LocalTime.of(21, 0))
    private val schedule = Schedule(sundayConfig, { LocalTime.of(5, 30) }, zone)
    private val gate = ChangeGate(schedule)

    private fun at(text: String): Instant = java.time.LocalDateTime.parse(text).atZone(zone).toInstant()

    // Sunday 2026-11-01 20:00 to 21:00 is the Sunday settings window
    private val sundayBeforeWindow = at("2026-11-01T18:00:00")
    private val sundayWindowStart = at("2026-11-01T20:00:00")
    private val sundayInWindow = at("2026-11-01T20:30:00")
    private val sundayDuringWindowRequest = at("2026-11-01T20:15:00")
    private val wednesdayNoon = at("2026-10-28T12:00:00")
    private val nextSundayInWindow = at("2026-11-08T20:30:00")

    private fun request(kind: RequestKind = RequestKind.BLOCKED_REMOVE, target: String = "app.x", time: Instant = wednesdayNoon) =
        ChangeRequest(kind, target, time.toEpochMilli())

    @Test
    fun `tightening is allowed at any time`() {
        assertIs<Decision.Allowed>(gate.decide(ChangeKind.TIGHTEN))
    }

    @Test
    fun `loosening directly on the spot is never allowed, it must be requested`() {
        assertIs<Decision.Denied>(gate.decide(ChangeKind.LOOSEN))
    }

    @Test
    fun `request made before Sunday is approvable during the Sunday settings window`() {
        val req = request(time = wednesdayNoon)
        val decision = gate.decideSundayReview(req, sundayInWindow)
        assertIs<Decision.Allowed>(decision)
    }

    @Test
    fun `request made on Sunday before the window starts is approvable in that window`() {
        val req = request(time = sundayBeforeWindow)
        val decision = gate.decideSundayReview(req, sundayInWindow)
        assertIs<Decision.Allowed>(decision)
    }

    @Test
    fun `request made during the Sunday window waits until next Sunday`() {
        val req = request(time = sundayDuringWindowRequest)
        // In the same Sunday window: denied
        val decisionThisSunday = gate.decideSundayReview(req, sundayInWindow)
        assertIs<Decision.Denied>(decisionThisSunday)
        assertTrue(decisionThisSunday.why.contains("wait until next Sunday", ignoreCase = true))

        // In next Sunday's window: allowed
        val decisionNextSunday = gate.decideSundayReview(req, nextSundayInWindow)
        assertIs<Decision.Allowed>(decisionNextSunday)
    }

    @Test
    fun `outside the Sunday settings window approval is denied`() {
        val req = request(time = wednesdayNoon)
        val decision = gate.decideSundayReview(req, wednesdayNoon)
        assertIs<Decision.Denied>(decision)
        assertTrue(decision.why.contains("Sunday settings window", ignoreCase = true))
    }

    @Test
    fun `dismissing a request is always allowed`() {
        val req = request(time = wednesdayNoon)
        assertIs<Decision.Allowed>(gate.decideDismiss(req))
    }
}
