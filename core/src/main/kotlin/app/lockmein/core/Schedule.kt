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

    /** Sunday 20:00 to 22:00: anything may be changed. */
    OPEN_WINDOW,
}

data class ScheduleConfig(
    val nightStart: LocalTime = LocalTime.of(22, 0),
    val windowDay: DayOfWeek = DayOfWeek.SUNDAY,
    val windowStart: LocalTime = LocalTime.of(20, 0),
    val windowEnd: LocalTime = LocalTime.of(22, 0),
    /** Daytime ends here for decisions that must happen in the day (approving a browser). */
    val daytimeEnd: LocalTime = LocalTime.of(20, 0),
)

fun interface FajrProvider {
    fun fajr(date: LocalDate): LocalTime
}

/** The daily rhythm: day, night and the weekly open window, in the owner's time zone. */
class Schedule(
    private val config: ScheduleConfig,
    private val fajr: FajrProvider,
    private val zone: ZoneId,
) {
    fun phaseAt(instant: Instant): Phase {
        val local = instant.atZone(zone)
        val time = local.toLocalTime()
        return when {
            inOpenWindow(local) -> Phase.OPEN_WINDOW
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

    /** Start of the first open window that begins strictly after [instant], or the current window's start if one is running. */
    fun nextWindowStart(instant: Instant): Instant {
        val local = instant.atZone(zone)
        if (inOpenWindow(local)) return instant
        var date = local.toLocalDate()
        while (true) {
            if (date.dayOfWeek == config.windowDay) {
                val start = ZonedDateTime.of(date, config.windowStart, zone)
                if (start.toInstant().isAfter(instant)) return start.toInstant()
            }
            date = date.plusDays(1)
        }
    }

    /** The next moment the phase can change after [instant]: fajr, the start of the Sunday window, or the start of the night. */
    fun nextTransition(instant: Instant): Instant {
        val local = instant.atZone(zone)
        val today = local.toLocalDate()
        val candidates = (0L..8L).flatMap { offset ->
            val date = today.plusDays(offset)
            buildList {
                add(ZonedDateTime.of(date, fajr.fajr(date), zone).toInstant())
                add(ZonedDateTime.of(date, config.nightStart, zone).toInstant())
                if (date.dayOfWeek == config.windowDay) add(ZonedDateTime.of(date, config.windowStart, zone).toInstant())
            }
        }
        return candidates.filter { it.isAfter(instant) }.min()
    }

    private fun inOpenWindow(local: ZonedDateTime): Boolean {
        if (local.dayOfWeek != config.windowDay) return false
        val time = local.toLocalTime()
        return time >= config.windowStart && time < config.windowEnd
    }
}
