package app.lockmein.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** What the phone is allowed to do right now. */
enum class Phase {
    /** Fajr to 22:00: normal apps, under the screen check. */
    DAY,

    /** 22:00 to fajr: only the night allowlist. */
    NIGHT,

    /** Sunday 20:00 to 21:00: settings window. */
    OPEN_WINDOW,
}

data class ScheduleConfig(
    val nightStart: LocalTime = LocalTime.of(22, 0),
    val windowDay: DayOfWeek = DayOfWeek.SUNDAY,
    val windowStart: LocalTime = LocalTime.of(20, 0),
    val windowEnd: LocalTime = LocalTime.of(21, 0),
    /** Daytime ends here for decisions that must happen in the day (approving a browser). */
    val daytimeEnd: LocalTime = LocalTime.of(20, 0),
)

fun interface FajrProvider {
    fun fajr(date: LocalDate): LocalTime
}

/** The daily rhythm: day, night and windows, in the owner's time zone. */
class Schedule(
    private val config: ScheduleConfig,
    private val fajr: FajrProvider,
    private val zone: ZoneId,
) {
    fun phaseAt(instant: Instant): Phase {
        val local = instant.atZone(zone)
        val time = local.toLocalTime()
        return when {
            inSettingsWindow(instant) -> Phase.OPEN_WINDOW
            time >= config.nightStart || time < fajr.fajr(local.toLocalDate()) -> Phase.NIGHT
            else -> Phase.DAY
        }
    }

    fun isDaytime(instant: Instant): Boolean {
        val local = instant.atZone(zone)
        val time = local.toLocalTime()
        return time >= fajr.fajr(local.toLocalDate()) && time < config.daytimeEnd
    }

    /** First instant at or after [instant] that is daytime. */
    fun nextDaytime(instant: Instant): Instant {
        if (isDaytime(instant)) return instant
        val local = instant.atZone(zone)
        val today = local.toLocalDate()
        val date = if (local.toLocalTime() < fajr.fajr(today)) today else today.plusDays(1)
        return ZonedDateTime.of(date, fajr.fajr(date), zone).toInstant()
    }

    /** Daily open window: every day from 20:00 to 21:30. Only affects window apps. */
    fun inDailyWindow(instant: Instant): Boolean {
        val local = instant.atZone(zone)
        val time = local.toLocalTime()
        return time >= DAILY_WINDOW_START && time < DAILY_WINDOW_END
    }

    /** Weekly settings window: Sunday 20:00 to 21:00. Settings and removals allowed. */
    fun inSettingsWindow(instant: Instant): Boolean {
        val local = instant.atZone(zone)
        if (local.dayOfWeek != config.windowDay) return false
        val time = local.toLocalTime()
        return time >= config.windowStart && time < config.windowEnd
    }

    /** Start of the current weekly settings window, or null if outside the window. */
    fun currentSettingsWindowStart(instant: Instant): Instant? {
        if (!inSettingsWindow(instant)) return null
        val local = instant.atZone(zone)
        return ZonedDateTime.of(local.toLocalDate(), config.windowStart, zone).toInstant()
    }

    /** Monthly uninstall window: 1st of each month 20:00 to 21:00. Remove Lock Me In allowed. */
    fun inUninstallWindow(instant: Instant): Boolean {
        val local = instant.atZone(zone)
        if (local.dayOfMonth != 1) return false
        val time = local.toLocalTime()
        return time >= UNINSTALL_WINDOW_START && time < UNINSTALL_WINDOW_END
    }

    /** Start of the next daily open window, or [instant] if currently running. */
    fun nextDailyWindowStart(instant: Instant): Instant {
        if (inDailyWindow(instant)) return instant
        val local = instant.atZone(zone)
        val today = local.toLocalDate()
        val todayStart = ZonedDateTime.of(today, DAILY_WINDOW_START, zone).toInstant()
        return if (todayStart.isAfter(instant)) todayStart
        else ZonedDateTime.of(today.plusDays(1), DAILY_WINDOW_START, zone).toInstant()
    }

    /** End of the current or next daily open window. */
    fun nextDailyWindowEnd(instant: Instant): Instant {
        val local = instant.atZone(zone)
        val today = local.toLocalDate()
        val todayEnd = ZonedDateTime.of(today, DAILY_WINDOW_END, zone).toInstant()
        return if (todayEnd.isAfter(instant)) todayEnd
        else ZonedDateTime.of(today.plusDays(1), DAILY_WINDOW_END, zone).toInstant()
    }

    /** Start of the next weekly settings window, or [instant] if currently running. */
    fun nextSettingsWindowStart(instant: Instant): Instant {
        if (inSettingsWindow(instant)) return instant
        val local = instant.atZone(zone)
        var date = local.toLocalDate()
        while (true) {
            if (date.dayOfWeek == config.windowDay) {
                val start = ZonedDateTime.of(date, config.windowStart, zone).toInstant()
                if (start.isAfter(instant)) return start
            }
            date = date.plusDays(1)
        }
    }

    /** End of the current or next weekly settings window. */
    fun nextSettingsWindowEnd(instant: Instant): Instant {
        val local = instant.atZone(zone)
        var date = local.toLocalDate()
        while (true) {
            if (date.dayOfWeek == config.windowDay) {
                val end = ZonedDateTime.of(date, config.windowEnd, zone).toInstant()
                if (end.isAfter(instant)) return end
            }
            date = date.plusDays(1)
        }
    }

    /** Start of the next monthly uninstall window, or [instant] if currently running. */
    fun nextUninstallWindowStart(instant: Instant): Instant {
        if (inUninstallWindow(instant)) return instant
        val local = instant.atZone(zone)
        var date = local.toLocalDate()
        while (true) {
            if (date.dayOfMonth == 1) {
                val start = ZonedDateTime.of(date, UNINSTALL_WINDOW_START, zone).toInstant()
                if (start.isAfter(instant)) return start
            }
            date = date.plusDays(1)
        }
    }

    /** End of the current or next monthly uninstall window. */
    fun nextUninstallWindowEnd(instant: Instant): Instant {
        val local = instant.atZone(zone)
        var date = local.toLocalDate()
        while (true) {
            if (date.dayOfMonth == 1) {
                val end = ZonedDateTime.of(date, UNINSTALL_WINDOW_END, zone).toInstant()
                if (end.isAfter(instant)) return end
            }
            date = date.plusDays(1)
        }
    }

    /** Legacy alias: start of the first open settings window that begins strictly after [instant], or [instant] if running. */
    fun nextWindowStart(instant: Instant): Instant = nextSettingsWindowStart(instant)

    /** The next moment the phase or window edges can change after [instant]. */
    fun nextTransition(instant: Instant): Instant {
        val local = instant.atZone(zone)
        val today = local.toLocalDate()
        val candidates = (0L..35L).flatMap { offset ->
            val date = today.plusDays(offset)
            buildList {
                add(ZonedDateTime.of(date, fajr.fajr(date), zone).toInstant())
                add(ZonedDateTime.of(date, config.nightStart, zone).toInstant())
                add(ZonedDateTime.of(date, DAILY_WINDOW_START, zone).toInstant())
                add(ZonedDateTime.of(date, DAILY_WINDOW_END, zone).toInstant())
                if (date.dayOfWeek == config.windowDay) {
                    add(ZonedDateTime.of(date, config.windowStart, zone).toInstant())
                    add(ZonedDateTime.of(date, config.windowEnd, zone).toInstant())
                }
                if (date.dayOfMonth == 1) {
                    add(ZonedDateTime.of(date, UNINSTALL_WINDOW_START, zone).toInstant())
                    add(ZonedDateTime.of(date, UNINSTALL_WINDOW_END, zone).toInstant())
                }
            }
        }
        return candidates.filter { it.isAfter(instant) }.min()
    }

    companion object {
        val DAILY_WINDOW_START: LocalTime = LocalTime.of(20, 0)
        val DAILY_WINDOW_END: LocalTime = LocalTime.of(21, 30)
        val UNINSTALL_WINDOW_START: LocalTime = LocalTime.of(20, 0)
        val UNINSTALL_WINDOW_END: LocalTime = LocalTime.of(21, 0)
    }
}
