package app.lockmein.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** What came just before a try on a closed app or a filter block: how a slide starts. */
data class Lead(
    val at: Instant,
    /** The closed app tried, or the app the filter closed (empty when it could not be named). */
    val app: String,
    /** The last app on screen before it (home screen and system UI left out), or null. */
    val previousApp: String?,
    /** Seconds since the phone was last unlocked (or the screen came on), or null when nothing is known. */
    val sinceUnlockSeconds: Long?,
    /** The app whose notification started this session: it came just before the unlock and was the first app opened. */
    val startedBy: String?,
)

/** A one-tap mood score, 1 (low) to 5 (good), and what asked for it. */
data class MoodEntry(val at: Instant, val score: Int, val asked: String) {
    companion object {
        const val DAILY = "daily"
        const val AFTER_TRY = "after a try"
        const val BY_HAND = "by hand"

        fun encode(m: MoodEntry) = "${m.at.toEpochMilli()}\t${m.score}\t${m.asked}"
        fun decode(line: String): MoodEntry? {
            val p = line.split('\t')
            val at = p.getOrNull(0)?.toLongOrNull() ?: return null
            val score = p.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..5 } ?: return null
            return MoodEntry(Instant.ofEpochMilli(at), score, p.getOrElse(2) { BY_HAND })
        }
    }
}

/** One day in a few numbers, for a morning check-in: sleep, late screen time, tries, blocks, requests, top apps and mood. */
data class DayScores(
    val day: LocalDate,
    /** No more than [DayScores.BED_GRACE_MINUTES] of screen time from 22:00 until the next fajr; null before 22:00. */
    val inBedBy22: Boolean?,
    val screenMinutesAfter22: Long,
    /** 21:30 until the next day's fajr (so far, if that is still to come). */
    val screenMinutesNight: Long,
    /** Midnight until this day's fajr. */
    val screenMinutesBeforeFajr: Long,
    val screenMinutes: Long,
    val unlocks: Int,
    val tries: List<Lead>,
    val blocks: List<Lead>,
    val loosenAsked: Int,
    val tightened: Int,
    val topApps: List<AppUse>,
    val moods: List<MoodEntry>,
) {
    companion object {
        const val BED_GRACE_MINUTES = 3L
        val NIGHT_FROM: LocalTime = LocalTime.of(21, 30)
        val BED_AT: LocalTime = LocalTime.of(22, 0)
    }
}

object Slides {
    /** How long before an unlock a notification can be what started it. */
    const val NOTIFICATION_LEAD_MS = 2 * 60_000L

    fun lead(events: List<UseEvent>, at: Instant, app: String, ignore: Set<String>): Lead {
        val before = events.filter { it.at <= at }.sortedBy { it.at }
        val previous = before.lastOrNull { it.kind == UseKind.APP && it.pkg !in ignore && it.pkg != app }?.pkg
        val start = before.lastOrNull { it.kind == UseKind.UNLOCK } ?: before.lastOrNull { it.kind == UseKind.SCREEN_ON }
        val startedBy = start?.let { s ->
            val firstApp = events.filter { it.at >= s.at && it.at <= at && it.kind == UseKind.APP && it.pkg !in ignore }.minByOrNull { it.at }?.pkg
            before.lastOrNull {
                it.kind == UseKind.NOTIFICATION && it.at <= s.at && s.at.toEpochMilli() - it.at.toEpochMilli() <= NOTIFICATION_LEAD_MS && it.pkg == firstApp
            }?.pkg
        }
        return Lead(at, app, previous, start?.let { (at.toEpochMilli() - it.at.toEpochMilli()) / 1000 }, startedBy)
    }

    /** Screen-on time between [from] and [to], from the use events (an app on screen means the screen is on). */
    fun screenMillis(events: List<UseEvent>, from: Instant, to: Instant): Long {
        if (!to.isAfter(from)) return 0
        var total = 0L
        var since: Instant? = null
        fun close(at: Instant) {
            val s = since ?: return
            val a = maxOf(s, from)
            val b = minOf(at, to, s.plusMillis(PhoneUse.MAX_STRETCH_MS))
            if (b > a) total += b.toEpochMilli() - a.toEpochMilli()
            since = null
        }
        for (e in events.sortedBy { it.at }) {
            if (e.at >= to) break
            when (e.kind) {
                UseKind.SCREEN_ON, UseKind.UNLOCK, UseKind.APP -> {
                    // A fresh event while on restarts the stretch cap without losing time.
                    if (since != null) close(e.at)
                    since = e.at
                }
                UseKind.SCREEN_OFF -> close(e.at)
                else -> Unit
            }
        }
        close(to)
        return total
    }

    /**
     * The day's scores. [uses] must cover the day before (for what was on screen at midnight) through the morning after (for the
     * night); [fajr] gives a day's fajr time; [blocks] are the filter's closes as (time, app); [now] cuts what is still to come.
     */
    fun day(
        day: LocalDate,
        zone: ZoneId,
        now: Instant,
        uses: List<UseEvent>,
        events: List<LockEvent>,
        blocks: List<Pair<Instant, String>>,
        moods: List<MoodEntry>,
        fajr: (LocalDate) -> LocalTime,
        ignore: Set<String>,
    ): DayScores {
        val start = day.atStartOfDay(zone).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        val fajrToday = day.atTime(fajr(day)).atZone(zone).toInstant()
        val fajrNext = day.plusDays(1).atTime(fajr(day.plusDays(1))).atZone(zone).toInstant()
        val bedAt = day.atTime(DayScores.BED_AT).atZone(zone).toInstant()
        val nightFrom = day.atTime(DayScores.NIGHT_FROM).atZone(zone).toInstant()
        val cut = { t: Instant -> minOf(t, now) }
        val min = { ms: Long -> ms / 60_000 }

        val after22 = min(screenMillis(uses, bedAt, cut(fajrNext)))
        val summary = PhoneUse.summarize(uses.filter { it.at < end }, day, zone, now, ignore)
        val inDay = { t: Instant -> !t.isBefore(start) && t.isBefore(end) }
        return DayScores(
            day = day,
            inBedBy22 = if (now.isBefore(bedAt)) null else after22 <= DayScores.BED_GRACE_MINUTES,
            screenMinutesAfter22 = after22,
            screenMinutesNight = min(screenMillis(uses, nightFrom, cut(fajrNext))),
            screenMinutesBeforeFajr = min(screenMillis(uses, start, cut(fajrToday))),
            screenMinutes = min(summary.screenMillis),
            unlocks = summary.unlocks,
            tries = summary.tries.map { (at, pkg) -> lead(uses, at, pkg, ignore) },
            blocks = blocks.filter { inDay(it.first) }.sortedBy { it.first }.map { (at, pkg) -> lead(uses, at, pkg, ignore) },
            loosenAsked = events.count { inDay(it.at) && it.kind == EventKind.LOOSEN_REQUESTED },
            tightened = events.count { inDay(it.at) && it.kind == EventKind.TIGHTENED },
            topApps = summary.apps.filter { it.millis > 0 }.take(3),
            moods = moods.filter { inDay(it.at) }.sortedBy { it.at },
        )
    }
}
