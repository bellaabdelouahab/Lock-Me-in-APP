package app.lockmein.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationStatsTest {
    private val zone = ZoneOffset.UTC
    private val day = LocalDate.parse("2026-11-22")
    private fun t(s: String) = Instant.parse(if (s.length == 5) "2026-11-22T$s:00Z" else "${s}Z")
    private fun e(at: String, kind: UseKind, pkg: String = "") = UseEvent(t(at), kind, pkg)
    private val fajr = { _: LocalDate -> LocalTime.of(6, 0) }

    @Test
    fun perAppPerHourWithOpensAndTheUnlocksTheyStarted() {
        val uses = listOf(
            e("09:00", UseKind.NOTIFICATION, "wa"), e("09:10", UseKind.NOTIFICATION, "wa"), e("09:10", UseKind.NOTIFICATION_OPENED, "wa"),
            e("2026-11-22T09:10:30", UseKind.UNLOCK), // 30 s after wa's second: started by it
            e("09:20", UseKind.NOTIFICATION, "android"), e("2026-11-22T09:20:10", UseKind.UNLOCK), // Android's own never count
            e("10:00", UseKind.UNLOCK), // by hand
            e("10:30", UseKind.NOTIFICATION, "mail"),
        )
        val d = NotificationStats.day(day, zone, t("2026-11-23T12:00:00"), uses, fajr, emptySet())
        assertEquals(3, d.posted)
        assertEquals(1, d.opened)
        assertEquals(3, d.unlocks)
        assertEquals(1, d.unlocksAfterNotification)
        assertEquals(listOf(NotifyHour("wa", 9, 2, 1, 1), NotifyHour("mail", 10, 1, 0, 0)), d.hours)
    }

    @Test
    fun nightNotificationsAndWhetherTheScreenCameOn() {
        val uses = listOf(
            e("21:00", UseKind.NOTIFICATION, "wa"), // before 21:30
            e("23:00", UseKind.NOTIFICATION, "wa"), e("2026-11-22T23:00:20", UseKind.SCREEN_ON),
            e("2026-11-23T02:00:00", UseKind.NOTIFICATION, "wa"),
            e("2026-11-23T03:00:00", UseKind.NOTIFICATION, "tg"),
            e("2026-11-23T07:00:00", UseKind.NOTIFICATION, "tg"), // after fajr
        )
        val d = NotificationStats.day(day, zone, t("2026-11-23T12:00:00"), uses, fajr, emptySet())
        assertEquals(listOf(NightNotify("wa", 2, 1), NightNotify("tg", 1, 0)), d.night)
    }
}
