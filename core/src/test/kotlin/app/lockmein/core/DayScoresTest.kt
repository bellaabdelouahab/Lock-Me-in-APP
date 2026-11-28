package app.lockmein.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DayScoresTest {
    private val zone = ZoneOffset.UTC
    private val day = LocalDate.parse("2026-11-22")
    private fun t(s: String) = Instant.parse(if (s.length == 5) "2026-11-22T$s:00Z" else "${s}Z")
    private fun e(at: String, kind: UseKind, pkg: String = "") = UseEvent(t(at), kind, pkg)
    private val fajr = { _: LocalDate -> LocalTime.of(6, 0) }
    private val home = setOf("launcher")

    private fun scores(uses: List<UseEvent>, now: Instant = t("2026-11-23T12:00:00"), events: List<LockEvent> = emptyList(), moods: List<MoodEntry> = emptyList()) =
        Slides.day(day, zone, now, uses, events, blocks = listOf(t("10:05") to "com.brave.browser"), moods = moods, fajr = fajr, ignore = home)

    @Test
    fun aTryKnowsWhatCameBeforeItAndTheNotificationThatStartedTheSession() {
        val uses = listOf(
            e("10:00", UseKind.NOTIFICATION, "insta"), e("10:01", UseKind.SCREEN_ON), e("10:01", UseKind.UNLOCK),
            e("10:01", UseKind.APP, "insta"), e("10:03", UseKind.APP, "launcher"), e("10:04", UseKind.BLOCKED, "yt"), e("10:10", UseKind.SCREEN_OFF),
        )
        val d = scores(uses)
        val lead = d.tries.single()
        assertEquals("yt", lead.app)
        assertEquals("insta", lead.previousApp)
        assertEquals(180L, lead.sinceUnlockSeconds)
        assertEquals("insta", lead.startedBy)
        assertEquals("com.brave.browser", d.blocks.single().app)
    }

    @Test
    fun theLastNotificationInTheMinuteBeforeTheUnlockStartedItWhateverOpensNextAndAndroidsOwnNeverDo() {
        val uses = listOf(
            e("10:00", UseKind.NOTIFICATION, "chat"), e("10:00:30".let { "2026-11-22T$it" }, UseKind.NOTIFICATION, "android"),
            e("10:01", UseKind.SCREEN_ON), e("10:01", UseKind.UNLOCK), e("10:01", UseKind.APP, "claude"),
            UseEvent(t("10:02"), UseKind.BLOCKED, "", "com.android.settings > Accessibility"),
        )
        val lead = scores(uses).tries.single()
        assertEquals("chat", lead.startedBy)
        assertEquals(true, lead.sessionKnown)
        assertEquals("com.android.settings", lead.over)
        assertEquals("Accessibility", lead.page)
        val noStart = scores(listOf(UseEvent(t("10:02"), UseKind.BLOCKED, "yt"))).tries.single()
        assertEquals(false, noStart.sessionKnown, "no unlock found: unknown, not \"no notification\"")
        assertNull(noStart.over)
    }

    @Test
    fun aSessionOpenedByHandHasNoStarter() {
        val uses = listOf(
            e("09:00", UseKind.NOTIFICATION, "insta"), e("10:01", UseKind.UNLOCK), e("10:01", UseKind.APP, "insta"), e("10:02", UseKind.BLOCKED, ""),
        )
        val lead = scores(uses).tries.single()
        assertNull(lead.startedBy, "a notification an hour before is not what started it")
        assertEquals("", lead.app)
    }

    @Test
    fun theBedScoreIsThreeMinutesOfScreenFrom22To04() {
        val uses = listOf(
            e("21:40", UseKind.SCREEN_ON), e("21:40", UseKind.UNLOCK), e("21:40", UseKind.APP, "chat"), e("21:50", UseKind.SCREEN_OFF),
            e("22:30", UseKind.SCREEN_ON), e("22:32", UseKind.SCREEN_OFF), // two minutes on the lock screen: still in bed
            e("2026-11-23T03:59:00", UseKind.SCREEN_ON), e("2026-11-23T03:59:00", UseKind.UNLOCK), e("2026-11-23T04:20:00", UseKind.SCREEN_OFF),
            e("2026-11-23T05:00:00", UseKind.SCREEN_ON), e("2026-11-23T05:00:00", UseKind.UNLOCK), e("2026-11-23T05:20:00", UseKind.SCREEN_OFF),
        )
        val d = scores(uses)
        assertEquals(3L, d.screenMinutes22To04)
        assertEquals(true, d.inBedBy22, "a 03:59 alarm and the 05:00 wake do not fail the night")
        assertEquals(43L, d.screenMinutesAfter22, "22:00 to fajr (06:00) counts it all")
        assertEquals(53L, d.screenMinutesNight)
        val late = scores(uses + listOf(e("23:00", UseKind.SCREEN_ON), e("23:00", UseKind.UNLOCK), e("23:00", UseKind.APP, "chat"), e("23:05", UseKind.SCREEN_OFF)))
        assertEquals(false, late.inBedBy22)
        assertEquals(8L, late.screenMinutes22To04)
        assertEquals("chat", late.apps22To04.first().pkg)
        assertNull(scores(uses, now = t("21:45")).inBedBy22, "before 22:00 it is not known yet")
    }

    @Test
    fun midnightToFajrIsPartOfTheSameNightAndNeverMoreThan22ToFajr() {
        val uses = listOf(
            e("04:00", UseKind.SCREEN_ON), e("04:00", UseKind.UNLOCK), e("04:15", UseKind.SCREEN_OFF), // the night before: not this day's night
            e("23:50", UseKind.SCREEN_ON), e("23:50", UseKind.UNLOCK), e("23:50", UseKind.APP, "chat"),
            e("2026-11-23T00:30:00", UseKind.SCREEN_OFF),
            e("2026-11-23T05:00:00", UseKind.SCREEN_ON), e("2026-11-23T05:00:00", UseKind.UNLOCK), e("2026-11-23T05:10:00", UseKind.SCREEN_OFF),
        )
        val d = scores(uses)
        assertEquals(40L, d.screenMinutesBeforeFajr)
        assertEquals(50L, d.screenMinutesAfter22)
        assert(d.screenMinutesBeforeFajr <= d.screenMinutesAfter22)
        assert(d.screenMinutesAfter22 <= d.screenMinutesNight)
    }

    @Test
    fun requestsMoodAndScreenTime() {
        val uses = listOf(
            e("04:00", UseKind.SCREEN_ON), e("04:00", UseKind.UNLOCK), e("04:15", UseKind.SCREEN_OFF),
            e("07:00", UseKind.SCREEN_ON), e("07:00", UseKind.UNLOCK), e("07:10", UseKind.SCREEN_OFF),
        )
        val events = listOf(
            LockEvent(t("12:00"), EventKind.LOOSEN_REQUESTED), LockEvent(t("13:00"), EventKind.TIGHTENED),
            LockEvent(t("2026-11-23T01:00:00"), EventKind.LOOSEN_REQUESTED),
        )
        val d = scores(uses, events = events, moods = listOf(MoodEntry(t("13:00"), 2, MoodEntry.DAILY), MoodEntry(t("2026-11-21T13:00:00"), 5, MoodEntry.DAILY)))
        assertEquals(25L, d.screenMinutes)
        assertEquals(1, d.loosenAsked)
        assertEquals(1, d.tightened)
        assertEquals(listOf(2), d.moods.map { it.score })
    }

    @Test
    fun moodLinesRoundTripAndBadOnesAreDropped() {
        val m = MoodEntry(t("13:00"), 4, MoodEntry.AFTER_TRY)
        assertEquals(m, MoodEntry.decode(MoodEntry.encode(m)))
        assertNull(MoodEntry.decode("123\t9\tdaily"))
    }

    @Test
    fun theWeeklyTableHasOneRowADayAndDriftIsOpenUpMinusTightenings() {
        val uses = listOf(e("21:40", UseKind.SCREEN_ON), e("21:40", UseKind.UNLOCK), e("21:50", UseKind.SCREEN_OFF))
        val events = listOf(
            LockEvent(t("12:00"), EventKind.LOOSEN_REQUESTED), LockEvent(t("12:30"), EventKind.LOOSEN_REQUESTED), LockEvent(t("13:00"), EventKind.TIGHTENED),
            LockEvent(t("2026-11-01T13:00:00"), EventKind.LOOSEN_REQUESTED), // older than 14 days
        )
        val d = scores(uses, events = events)
        val table = WeeklyTable.text(listOf(d), zone).lines()
        assertEquals(2, table.size)
        assertEquals("2026-11-22 | yes | 0 | 21:50 | 10 | 0 | 1 | 2 | 1 | 0", table[1])
        val drift = Drift.of(events, t("2026-11-23T12:00:00"))
        assertEquals(1, drift.net)
        assertEquals(2, drift.openUpAsked)
    }
}
