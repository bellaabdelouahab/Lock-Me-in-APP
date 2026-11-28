package app.lockmein.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** One app's notifications in one hour of the day: posted, tapped open, and the ones that started an unlock (spec N1). */
data class NotifyHour(val pkg: String, val hour: Int, val posted: Int, val opened: Int, val ledToUnlock: Int)

/** Notifications that came in the night (21:30 to fajr) from one app, and how many had the screen come on within a minute. */
data class NightNotify(val pkg: String, val posted: Int, val screenCameOn: Int)

data class NotifyDay(
    val day: LocalDate,
    val posted: Int,
    val opened: Int,
    val unlocks: Int,
    /** Unlocks with a notification in the minute before ([Slides.NOTIFICATION_LEAD_MS]). */
    val unlocksAfterNotification: Int,
    val hours: List<NotifyHour>,
    /** 21:30 this day to the next fajr. */
    val night: List<NightNotify>,
)

/** Counts only, from the phone-use log: no title, sender or text ever reaches here. */
object NotificationStats {
    private val NOT_COUNTED = setOf("android", "com.android.systemui")

    fun day(day: LocalDate, zone: ZoneId, now: Instant, events: List<UseEvent>, fajr: (LocalDate) -> LocalTime, ignore: Set<String>): NotifyDay {
        val start = day.atStartOfDay(zone).toInstant()
        val end = minOf(day.plusDays(1).atStartOfDay(zone).toInstant(), now)
        val sorted = events.sortedBy { it.at }
        val counted = { e: UseEvent -> e.pkg !in ignore && e.pkg !in NOT_COUNTED }
        val notes = sorted.filter { it.kind == UseKind.NOTIFICATION && counted(it) }
        val inDay = { t: Instant -> t >= start && t < end }

        // Each unlock is credited to the last notification in the minute before it (the same rule as "what started it").
        val unlocks = sorted.filter { it.kind == UseKind.UNLOCK && inDay(it.at) }
        val starters = unlocks.mapNotNull { u ->
            notes.lastOrNull { it.at <= u.at && u.at.toEpochMilli() - it.at.toEpochMilli() <= Slides.NOTIFICATION_LEAD_MS }
        }.toSet()

        val hourOf = { t: Instant -> t.atZone(zone).hour }
        val key = { e: UseEvent -> e.pkg to hourOf(e.at) }
        val posted = notes.filter { inDay(it.at) }.groupingBy(key).eachCount()
        val opened = sorted.filter { it.kind == UseKind.NOTIFICATION_OPENED && counted(it) && inDay(it.at) }.groupingBy(key).eachCount()
        val led = starters.filter { inDay(it.at) }.groupingBy(key).eachCount()
        val hours = (posted.keys + opened.keys + led.keys).map { (pkg, h) ->
            NotifyHour(pkg, h, posted[pkg to h] ?: 0, opened[pkg to h] ?: 0, led[pkg to h] ?: 0)
        }.sortedWith(compareBy<NotifyHour> { it.hour }.thenByDescending { it.posted })

        val nightFrom = day.atTime(DayScores.NIGHT_FROM).atZone(zone).toInstant()
        val nightTo = minOf(day.plusDays(1).atTime(fajr(day.plusDays(1))).atZone(zone).toInstant(), now)
        val ons = sorted.filter { it.kind == UseKind.SCREEN_ON }
        val night = notes.filter { it.at >= nightFrom && it.at < nightTo }.groupBy { it.pkg }.map { (pkg, list) ->
            NightNotify(pkg, list.size, list.count { n -> ons.any { it.at >= n.at && it.at.toEpochMilli() - n.at.toEpochMilli() <= Slides.NOTIFICATION_LEAD_MS } })
        }.sortedByDescending { it.posted }

        return NotifyDay(day, posted.values.sum(), opened.values.sum(), unlocks.size, unlocks.count { u ->
            notes.any { it.at <= u.at && u.at.toEpochMilli() - it.at.toEpochMilli() <= Slides.NOTIFICATION_LEAD_MS }
        }, hours, night)
    }
}
