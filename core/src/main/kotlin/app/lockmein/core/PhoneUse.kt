package app.lockmein.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One thing that happened on the phone: an app came to the front, the screen went on or off, the phone was unlocked, a
 * notification came. [detail] is set only for a try the dialog did not name: the app it came over, and the Settings page
 * ("com.android.settings > Accessibility").
 */
data class UseEvent(val at: Instant, val kind: UseKind, val pkg: String = "", val detail: String = "")

enum class UseKind {
    APP, SCREEN_ON, SCREEN_OFF, UNLOCK, NOTIFICATION,

    /** A notification was tapped open (from the notification listener). */
    NOTIFICATION_OPENED,

    /** A try to open a closed app: Android's "blocked" dialog came up (package empty when the app could not be named). */
    BLOCKED,
}

/** Stored one event per line: epoch millis, kind, package, and the detail when there is one. */
object UseLines {
    fun encode(e: UseEvent): String =
        "${e.at.toEpochMilli()}\t${e.kind}\t${e.pkg}" + if (e.detail.isEmpty()) "" else "\t" + e.detail.replace('\t', ' ').replace('\n', ' ')

    fun decode(line: String): UseEvent? {
        val parts = line.split('\t')
        if (parts.size < 2) return null
        val at = parts[0].toLongOrNull() ?: return null
        val kind = runCatching { UseKind.valueOf(parts[1]) }.getOrNull() ?: return null
        return UseEvent(Instant.ofEpochMilli(at), kind, parts.getOrElse(2) { "" }, parts.getOrElse(3) { "" })
    }
}

data class AppUse(val pkg: String, val millis: Long, val opens: Int, val notifications: Int, val tries: Int = 0)

data class DayUse(
    val day: LocalDate,
    /** Time with the screen on. */
    val screenMillis: Long,
    val unlocks: Int,
    val firstUnlock: Instant?,
    val lastScreenOff: Instant?,
    val notifications: Int,
    /** Most used first. */
    val apps: List<AppUse>,
    /** Every time an app came to the front: (time, app). */
    val opens: List<Pair<Instant, String>>,
    /** Tries to open a closed app (Android's "blocked" dialog), named or not. */
    val blockedTries: Int = 0,
    /** Every try: (time, app or empty). */
    val tries: List<Pair<Instant, String>> = emptyList(),
)

/** A stretch of screen-on time that counts: [from] to [to], unlocked or on the lock screen, and the app in front (null when none). */
data class ScreenSpan(val from: Instant, val to: Instant, val unlocked: Boolean, val app: String?) {
    val millis: Long get() = to.toEpochMilli() - from.toEpochMilli()

    /** The part of this span between [a] and [b], in millis. */
    fun within(a: Instant, b: Instant): Long {
        val x = maxOf(from, a)
        val y = minOf(to, b)
        return if (y > x) y.toEpochMilli() - x.toEpochMilli() else 0L
    }
}

/**
 * Turns the day's events into time per app, opens, unlocks and screen time. Every number comes from the same screen-on spans
 * ([PhoneUse.spans]), so an app's time is never more than the screen time it sits in. Pure, so it is tested here.
 */
object PhoneUse {
    /** Longest an unlocked screen can count without a fresh event (a missed screen-off must not turn into a 10-hour session). */
    const val MAX_STRETCH_MS = 2 * 60 * 60 * 1000L

    /**
     * Longest a lit lock screen counts. Past that it is an adhan or alarm screen, or a screen-off Android never sent (11-27 23:52
     * to 04:45), not use. A glance at a notification fits in it.
     */
    const val LOCKED_MAX_MS = 2 * 60 * 1000L

    /**
     * The screen-on time the events show, up to [until]. App time counts only while unlocked: from the unlock (or an app coming
     * to the front with the screen off, which means events were missed) until the screen goes off. A screen that comes on is
     * locked until the unlock, and counts at most [LOCKED_MAX_MS] that way. An unlocked screen counts at most [MAX_STRETCH_MS]
     * after the last unlock or app change.
     */
    fun spans(events: List<UseEvent>, until: Instant): List<ScreenSpan> {
        val out = ArrayList<ScreenSpan>()
        var on = false
        var unlocked = false
        var current: String? = null
        // The app that came to the front since the screen came on (what a lock screen shows: an adhan, the camera).
        var sinceOn: String? = null
        var windowEnd: Instant? = null
        var prev: Instant? = null
        var lastApp: UseEvent? = null

        fun emit(to: Instant) {
            val p = prev ?: return
            val w = windowEnd ?: return
            if (!on) return
            val b = minOf(to, w)
            if (b > p) out += ScreenSpan(p, b, unlocked, if (unlocked) current else sinceOn)
        }

        for (e in events.sortedBy { it.at }) {
            if (e.at >= until) break
            emit(e.at)
            prev = e.at
            when (e.kind) {
                UseKind.SCREEN_ON -> {
                    on = true; unlocked = false
                    // An app logged in the same instant came up with the screen (Android's order between the two varies).
                    sinceOn = lastApp?.takeIf { it.at == e.at }?.pkg
                    windowEnd = e.at.plusMillis(LOCKED_MAX_MS)
                }
                UseKind.UNLOCK -> {
                    on = true; unlocked = true
                    windowEnd = e.at.plusMillis(MAX_STRETCH_MS)
                }
                UseKind.SCREEN_OFF -> { on = false; unlocked = false; sinceOn = null; windowEnd = null }
                UseKind.APP -> {
                    current = e.pkg
                    lastApp = e
                    if (!on) { on = true; unlocked = true }
                    if (unlocked) windowEnd = e.at.plusMillis(MAX_STRETCH_MS) else sinceOn = e.pkg
                }
                UseKind.NOTIFICATION, UseKind.NOTIFICATION_OPENED, UseKind.BLOCKED -> Unit
            }
        }
        emit(until)
        // Merge touching spans of the same kind, so a notification in the middle does not split them.
        return out.fold(ArrayList()) { acc, s ->
            val last = acc.lastOrNull()
            if (last != null && last.to == s.from && last.unlocked == s.unlocked && last.app == s.app) acc[acc.size - 1] = last.copy(to = s.to) else acc += s
            acc
        }
    }

    /** Screen-on time between [from] and [to]. */
    fun screenMillis(events: List<UseEvent>, from: Instant, to: Instant): Long =
        if (!to.isAfter(from)) 0L else spans(events, to).sumOf { it.within(from, to) }

    /** Screen-on time per app between [from] and [to] (lock-screen time only for an app shown on it), most first. */
    fun appMillis(events: List<UseEvent>, from: Instant, to: Instant, ignore: Set<String> = emptySet()): List<Pair<String, Long>> {
        if (!to.isAfter(from)) return emptyList()
        val time = HashMap<String, Long>()
        for (s in spans(events, to)) {
            val app = s.app ?: continue
            if (app in ignore) continue
            val ms = s.within(from, to)
            if (ms > 0) time[app] = (time[app] ?: 0L) + ms
        }
        return time.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    fun summarize(events: List<UseEvent>, day: LocalDate, zone: ZoneId, now: Instant, ignore: Set<String> = emptySet()): DayUse {
        val start = day.atStartOfDay(zone).toInstant()
        val end = minOf(day.plusDays(1).atStartOfDay(zone).toInstant(), now)
        val sorted = events.sortedBy { it.at }
        val spans = spans(sorted, end)
        val time = HashMap<String, Long>()
        for (s in spans) {
            val app = s.app
            if (!s.unlocked || app == null || app in ignore) continue
            val ms = s.within(start, end)
            if (ms > 0) time[app] = (time[app] ?: 0L) + ms
        }
        val screenMillis = spans.sumOf { it.within(start, end) }
        val opens = HashMap<String, Int>()
        val notes = HashMap<String, Int>()
        val openList = ArrayList<Pair<Instant, String>>()
        var unlocks = 0
        var firstUnlock: Instant? = null
        var lastOff: Instant? = null
        var notifications = 0
        val tryCount = HashMap<String, Int>()
        val tryList = ArrayList<Pair<Instant, String>>()
        var current: String? = null

        for (e in sorted) {
            if (e.at >= end) break
            val inDay = e.at >= start
            when (e.kind) {
                UseKind.SCREEN_OFF -> if (inDay) lastOff = e.at
                UseKind.UNLOCK -> if (inDay) {
                    unlocks++
                    if (firstUnlock == null) firstUnlock = e.at
                }
                UseKind.APP -> {
                    if (e.pkg == current) continue
                    current = e.pkg
                    if (inDay && e.pkg !in ignore) {
                        opens[e.pkg] = (opens[e.pkg] ?: 0) + 1
                        openList += e.at to e.pkg
                    }
                }
                UseKind.NOTIFICATION -> if (inDay && e.pkg !in ignore) {
                    notifications++
                    notes[e.pkg] = (notes[e.pkg] ?: 0) + 1
                }
                UseKind.BLOCKED -> if (inDay) {
                    tryList += e.at to e.pkg
                    if (e.pkg.isNotEmpty()) tryCount[e.pkg] = (tryCount[e.pkg] ?: 0) + 1
                }
                UseKind.SCREEN_ON, UseKind.NOTIFICATION_OPENED -> Unit
            }
        }

        val apps = (time.keys + opens.keys + notes.keys + tryCount.keys).map { AppUse(it, time[it] ?: 0L, opens[it] ?: 0, notes[it] ?: 0, tryCount[it] ?: 0) }
            .sortedWith(compareByDescending<AppUse> { it.millis }.thenByDescending { it.opens }.thenByDescending { it.notifications })
        return DayUse(day, screenMillis, unlocks, firstUnlock, lastOff, notifications, apps, openList, tryList.size, tryList)
    }

    /** CSV for analysis on a laptop: one row per event, in local time. */
    fun csv(events: List<UseEvent>, zone: ZoneId, label: (String) -> String = { it }): String = buildString {
        append("time,event,package,app\n")
        for (e in events.sortedBy { it.at }) {
            val name = if (e.pkg.isEmpty()) "" else label(e.pkg).replace("\"", "\"\"")
            append(e.at.atZone(zone).toLocalDateTime()).append(',').append(e.kind.name.lowercase()).append(',')
                .append(e.pkg).append(",\"").append(name).append("\"\n")
        }
    }
}
