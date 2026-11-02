package app.lockmein.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventsTest {
    private val now = Instant.parse("2026-11-08T21:00:00Z")
    private fun ago(hours: Long, kind: EventKind, detail: String = "") = LockEvent(now.minus(Duration.ofHours(hours)), kind, detail)

    private val events = listOf(
        ago(1, EventKind.PASS_OK),
        ago(2, EventKind.PASS_OK),
        ago(3, EventKind.PASS_FAILED, "private dns host: code 1"),
        ago(30, EventKind.APP_QUARANTINED, "com.new.game"),
        ago(31, EventKind.APP_QUARANTINED, "com.new.game"),
        ago(40, EventKind.DENIED, "changes open Sunday"),
        ago(50, EventKind.EMERGENCY_USED),
        ago(60, EventKind.EMERGENCY_REFUSED, "wrong code"),
        ago(200, EventKind.PASS_OK), // older than a week
        LockEvent(now.plusSeconds(60), EventKind.PASS_OK), // in the future: ignored
    )

    @Test
    fun `the week counts what happened in it and ignores what is older or later`() {
        val report = Reports.week(events, now)
        assertEquals(2, report.count(EventKind.PASS_OK))
        assertEquals(1, report.count(EventKind.PASS_FAILED))
        assertEquals(2, report.count(EventKind.APP_QUARANTINED))
        assertEquals(1, report.count(EventKind.EMERGENCY_USED))
        assertEquals(0, report.count(EventKind.PROTECTION_REMOVED))
    }

    @Test
    fun `new apps are listed once and refusals and problems are kept for the Sunday session`() {
        val report = Reports.week(events, now)
        assertEquals(listOf("com.new.game"), report.newApps)
        assertEquals(listOf(EventKind.EMERGENCY_REFUSED, EventKind.DENIED), report.refusals.map { it.kind })
        assertEquals(listOf("private dns host: code 1"), report.problems.map { it.detail })
    }

    @Test
    fun `the text report is readable and names the details`() {
        val text = Reports.week(events, now).toText(ZoneId.of("UTC"))
        assertTrue("Policy passes: 2 ok, 1 with problems" in text)
        assertTrue("Emergency windows used: 1 (refused codes: 1)" in text)
        assertTrue("New apps held back: com.new.game" in text)
        assertTrue("Refused" in text && "wrong code" in text)
        assertTrue("Problem" in text && "code 1" in text)
    }

    @Test
    fun `an empty week still makes a report`() {
        val text = Reports.week(emptyList(), now).toText(ZoneId.of("UTC"))
        assertTrue("Policy passes: 0 ok, 0 with problems" in text)
    }

    @Test
    fun `an event survives being written as a line and read back`() {
        for (event in events) assertEquals(event, EventLines.decode(EventLines.encode(event)))
    }

    @Test
    fun `tabs and new lines in a detail cannot break the line format`() {
        val event = LockEvent(now, EventKind.DENIED, "a\tb\nc")
        assertEquals(LockEvent(now, EventKind.DENIED, "a b c"), EventLines.decode(EventLines.encode(event)))
    }

    @Test
    fun `damaged lines are skipped`() {
        assertNull(EventLines.decode(""))
        assertNull(EventLines.decode("not a number\tPASS_OK"))
        assertNull(EventLines.decode("123\tNOT_A_KIND"))
        assertNull(EventLines.decode("123"))
    }

    @Test
    fun `the log keeps only the newest lines`() {
        val lines = (1..1500).map { "line $it" }
        val kept = EventLines.trim(lines)
        assertEquals(1000, kept.size)
        assertEquals("line 501", kept.first())
        assertEquals("line 1500", kept.last())
        assertEquals(lines.take(10), EventLines.trim(lines.take(10)))
    }
}
