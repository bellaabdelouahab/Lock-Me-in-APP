package app.lockmein.core

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
    private val monday = ScheduleConfig(windowDay = java.time.DayOfWeek.MONDAY, windowStart = LocalTime.of(9, 0), windowEnd = LocalTime.of(10, 0))
    private val schedule = Schedule(monday, { LocalTime.of(5, 30) }, zone)
    private val gate = ChangeGate(schedule)

    // 2026-11-02 is a Monday; a request made on 2026-10-25 has waited its seven days by then.
    private fun at(text: String) = Instant.parse(text)
    private val asked = at("2026-10-25T12:00:00Z")
    private fun request(kind: RequestKind = RequestKind.NIGHT_ADD, target: String = "app.x", time: Instant = asked) =
        ChangeRequest(kind, target, time.toEpochMilli())

    @Test
    fun `tightening is allowed at any time`() {
        assertIs<Decision.Allowed>(gate.decide(ChangeKind.TIGHTEN))
    }

    @Test
    fun `loosening is never direct, it has to be a request`() {
        assertIs<Decision.Denied>(gate.decide(ChangeKind.LOOSEN))
    }

    @Test
    fun `a request waits seven days`() {
        assertEquals(asked.plus(Duration.ofDays(7)), gate.readyAt(request()))
    }

    @Test
    fun `after the wait it applies in the Monday window when the clocks agree`() {
        val inWindow = at("2026-11-02T08:30:00Z") // 09:30 in Casablanca (UTC+1)
        assertIs<Decision.Allowed>(gate.decideApply(request(), inWindow, networkTime = inWindow))
    }

    @Test
    fun `after the wait but outside the window it still does not apply`() {
        val monday3pm = at("2026-11-02T14:00:00Z")
        assertIs<Decision.Denied>(gate.decideApply(request(), monday3pm, networkTime = monday3pm))
    }

    @Test
    fun `inside the window but before the wait is over it does not apply`() {
        val laterRequest = request(time = at("2026-10-28T12:00:00Z"))
        val inWindow = at("2026-11-02T08:30:00Z")
        assertIs<Decision.Denied>(gate.decideApply(laterRequest, inWindow, networkTime = inWindow))
    }

    @Test
    fun `without network time or with a disagreeing clock nothing applies`() {
        val inWindow = at("2026-11-02T08:30:00Z")
        assertIs<Decision.Denied>(gate.decideApply(request(), inWindow, networkTime = null))
        assertIs<Decision.Denied>(gate.decideApply(request(), inWindow, networkTime = inWindow.plus(Duration.ofHours(3))))
    }

    @Test
    fun `the first request counts and asking again does not restart the wait`() {
        val first = request()
        val second = request(time = asked.plus(Duration.ofDays(3)))
        assertEquals(listOf(first), Requests.add(listOf(first), second))
    }

    @Test
    fun `a settings request replaces the earlier one and starts its wait again`() {
        val config = LockConfig(dnsHost = "abc.dns.nextdns.io")
        val first = ChangeRequest(RequestKind.SETTINGS, "settings", asked.toEpochMilli(), config)
        val later = ChangeRequest(RequestKind.SETTINGS, "settings", asked.plus(Duration.ofDays(6)).toEpochMilli(), config.copy(nightStart = "21:00"))
        val result = Requests.add(listOf(first), later)
        assertEquals(listOf(later), result)
    }

    @Test
    fun `requests can be cancelled and survive being saved as text`() {
        val a = request(target = "a")
        val b = request(RequestKind.BROWSER, "b")
        val saved = Requests.decode(Requests.encode(listOf(a, b)))
        assertEquals(listOf(a, b), saved)
        assertEquals(listOf(b), Requests.cancel(saved, a.id))
        assertTrue(Requests.decode("not json").isEmpty())
    }
}
