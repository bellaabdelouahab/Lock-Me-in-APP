package app.lockmein.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FajrTest {
    private val zone = ZoneId.of("Africa/Casablanca")
    private val agadir = AstronomicalFajr(FajrConfig(), zone)

    private fun minutes(t: LocalTime) = t.hour * 60 + t.minute

    @Test
    fun `the sun's declination and the equation of time match known almanac values`() {
        // Almanac: on 21 June the declination is about +23.44 degrees; on 3 November the equation of time is about +16.4 minutes.
        assertEquals(23.44, Math.toDegrees(SunPosition.at(LocalDate.parse("2026-06-21")).declination), 0.05)
        assertEquals(16.4, SunPosition.at(LocalDate.parse("2026-11-03")).equationOfTimeMinutes, 0.3)
        // Near the March equinox the declination crosses zero.
        assertEquals(0.0, Math.toDegrees(SunPosition.at(LocalDate.parse("2026-03-20")).declination), 0.5)
    }

    @Test
    fun `fajr in Agadir is in the early morning in every season`() {
        for (day in listOf("2026-01-15", "2026-03-20", "2026-06-21", "2026-09-22", "2026-11-02", "2026-12-21")) {
            val m = minutes(agadir.fajr(LocalDate.parse(day)))
            // Morocco keeps UTC+1 all year (UTC+0 in Ramadan), so winter fajr is late: about 07:05 in January.
            assertTrue(m in 4 * 60..7 * 60 + 30, "fajr on $day was ${agadir.fajr(LocalDate.parse(day))}")
        }
    }

    @Test
    fun `fajr is earliest in summer and latest in winter`() {
        val summer = minutes(agadir.fajr(LocalDate.parse("2026-06-21")))
        val winter = minutes(agadir.fajr(LocalDate.parse("2026-12-21")))
        assertTrue(summer < winter, "summer $summer winter $winter")
    }

    @Test
    fun `the day changes the time by a few minutes at most`() {
        val today = minutes(agadir.fajr(LocalDate.parse("2026-11-02")))
        val tomorrow = minutes(agadir.fajr(LocalDate.parse("2026-11-03")))
        assertTrue(abs(today - tomorrow) <= 2, "today $today tomorrow $tomorrow")
    }

    @Test
    fun `a smaller sun angle gives a later fajr and the offset shifts it`() {
        val day = LocalDate.parse("2026-11-02")
        val at18 = AstronomicalFajr(FajrConfig(sunAngleDegrees = 18.0), zone).fajr(day)
        assertTrue(at18 > agadir.fajr(day))
        val shifted = AstronomicalFajr(FajrConfig(offsetMinutes = 10), zone).fajr(day)
        assertEquals(minutes(agadir.fajr(day)) + 10, minutes(shifted))
    }

    @Test
    fun `fajr plugs into the schedule`() {
        val schedule = Schedule(ScheduleConfig(), agadir, zone)
        val day = LocalDate.parse("2026-11-02")
        val fajr = agadir.fajr(day)
        val before = day.atTime(fajr).minusMinutes(1).atZone(zone).toInstant()
        val after = day.atTime(fajr).atZone(zone).toInstant()
        assertEquals(Phase.NIGHT, schedule.phaseAt(before))
        assertEquals(Phase.DAY, schedule.phaseAt(after))
    }
}
