package app.lockmein.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One thing that happened on the phone: an app came to the front, the screen went on or off, the phone was unlocked, a notification came. */
data class UseEvent(val at: Instant, val kind: UseKind, val pkg: String = "")

enum class UseKind {
    APP, SCREEN_ON, SCREEN_OFF, UNLOCK, NOTIFICATION,

    /** A try to open a closed app: Android's "blocked" dialog came up (package empty when the app could not be named). */
    BLOCKED,
}

/** Stored one event per line: epoch millis, kind, package. */
object UseLines {
    fun encode(e: UseEvent): String = "${e.at.toEpochMilli()}\t${e.kind}\t${e.pkg}"

    fun decode(line: String): UseEvent? {
        val parts = line.split('\t')
        if (parts.size < 2) return null
        val at = parts[0].toLongOrNull() ?: return null
        val kind = runCatching { UseKind.valueOf(parts[1]) }.getOrNull() ?: return null
        return UseEvent(Instant.ofEpochMilli(at), kind, parts.getOrElse(2) { "" })
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

/**
 * Turns the day's events into time per app, opens, unlocks and screen time. An app's time runs from when it came to the front
 * until another app did or the screen went off. Pure, so it is tested here.
 */
object PhoneUse {
    /** Longest an app can count without a fresh event (a missed screen-off must not turn into a 10-hour session). */
    const val MAX_STRETCH_MS = 2 * 60 * 60 * 1000L

    fun summarize(events: List<UseEvent>, day: LocalDate, zone: ZoneId, now: Instant, ignore: Set<String> = emptySet()): DayUse {
        val start = day.atStartOfDay(zone).toInstant()
        val end = minOf(day.plusDays(1).atStartOfDay(zone).toInstant(), now)
        val sorted = events.sortedBy { it.at }
        val time = HashMap<String, Long>()
        val opens = HashMap<String, Int>()
        val notes = HashMap<String, Int>()
        val openList = ArrayList<Pair<Instant, String>>()
        var screenMillis = 0L
        var unlocks = 0
        var firstUnlock: Instant? = null
        var lastOff: Instant? = null
        var notifications = 0
        val tryCount = HashMap<String, Int>()
        val tryList = ArrayList<Pair<Instant, String>>()

        var screenOn = false
        var screenSince: Instant? = null
        var current: String? = null
        var since: Instant? = null

        fun clip(from: Instant, to: Instant): Long {
            val a = maxOf(from, start)
            val b = minOf(to, end)
            return if (b > a) minOf(b.toEpochMilli() - a.toEpochMilli(), MAX_STRETCH_MS) else 0L
        }

        fun closeApp(at: Instant) {
            val app = current
            val from = since
            if (app != null && from != null && app !in ignore) time[app] = (time[app] ?: 0L) + clip(from, at)
            since = if (current != null) at else null
        }

        for (e in sorted) {
            if (e.at >= end) break
            val inDay = e.at >= start
            when (e.kind) {
                UseKind.SCREEN_ON -> {
                    if (!screenOn) { screenOn = true; screenSince = e.at }
                    if (current != null) since = e.at
                }
                UseKind.SCREEN_OFF -> {
                    closeApp(e.at)
                    since = null
                    screenSince?.let { if (screenOn) screenMillis += clip(it, e.at) }
                    screenOn = false
                    screenSince = null
                    if (inDay) lastOff = e.at
                }
                UseKind.UNLOCK -> if (inDay) {
                    unlocks++
                    if (firstUnlock == null) firstUnlock = e.at
                }
                UseKind.APP -> {
                    if (e.pkg == current) continue
                    if (since != null) closeApp(e.at)
                    current = e.pkg
                    since = e.at
                    if (!screenOn) { screenOn = true; screenSince = e.at } // an app on screen means the screen is on
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
            }
        }
        // Still on screen now (or at the day's end).
        if (since != null) closeApp(end)
        screenSince?.let { if (screenOn) screenMillis += clip(it, end) }

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
