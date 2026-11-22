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
        val d = PhoneUse.summarize(listOf(on("10:00"), app("10:00", "yt")), day, zone, now = t("10:20"))
        assertEquals(20 * min, d.apps.single().millis)
        assertEquals(20 * min, d.screenMillis)
    }

    @Test
    fun `turning the screen back on resumes the app that was in front`() {
        val events = listOf(on("10:00"), app("10:00", "yt"), off("10:10"), on("11:00"), off("11:05"))
        assertEquals(15 * min, PhoneUse.summarize(events, day, zone, now = t("12:00")).apps.single().millis)
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
            UseEvent(Instant.parse("2026-11-21T23:50:00Z"), UseKind.APP, "yt"),
            off("00:10"),
            UseEvent(t("07:00"), UseKind.NOTIFICATION, "chat"),
            UseEvent(t("07:01"), UseKind.NOTIFICATION, "chat"),
            on("07:02"), app("07:02", "launcher"), app("07:03", "chat"), off("07:04"),
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
        assertEquals(null, UseLines.decode("garbage"))
        val csv = PhoneUse.csv(listOf(e), zone) { "X \"app\"" }
        assertEquals("time,event,package,app\n2026-11-22T07:00,app,com.x,\"X \"\"app\"\"\"\n", csv)
    }
}
