package app.lockmein.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneUseTest {
    private val zone = ZoneOffset.UTC
    private val day = LocalDate.parse("2026-11-22")
    private fun t(hhmm: String) = Instant.parse("2026-11-22T$hhmm:00Z")
    private fun app(at: String, pkg: String) = UseEvent(t(at), UseKind.APP, pkg)
    private fun on(at: String) = UseEvent(t(at), UseKind.SCREEN_ON)
    private fun off(at: String) = UseEvent(t(at), UseKind.SCREEN_OFF)
    private fun unlock(at: String) = UseEvent(t(at), UseKind.UNLOCK)
    private val min = 60_000L

    @Test
    fun `time per app runs until the next app or the screen going off`() {
        val events = listOf(
            on("08:00"), unlock("08:00"), app("08:00", "yt"), app("08:10", "chat"), off("08:15"),
            on("09:00"), unlock("09:00"), app("09:00", "yt"), off("09:30"),
        )
        val d = PhoneUse.summarize(events, day, zone, now = t("12:00"))
        assertEquals(40 * min, d.apps.first { it.pkg == "yt" }.millis)
        assertEquals(2, d.apps.first { it.pkg == "yt" }.opens)
        assertEquals(5 * min, d.apps.first { it.pkg == "chat" }.millis)
        assertEquals(45 * min, d.screenMillis)
        assertEquals(2, d.unlocks)
        assertEquals(t("08:00"), d.firstUnlock)
        assertEquals(t("09:30"), d.lastScreenOff)
        assertEquals("yt", d.apps.first().pkg)
    }

    @Test
    fun `the app on screen now counts up to now`() {
        val d = PhoneUse.summarize(listOf(on("10:00"), unlock("10:00"), app("10:00", "yt")), day, zone, now = t("10:20"))
        assertEquals(20 * min, d.apps.single().millis)
        assertEquals(20 * min, d.screenMillis)
    }

    @Test
    fun `unlocking again resumes the app that was in front, a lit lock screen does not`() {
        val events = listOf(
            on("10:00"), unlock("10:00"), app("10:00", "yt"), off("10:10"),
            on("11:00"), unlock("11:00"), off("11:05"),
            on("11:30"), off("11:35"), // never unlocked: not yt's time, and at most 2 minutes of screen
        )
        val d = PhoneUse.summarize(events, day, zone, now = t("12:00"))
        assertEquals(15 * min, d.apps.single().millis)
        assertEquals(17 * min, d.screenMillis)
    }

    @Test
    fun `a screen-on Android never closed counts two minutes, not the app left behind, as on 11-27 at 23h52`() {
        val events = listOf(
            UseEvent(Instant.parse("2026-11-21T23:51:43Z"), UseKind.SCREEN_ON),
            UseEvent(Instant.parse("2026-11-21T23:51:47Z"), UseKind.UNLOCK),
            UseEvent(Instant.parse("2026-11-21T23:52:02Z"), UseKind.APP, "claude"),
            UseEvent(Instant.parse("2026-11-21T23:52:13Z"), UseKind.SCREEN_OFF),
            UseEvent(Instant.parse("2026-11-21T23:52:14Z"), UseKind.SCREEN_ON),
            off("04:45"), on("04:45"), unlock("04:45"), app("04:45", "chat"), off("04:50"),
        )
        val d = PhoneUse.summarize(events, day, zone, now = t("12:00"))
        assertEquals(0L, d.apps.firstOrNull { it.pkg == "claude" }?.millis ?: 0L)
        assertEquals(5 * min, d.screenMillis, "the lit lock screen ended at 23:54, before this day began")
        assertEquals(5 * min, d.apps.single { it.pkg == "chat" }.millis)
    }

    @Test
    fun `an adhan screen on the lock screen counts two minutes, under its app`() {
        val events = listOf(app("12:26", "salaat"), on("12:26"), off("12:54"))
        val d = PhoneUse.summarize(events, day, zone, now = t("13:00"))
        assertEquals(2 * min, d.screenMillis)
        assertEquals(0L, d.apps.single().millis, "app time is unlocked time only")
        assertEquals(listOf("salaat" to 2 * min), PhoneUse.appMillis(events, t("12:00"), t("13:00")))
    }

    @Test
    fun `a day's app time never exceeds its screen time`() {
        val events = listOf(
            on("08:00"), app("08:00", "settings"), unlock("08:00"), app("08:01", "yt"), on("08:20"), app("08:20", "chat"),
            unlock("08:21"), off("08:30"), app("09:00", "yt"), UseEvent(t("09:30"), UseKind.NOTIFICATION, "chat"), off("10:00"),
        )
        val d = PhoneUse.summarize(events, day, zone, now = t("12:00"))
        assert(d.apps.sumOf { it.millis } <= d.screenMillis) { "${d.apps} vs ${d.screenMillis}" }
        // yt 08:01 to 08:20 and 09:00 to 10:00 (an app with the screen off means events were missed); chat after the 08:21 unlock.
        assertEquals(79 * min, d.apps.single { it.pkg == "yt" }.millis)
        assertEquals(9 * min, d.apps.single { it.pkg == "chat" }.millis)
        assertEquals(90 * min, d.screenMillis)
    }

    @Test
    fun `a missed screen-off cannot turn into a whole night`() {
        val events = listOf(app("01:00", "yt"), app("09:00", "chat"))
        val d = PhoneUse.summarize(events, day, zone, now = t("09:01"))
        assertEquals(PhoneUse.MAX_STRETCH_MS, d.apps.first { it.pkg == "yt" }.millis)
    }

    @Test
    fun `yesterday's events only count from midnight, notifications and ignored apps`() {
        val events = listOf(
            UseEvent(Instant.parse("2026-11-21T23:50:00Z"), UseKind.SCREEN_ON),
            UseEvent(Instant.parse("2026-11-21T23:50:00Z"), UseKind.UNLOCK),
            UseEvent(Instant.parse("2026-11-21T23:50:00Z"), UseKind.APP, "yt"),
            off("00:10"),
            UseEvent(t("07:00"), UseKind.NOTIFICATION, "chat"),
            UseEvent(t("07:01"), UseKind.NOTIFICATION, "chat"),
            on("07:02"), unlock("07:02"), app("07:02", "launcher"), app("07:03", "chat"), off("07:04"),
        )
        val d = PhoneUse.summarize(events, day, zone, now = t("12:00"), ignore = setOf("launcher"))
        assertEquals(10 * min, d.apps.first { it.pkg == "yt" }.millis)
        assertEquals(0, d.apps.first { it.pkg == "yt" }.opens)
        assertEquals(2, d.notifications)
        assertEquals(2, d.apps.first { it.pkg == "chat" }.notifications)
        assertEquals(null, d.apps.firstOrNull { it.pkg == "launcher" })
        assertEquals(12 * min, d.screenMillis)
    }

    @Test
    fun `lines and csv round trip`() {
        val e = UseEvent(t("07:00"), UseKind.APP, "com.x")
        assertEquals(e, UseLines.decode(UseLines.encode(e)))
        val withDetail = UseEvent(t("07:00"), UseKind.BLOCKED, "", "com.android.settings > Apps\tx")
        assertEquals(withDetail.copy(detail = "com.android.settings > Apps x"), UseLines.decode(UseLines.encode(withDetail)))
        assertEquals("1\tAPP\tcom.x", UseLines.encode(UseEvent(Instant.ofEpochMilli(1), UseKind.APP, "com.x")), "old lines keep three columns")
        assertEquals(null, UseLines.decode("garbage"))
        val csv = PhoneUse.csv(listOf(e), zone) { "X \"app\"" }
        assertEquals("time,event,package,app\n2026-11-22T07:00,app,com.x,\"X \"\"app\"\"\"\n", csv)
    }
}
