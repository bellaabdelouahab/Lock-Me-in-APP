package app.lockmein.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChangeGateTest {
    private val zone = ZoneId.of("Africa/Casablanca")
    private val schedule = Schedule(ScheduleConfig(), { LocalTime.of(5, 30) }, zone)
    private val gate = ChangeGate(schedule)

    private fun at(date: String, time: String): Instant = ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toInstant()

    private val sundayWindow = at("2026-11-01", "20:30")
    private val mondayNoon = at("2026-11-02", "12:00")

    @Test
    fun `tightening is allowed at any time, even with no network time`() {
        assertIs<Decision.Allowed>(gate.decide(ChangeKind.TIGHTEN, mondayNoon, networkTime = null))
    }

    @Test
    fun `loosening is allowed in the Sunday window when the clocks agree`() {
        assertIs<Decision.Allowed>(gate.decide(ChangeKind.LOOSEN, sundayWindow, networkTime = sundayWindow.plusSeconds(20)))
    }

    @Test
    fun `loosening on a Monday is denied and says when the window opens`() {
        val denied = assertIs<Decision.Denied>(gate.decide(ChangeKind.LOOSEN, mondayNoon, networkTime = mondayNoon))
        assertTrue(denied.why.contains(at("2026-11-08", "20:00").toString()))
    }

    @Test
    fun `winding the phone clock into the window does not help`() {
        val real = mondayNoon
        val wound = sundayWindow
        assertIs<Decision.Denied>(gate.decide(ChangeKind.LOOSEN, now = wound, networkTime = real))
    }

    @Test
    fun `loosening with no network time is denied even inside the window`() {
        assertIs<Decision.Denied>(gate.decide(ChangeKind.LOOSEN, sundayWindow, networkTime = null))
    }

    @Test
    fun `an emergency window allows loosening at any time, even offline`() {
        val until = mondayNoon.plus(Duration.ofMinutes(60))
        assertIs<Decision.Allowed>(gate.decide(ChangeKind.LOOSEN, mondayNoon.plusSeconds(60), networkTime = null, emergencyUntil = until))
    }

    @Test
    fun `an emergency window ends, and never overrides a clock that has been moved`() {
        val until = mondayNoon.plus(Duration.ofMinutes(60))
        assertIs<Decision.Denied>(gate.decide(ChangeKind.LOOSEN, until.plusSeconds(1), networkTime = until.plusSeconds(1), emergencyUntil = until))
        assertIs<Decision.Denied>(gate.decide(ChangeKind.LOOSEN, mondayNoon, networkTime = mondayNoon.plus(Duration.ofHours(3)), emergencyUntil = until))
    }

    @Test
    fun `a requested browser is approved 30 minutes later when that is daytime`() {
        val approval = BrowserApproval(schedule)
        val asked = at("2026-11-02", "10:00")
        assertEquals(at("2026-11-02", "10:30"), approval.approvalTime(asked))
        assertTrue(approval.isApproved(asked, at("2026-11-02", "10:30")))
        assertTrue(!approval.isApproved(asked, at("2026-11-02", "10:29")))
    }

    @Test
    fun `a browser asked for in the evening waits for the next morning`() {
        val approval = BrowserApproval(schedule)
        assertEquals(at("2026-11-03", "05:30"), approval.approvalTime(at("2026-11-02", "19:45")))
        assertEquals(at("2026-11-03", "05:30"), approval.approvalTime(at("2026-11-02", "23:00")))
        assertEquals(at("2026-11-02", "05:30"), approval.approvalTime(at("2026-11-02", "03:00")))
    }

    @Test
    fun `asking for the same browser again does not restart the wait`() {
        val approval = BrowserApproval(schedule)
        val first = approval.requested(emptyMap(), "org.mozilla.firefox", at("2026-11-02", "10:00"))
        val again = approval.requested(first, "org.mozilla.firefox", at("2026-11-02", "10:20"))
        assertEquals(first, again)
        assertEquals(at("2026-11-02", "10:00"), again.getValue("org.mozilla.firefox"))
    }

    @Test
    fun `only the browsers whose wait is over are due`() {
        val approval = BrowserApproval(schedule)
        val requests = mapOf("a" to at("2026-11-02", "10:00"), "b" to at("2026-11-02", "10:20"), "c" to at("2026-11-02", "21:00"))
        assertEquals(setOf("a"), approval.due(requests, at("2026-11-02", "10:35")))
        assertEquals(setOf("a", "b"), approval.due(requests, at("2026-11-02", "11:00")))
        assertEquals(setOf("a", "b"), approval.due(requests, at("2026-11-03", "05:29")))
        assertEquals(setOf("a", "b", "c"), approval.due(requests, at("2026-11-03", "05:30")))
    }

    @Test
    fun `a friend code opens an emergency window once only`() {
        val secret = "12345678901234567890".toByteArray()
        val emergency = Emergency(secret)
        val now = Instant.ofEpochSecond(1_700_000_010)
        val code = Totp.code(secret, Totp.counter(now))

        val first = emergency.redeem(code, now, lastUsedCounter = null)
        assertEquals(now.plus(Duration.ofMinutes(60)), first?.until)

        assertNull(emergency.redeem(code, now.plusSeconds(5), lastUsedCounter = first?.counter))
        val wrong = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        assertNull(emergency.redeem(wrong, now, lastUsedCounter = null))
    }
}
