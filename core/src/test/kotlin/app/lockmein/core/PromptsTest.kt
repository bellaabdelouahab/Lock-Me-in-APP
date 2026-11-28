package app.lockmein.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptsTest {
    private fun t(s: String) = LocalTime.parse(s)
    private val day = LocalDate.parse("2026-11-28")

    @Test
    fun prayerTimesForAgadirAreInTheRightPlaces() {
        // Agadir, late November, in UTC (the phone reads +00:00): roughly fajr 05:39, dhuhr 12:24, asr 15:13, maghrib 17:40,
        // isha 18:59. Not checked against the Salaat app yet; within 8 minutes is all the prompt's 10-minute margin needs.
        val p = PrayerTimes(FajrConfig(), ZoneId.of("UTC")).on(day)
        fun close(expected: String, got: LocalTime) = assertTrue(kotlin.math.abs(java.time.Duration.between(t(expected), got).toMinutes()) <= 8, "$expected vs $got")
        close("05:39", p.fajr); close("12:24", p.dhuhr); close("15:13", p.asr); close("17:40", p.maghrib); close("18:59", p.isha)
    }

    @Test
    fun aSlotNearAPrayerMovesFifteenMinutesAndNoneAfter2130() {
        val prayers = listOf(t("06:30"), t("12:55"), t("15:30"), t("19:10"), t("20:30"))
        // 13:00 is 5 min after dhuhr: 13:15. 19:15 is 5 min after maghrib: 19:30.
        assertEquals(listOf(t("07:45") to t("07:45"), t("13:00") to t("13:15"), t("19:15") to t("19:30")), PromptSchedule.slots(prayers))
        // 19:15 near 19:10, 19:30 near 19:35, 19:45 near 19:50... until 21:30: dropped.
        val late = PromptSchedule.slots(listOf(t("19:10"), t("19:35"), t("19:55"), t("20:15"), t("20:35"), t("21:00")))
        assertEquals(listOf(t("07:45"), t("13:00")), late.map { it.first })
    }

    @Test
    fun dueOnceFromItsTimeUntilTheNextAndLaterTwiceThenSkipped() {
        val prayers = listOf(t("06:30"), t("12:30"), t("15:30"), t("17:40"), t("19:00"))
        assertNull(PromptDue.at(t("07:30"), 0, day, prayers, emptyList()))
        assertEquals(t("07:45"), PromptDue.at(t("09:00"), 0, day, prayers, emptyList()))
        assertEquals(t("13:00"), PromptDue.at(t("14:00"), 0, day, prayers, emptyList()), "a missed morning slot is not asked in the afternoon")
        val snoozed = listOf(PromptRecord(day, t("07:45"), 1, 1_000, null))
        assertNull(PromptDue.at(t("09:00"), 500, day, prayers, snoozed), "not before the snooze ends")
        assertEquals(t("07:45"), PromptDue.at(t("09:20"), 1_500, day, prayers, snoozed))
        assertNull(PromptDue.at(t("09:00"), 0, day, prayers, listOf(PromptRecord(day, t("07:45"), 2, 0, PromptRecord.SKIPPED))))
        assertNull(PromptDue.at(t("21:40"), 0, day, prayers, emptyList()), "none after 21:30")
        assertNull(PromptDue.at(t("19:05"), 0, day, prayers, emptyList()), "not during a prayer")
    }
}
