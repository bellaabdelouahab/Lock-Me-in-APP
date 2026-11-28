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
    /**
     * The app whose notification started this session: the last one posted in the [Slides.NOTIFICATION_LEAD_MS] before the
     * unlock. Null when none came (see [sessionKnown]).
     */
    val startedBy: String?,
    /** The session's start (an unlock or screen-on) was found, so a null [startedBy] means "no notification", not "unknown". */
    val sessionKnown: Boolean = false,
    /** For a try the dialog did not name: the app the dialog came over (Settings, Google). */
    val over: String? = null,
    /** And when that was Settings, the page it was on (Accessibility, Apps, Device admin apps). */
    val page: String? = null,
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
    /** In bed: no more than [DayScores.BED_GRACE_MINUTES] of screen time from 22:00 to 04:00 the next morning; null before 22:00. */
    val inBedBy22: Boolean?,
    /** 22:00 to 04:00 the next morning: the bed score's number. */
    val screenMinutes22To04: Long,
    /** The apps on screen from 22:00 to 04:00, most first (lock-screen time too, for an app shown on it: an alarm, an adhan). */
    val apps22To04: List<AppUse>,
    /** 22:00 until the next day's fajr. */
    val screenMinutesAfter22: Long,
    /** 21:30 until the next day's fajr (so far, if that is still to come). */
    val screenMinutesNight: Long,
    /** Midnight until fajr of the same night (the next morning): part of [screenMinutesAfter22], never more. */
    val screenMinutesBeforeFajr: Long,
    val screenMinutes: Long,
    /** The day's last screen-off (before midnight), or null. */
    val lastScreenOff: Instant? = null,
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
        /** The bed score's window ends here, not at fajr: a 05:00 wake must not fail the night. */
        val BED_UNTIL: LocalTime = LocalTime.of(4, 0)
    }
}

object Slides {
    /** How long before an unlock a notification can be what started it. */
    const val NOTIFICATION_LEAD_MS = 60_000L

    /** Notifications that are never what started a session: Android itself. */
    private val NOT_A_STARTER = setOf("android", "com.android.systemui")

    fun lead(events: List<UseEvent>, at: Instant, app: String, ignore: Set<String>, detail: String = ""): Lead {
        val before = events.filter { it.at <= at }.sortedBy { it.at }
        val previous = before.lastOrNull { it.kind == UseKind.APP && it.pkg !in ignore && it.pkg != app }?.pkg
        val start = before.lastOrNull { it.kind == UseKind.UNLOCK } ?: before.lastOrNull { it.kind == UseKind.SCREEN_ON }
        val startedBy = start?.let { s ->
            before.lastOrNull {
                it.kind == UseKind.NOTIFICATION && it.at <= s.at && s.at.toEpochMilli() - it.at.toEpochMilli() <= NOTIFICATION_LEAD_MS &&
                    it.pkg !in ignore && it.pkg !in NOT_A_STARTER
            }?.pkg
        }
        val over = detail.substringBefore(" > ").takeIf { it.isNotBlank() }
        val page = detail.substringAfter(" > ", "").takeIf { it.isNotBlank() }
        return Lead(at, app, previous, start?.let { (at.toEpochMilli() - it.at.toEpochMilli()) / 1000 }, startedBy, start != null, over, page)
    }

    /** Screen-on time between [from] and [to], from the same spans as every other number ([PhoneUse.spans]). */
    fun screenMillis(events: List<UseEvent>, from: Instant, to: Instant): Long = PhoneUse.screenMillis(events, from, to)

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
        val fajrNext = day.plusDays(1).atTime(fajr(day.plusDays(1))).atZone(zone).toInstant()
        val bedAt = day.atTime(DayScores.BED_AT).atZone(zone).toInstant()
        val nightFrom = day.atTime(DayScores.NIGHT_FROM).atZone(zone).toInstant()
        val bedUntil = day.plusDays(1).atTime(DayScores.BED_UNTIL).atZone(zone).toInstant()
        val cut = { t: Instant -> minOf(t, now) }
        val min = { ms: Long -> ms / 60_000 }

        val after22 = min(screenMillis(uses, bedAt, cut(fajrNext)))
        val bed = min(screenMillis(uses, bedAt, cut(bedUntil)))
        val summary = PhoneUse.summarize(uses.filter { it.at < end }, day, zone, now, ignore)
        val inDay = { t: Instant -> !t.isBefore(start) && t.isBefore(end) }
        return DayScores(
            day = day,
            inBedBy22 = if (now.isBefore(bedAt)) null else bed <= DayScores.BED_GRACE_MINUTES,
            screenMinutes22To04 = bed,
            apps22To04 = PhoneUse.appMillis(uses, bedAt, cut(bedUntil), ignore).map { (pkg, ms) -> AppUse(pkg, ms, 0, 0) },
            screenMinutesAfter22 = after22,
            screenMinutesNight = min(screenMillis(uses, nightFrom, cut(fajrNext))),
            screenMinutesBeforeFajr = min(screenMillis(uses, end, cut(fajrNext))),
            screenMinutes = min(summary.screenMillis),
            lastScreenOff = summary.lastScreenOff,
            unlocks = summary.unlocks,
            tries = uses.filter { it.kind == UseKind.BLOCKED && inDay(it.at) && it.at < cut(end) }.sortedBy { it.at }
                .map { lead(uses, it.at, it.pkg, ignore, it.detail) },
            blocks = blocks.filter { inDay(it.first) }.sortedBy { it.first }.map { (at, pkg) -> lead(uses, at, pkg, ignore) },
            loosenAsked = events.count { inDay(it.at) && it.kind == EventKind.LOOSEN_REQUESTED },
            tightened = events.count { inDay(it.at) && it.kind == EventKind.TIGHTENED },
            topApps = summary.apps.filter { it.millis > 0 }.take(3),
            moods = moods.filter { inDay(it.at) }.sortedBy { it.at },
        )
    }
}

/** Spec N4: the week as one row a day, the table the Saturday session used to build by hand. */
object WeeklyTable {
    fun text(days: List<DayScores>, zone: ZoneId): String = buildString {
        appendLine("Day        | in bed | 22-04 min | last off | after 21:30 | tries | blocks | open-up asks | tightenings | moods")
        for (d in days.sortedBy { it.day }) {
            val bed = when (d.inBedBy22) { true -> "yes"; false -> "no"; null -> "-" }
            val off = d.lastScreenOff?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0)?.toString() ?: "-"
            appendLine("${d.day} | $bed | ${d.screenMinutes22To04} | $off | ${d.screenMinutesNight} | ${d.tries.size} | ${d.blocks.size} | ${d.loosenAsked} | ${d.tightened} | ${d.moods.size}")
        }
    }.trimEnd()
}

/** Spec D4: how the rules drift, as one number: requests to open up minus tightenings over the last [days] days. */
data class Drift(val openUpAsked: Int, val tightened: Int) {
    val net: Int get() = openUpAsked - tightened

    companion object {
        fun of(events: List<LockEvent>, now: Instant, days: Long = 14): Drift {
            val from = now.minus(java.time.Duration.ofDays(days))
            val recent = events.filter { !it.at.isBefore(from) && !it.at.isAfter(now) }
            return Drift(recent.count { it.kind == EventKind.LOOSEN_REQUESTED }, recent.count { it.kind == EventKind.TIGHTENED })
        }
    }
}
