package app.lockmein.core

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The five prayer times from the sun, with the same place and method as fajr (Morocco's: fajr 19 degrees, isha 17, asr at a
 * shadow of one length; dhuhr and maghrib a few minutes after noon and sunset). Good to a few minutes, which is all the center
 * prompt needs to stay [PromptSchedule.PRAYER_MARGIN] away from them.
 */
class PrayerTimes(private val config: FajrConfig, private val zone: ZoneId) {
    data class Day(val fajr: LocalTime, val dhuhr: LocalTime, val asr: LocalTime, val maghrib: LocalTime, val isha: LocalTime) {
        val all: List<LocalTime> get() = listOf(fajr, dhuhr, asr, maghrib, isha)
    }

    fun on(date: LocalDate): Day {
        val sun = SunPosition.at(date)
        val lat = Math.toRadians(config.latitude)
        val transit = 12.0 - config.longitude / 15.0 - sun.equationOfTimeMinutes / 60.0
        fun hourAngle(altitudeRad: Double): Double {
            val c = (sin(altitudeRad) - sin(lat) * sin(sun.declination)) / (cos(lat) * cos(sun.declination))
            return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0))) / 15.0
        }
        val asrAltitude = atan(1.0 / (1.0 + tan(kotlin.math.abs(lat - sun.declination))))
        fun at(utcHours: Double, plusMinutes: Long = 0): LocalTime =
            date.atStartOfDay().toInstant(ZoneOffset.UTC).plus(Duration.ofSeconds((utcHours * 3600).toLong())).plus(Duration.ofMinutes(plusMinutes))
                .atZone(zone).toLocalTime().withSecond(0).withNano(0)
        return Day(
            fajr = AstronomicalFajr(config, zone).fajr(date),
            dhuhr = at(transit, 5),
            asr = at(transit + hourAngle(asrAltitude)),
            maghrib = at(transit + hourAngle(Math.toRadians(-0.833)), 5),
            isha = at(transit + hourAngle(Math.toRadians(-17.0))),
        )
    }
}

/**
 * When the center prompt asks (spec N2, agreed 2026-11-28): 07:45, 13:00 and 19:15; a slot within [PRAYER_MARGIN] of a prayer
 * time moves [SHIFT] later (again if that is near one too); nothing at or after [LAST], so a slot pushed there is dropped.
 */
object PromptSchedule {
    val SLOTS: List<LocalTime> = listOf(LocalTime.of(7, 45), LocalTime.of(13, 0), LocalTime.of(19, 15))
    val PRAYER_MARGIN: Duration = Duration.ofMinutes(10)
    val SHIFT: Duration = Duration.ofMinutes(15)
    val LAST: LocalTime = LocalTime.of(21, 30)

    /** "Later" moves the prompt to the first unlock [SNOOZE] on, at most [MAX_SNOOZES] times; then it is logged as skipped. */
    val SNOOZE: Duration = Duration.ofMinutes(15)
    const val MAX_SNOOZES = 2

    /** Today's slots: (the agreed time, when it really asks). */
    fun slots(prayers: List<LocalTime>): List<Pair<LocalTime, LocalTime>> = SLOTS.mapNotNull { slot ->
        var t = slot
        repeat(4) { if (prayers.any { near(t, it) }) t = t.plus(SHIFT) }
        if (prayers.any { near(t, it) } || !t.isBefore(LAST) || t.isBefore(slot)) null else slot to t
    }

    fun nearPrayer(t: LocalTime, prayers: List<LocalTime>): Boolean = prayers.any { near(t, it) }

    private fun near(a: LocalTime, b: LocalTime): Boolean = kotlin.math.abs(Duration.between(a, b).toMinutes()) <= PRAYER_MARGIN.toMinutes()
}

/** What happened to one slot of one day. */
data class PromptRecord(val day: LocalDate, val slot: LocalTime, val snoozes: Int, val snoozedUntilMs: Long, val result: String?) {
    val closed: Boolean get() = result != null

    companion object {
        const val ANSWERED = "answered"
        const val SKIPPED = "skipped"
    }
}

object PromptDue {
    /**
     * The slot to ask now, at an unlock at [now] (local), or null. A slot is due from its (shifted) time until the next slot's,
     * once, unless answered or skipped, and not before a snooze ends. Never at or after [PromptSchedule.LAST], never within
     * the prayer margin.
     */
    fun at(now: LocalTime, nowMs: Long, today: LocalDate, prayers: List<LocalTime>, records: List<PromptRecord>): LocalTime? {
        if (!now.isBefore(PromptSchedule.LAST) || PromptSchedule.nearPrayer(now, prayers)) return null
        val slots = PromptSchedule.slots(prayers)
        val idx = slots.indexOfLast { (_, at) -> !now.isBefore(at) }
        if (idx < 0) return null
        val slot = slots[idx].first
        val rec = records.firstOrNull { it.day == today && it.slot == slot }
        if (rec != null && (rec.closed || nowMs < rec.snoozedUntilMs)) return null
        return slot
    }
}
