package app.lockmein.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class CountdownTest {
    private val zone = ZoneId.of("Africa/Casablanca")
    private val config = LockConfig(
        dnsHost = "abc123.dns.nextdns.io",
        alwaysBlocked = setOf("com.instagram.android", "com.facebook.katana", "com.zhiliaoapp.musically"),
        windowApps = setOf("com.google.android.youtube"),
    )
    private val schedule = Schedule(ScheduleConfig(), { LocalTime.of(5, 30) }, zone)

    private fun at(date: String, time: String) = ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toInstant()

    @Test
    fun `countdown before 20 00 says window opens in X h Y min`() {
        val now = at("2026-11-02", "16:48")
        val countdown = CountdownFormatter.compute(schedule, config, now, zone)
        assertEquals("Window opens in 3 h 12 min", countdown.bigText)
    }

    @Test
    fun `countdown during 20 00 to 21 30 says window closes in X min`() {
        val now = at("2026-11-02", "20:43")
        val countdown = CountdownFormatter.compute(schedule, config, now, zone)
        assertEquals("Window closes in 47 min", countdown.bigText)
    }

    @Test
    fun `countdown after 21 30 says next window tomorrow 20 00 in X h Y min`() {
        val now = at("2026-11-02", "21:30")
        val countdown = CountdownFormatter.compute(schedule, config, now, zone)
        assertEquals("Next window tomorrow 20:00 (in 22 h 30 min)", countdown.bigText)
    }

    @Test
    fun `settings window line shows upcoming Sunday and duration`() {
        // Friday 2026-11-06 16:00 -> Sunday 20:00 is in 2 days 4 h
        val now = at("2026-11-06", "16:00")
        val countdown = CountdownFormatter.compute(schedule, config, now, zone)
        assertEquals("Settings window: Sunday 20:00 (in 2 days 4 h)", countdown.settingsWindowLine)
    }

    @Test
    fun `uninstall window line shows 1st of month and duration in days`() {
        // 2026-11-08 20:00 -> 1 Dec 20:00 is in 23 days
        val now = at("2026-11-08", "20:00")
        val countdown = CountdownFormatter.compute(schedule, config, now, zone)
        assertEquals("Uninstall window: 1 Dec 20:00 (in 23 days)", countdown.uninstallWindowLine)
    }

    @Test
    fun `apps count line matches list sizes and plurals`() {
        val now = at("2026-11-02", "12:00")
        val countdown = CountdownFormatter.compute(schedule, config, now, zone)
        assertEquals("Always blocked: 3 apps. Window apps: 1 app.", countdown.appsCountLine)

        val customConfig = config.copy(
            alwaysBlocked = setOf("app1"),
            windowApps = setOf("app2", "app3"),
        )
        val customCountdown = CountdownFormatter.compute(schedule, customConfig, now, zone)
        assertEquals("Always blocked: 1 app. Window apps: 2 apps.", customCountdown.appsCountLine)
    }
}
