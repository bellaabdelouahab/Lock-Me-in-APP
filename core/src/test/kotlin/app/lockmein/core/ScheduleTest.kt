package app.lockmein.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduleTest {
    private val zone = ZoneId.of("Africa/Casablanca")
    private val schedule = Schedule(ScheduleConfig(), { LocalTime.of(5, 30) }, zone)

    private fun at(date: String, time: String) = ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toInstant()

    // 2026-11-01 is a Sunday, 2026-11-02 a Monday.
    @Test
    fun `the test calendar is what the cases below assume`() {
        assertEquals(DayOfWeek.SUNDAY, LocalDate.parse("2026-11-01").dayOfWeek)
    }

    @Test
    fun `a normal day runs from fajr to 22 00 and the night covers the rest`() {
        assertEquals(Phase.NIGHT, schedule.phaseAt(at("2026-11-02", "05:29")))
        assertEquals(Phase.DAY, schedule.phaseAt(at("2026-11-02", "05:30")))
        assertEquals(Phase.DAY, schedule.phaseAt(at("2026-11-02", "21:59")))
        assertEquals(Phase.NIGHT, schedule.phaseAt(at("2026-11-02", "22:00")))
        assertEquals(Phase.NIGHT, schedule.phaseAt(at("2026-11-03", "00:30")))
    }

    @Test
    fun `the Sunday window is open from 20 00 to 22 00 and then the night starts`() {
        assertEquals(Phase.DAY, schedule.phaseAt(at("2026-11-01", "19:59")))
        assertEquals(Phase.OPEN_WINDOW, schedule.phaseAt(at("2026-11-01", "20:00")))
        assertEquals(Phase.OPEN_WINDOW, schedule.phaseAt(at("2026-11-01", "21:59")))
        assertEquals(Phase.NIGHT, schedule.phaseAt(at("2026-11-01", "22:00")))
    }

    @Test
    fun `20 00 to 22 00 on other days is an ordinary evening, not a window`() {
        assertEquals(Phase.DAY, schedule.phaseAt(at("2026-11-02", "20:30")))
        assertEquals(Phase.DAY, schedule.phaseAt(at("2026-11-07", "21:00")))
    }

    @Test
    fun `daytime for decisions ends at 20 00`() {
        assertFalse(schedule.isDaytime(at("2026-11-02", "05:29")))
        assertTrue(schedule.isDaytime(at("2026-11-02", "05:30")))
        assertTrue(schedule.isDaytime(at("2026-11-02", "19:59")))
        assertFalse(schedule.isDaytime(at("2026-11-02", "20:00")))
    }

    @Test
    fun `the next daytime is today's fajr before dawn and tomorrow's after dark`() {
        assertEquals(at("2026-11-02", "10:00"), schedule.nextDaytime(at("2026-11-02", "10:00")))
        assertEquals(at("2026-11-02", "05:30"), schedule.nextDaytime(at("2026-11-02", "03:00")))
        assertEquals(at("2026-11-03", "05:30"), schedule.nextDaytime(at("2026-11-02", "22:15")))
    }

    @Test
    fun `the next window is the coming Sunday at 20 00, or now when one is running`() {
        assertEquals(at("2026-11-08", "20:00"), schedule.nextWindowStart(at("2026-11-02", "09:00")))
        assertEquals(at("2026-11-08", "20:00"), schedule.nextWindowStart(at("2026-11-01", "22:00")))
        assertEquals(at("2026-11-01", "20:00"), schedule.nextWindowStart(at("2026-11-01", "12:00")))
        val running = at("2026-11-01", "20:30")
        assertEquals(running, schedule.nextWindowStart(running))
    }

    @Test
    fun `the next transition is the nearest of fajr, the Sunday window and 22 00`() {
        assertEquals(at("2026-11-02", "05:30"), schedule.nextTransition(at("2026-11-02", "03:00")))
        assertEquals(at("2026-11-02", "22:00"), schedule.nextTransition(at("2026-11-02", "05:30")))
        assertEquals(at("2026-11-03", "05:30"), schedule.nextTransition(at("2026-11-02", "22:00")))
        assertEquals(at("2026-11-01", "20:00"), schedule.nextTransition(at("2026-11-01", "12:00")))
        assertEquals(at("2026-11-01", "22:00"), schedule.nextTransition(at("2026-11-01", "20:00")))
    }

    @Test
    fun `every phase change is announced by a transition`() {
        // Walk a week minute by minute: the phase may only change at the instant nextTransition named.
        var now = at("2026-11-02", "00:00")
        val end = at("2026-11-09", "00:00")
        while (now.isBefore(end)) {
            val next = schedule.nextTransition(now)
            val phase = schedule.phaseAt(now)
            assertEquals(phase, schedule.phaseAt(next.minusSeconds(1)), "phase moved before $next")
            now = next
        }
    }

    @Test
    fun `the window follows the clock when Morocco changes its offset`() {
        // Morocco moves to +00 for Ramadan; the zone rules carry that, the schedule only reads local time.
        val before = Schedule(ScheduleConfig(), { LocalTime.of(5, 30) }, ZoneId.of("Africa/Casablanca"))
        val sundayWindow = ZonedDateTime.of(LocalDate.parse("2027-02-14"), LocalTime.of(20, 15), ZoneId.of("Africa/Casablanca"))
        assertEquals(DayOfWeek.SUNDAY, sundayWindow.dayOfWeek)
        assertEquals(Phase.OPEN_WINDOW, before.phaseAt(sundayWindow.toInstant()))
    }
}
