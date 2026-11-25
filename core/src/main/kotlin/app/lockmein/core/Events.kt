package app.lockmein.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class EventKind {
    /** A policy pass finished with everything in force. */
    PASS_OK,

    /** A policy pass left something undone (detail says what). */
    PASS_FAILED,

    /** A new app appeared and was suspended until reviewed. */
    APP_QUARANTINED,
    APP_RELEASED,
    BROWSER_REQUESTED,
    BROWSER_APPROVED,

    /** Something that opens the phone up was asked for and is waiting. */
    LOOSEN_REQUESTED,

    /** A waiting request was cancelled. */
    REQUEST_CANCELLED,

    /** A waiting request was carried out in the open window. */
    LOOSENED,

    /** A change was refused (detail says why). */
    DENIED,

    /** A change that closes the phone more was applied. */
    TIGHTENED,
    EMERGENCY_USED,
    EMERGENCY_REFUSED,
    REMOVAL_REQUESTED,
    PROTECTION_STARTED,
    PROTECTION_REMOVED,

    /** Screen filter detected sexual or revealing content. */
    FILTER_HIT,

    /** App exited and suspended for 30 minutes following second detection. */
    FILTER_BLOCKED,

    /** Warning cleared after a clean check. */
    FILTER_CLEARED,

    /** A block saved for review with "Wrong block?" (spec v3.1 F3); the detail holds the day's count. */
    FILTER_SAVED,

    /** A try to open a closed app (Android's "blocked" dialog came up). */
    BLOCKED_TRY,

    /** A change asked through the MCP (Lock Me In relay), with the app's answer. */
    REMOTE_CHANGE,

    /** A watched app showed a screen the filter cannot see (a private tab) and was sent home (FEATURES 3.16). */
    HIDDEN_SCREEN,

    /** A closed app's site was open in a browser and was left (FEATURES 3.18); the detail names the site, the app and the browser. */
    SITE_CLOSED,

    /** Blocks sent to the training server (FEATURES 3.20); the detail holds how many. */
    TRAINING_SENT,

    /** A judge the trainer passed was filed through the change gate; the detail holds the version and the answer. */
    TRAINING_OFFER,

    /** The private DNS server stopped answering while the network worked: plain automatic DNS until it is back (FEATURES 3.17). */
    DNS_FALLBACK,

    /** The private DNS server answered again and is pinned back; the detail holds the minutes without it. */
    DNS_RESTORED,
}

data class LockEvent(val at: Instant, val kind: EventKind, val detail: String = "")

/** A week of events in numbers, for the Sunday session. */
data class WeeklyReport(
    val from: Instant,
    val to: Instant,
    val counts: Map<EventKind, Int>,
    val newApps: List<String>,
    val refusals: List<LockEvent>,
    val problems: List<LockEvent>,
) {
    fun count(kind: EventKind) = counts[kind] ?: 0

    fun toText(zone: ZoneId): String {
        val day = DateTimeFormatter.ofPattern("EEE d MMM HH:mm").withZone(zone)
        return buildString {
            appendLine("Lock Me In, ${day.format(from)} to ${day.format(to)}")
            appendLine("Policy passes: ${count(EventKind.PASS_OK)} ok, ${count(EventKind.PASS_FAILED)} with problems")
            appendLine("Emergency windows used: ${count(EventKind.EMERGENCY_USED)} (refused codes: ${count(EventKind.EMERGENCY_REFUSED)})")
            appendLine("Changes that opened the phone up: ${count(EventKind.LOOSENED)}; refused: ${count(EventKind.DENIED)}")
            appendLine("Changes that closed it more: ${count(EventKind.TIGHTENED)}")
            appendLine("Requests to open up: ${count(EventKind.LOOSEN_REQUESTED)} (vs ${count(EventKind.TIGHTENED)} that closed it more)")
            appendLine("Removal requests: ${count(EventKind.REMOVAL_REQUESTED)}")
            appendLine("Browsers asked for: ${count(EventKind.BROWSER_REQUESTED)}, approved: ${count(EventKind.BROWSER_APPROVED)}")
            if (count(EventKind.FILTER_BLOCKED) > 0 || count(EventKind.FILTER_HIT) > 0) {
                appendLine("Screen filter detections: ${count(EventKind.FILTER_HIT)} hits, ${count(EventKind.FILTER_BLOCKED)} blocks")
            }
            if (count(EventKind.FILTER_SAVED) > 0) appendLine("Blocks saved as wrong for review: ${count(EventKind.FILTER_SAVED)}")
            if (count(EventKind.REMOTE_CHANGE) > 0) appendLine("Changes asked through the MCP: ${count(EventKind.REMOTE_CHANGE)}")
            if (count(EventKind.BLOCKED_TRY) > 0) appendLine("Tries to open a closed app: ${count(EventKind.BLOCKED_TRY)}")
            if (count(EventKind.DNS_FALLBACK) > 0) appendLine("DNS server down: ${count(EventKind.DNS_FALLBACK)} times, plain DNS (unfiltered) meanwhile")
            if (count(EventKind.TRAINING_SENT) > 0) appendLine("Days the filter's blocks went to training: ${count(EventKind.TRAINING_SENT)}")
            if (count(EventKind.SITE_CLOSED) > 0) appendLine("Closed apps' sites left in the browser: ${count(EventKind.SITE_CLOSED)}")
            if (count(EventKind.HIDDEN_SCREEN) > 0) appendLine("Private tabs sent home: ${count(EventKind.HIDDEN_SCREEN)}")
            if (newApps.isNotEmpty()) appendLine("New apps held back: ${newApps.joinToString()}")
            refusals.forEach { appendLine("Refused ${day.format(it.at)}: ${it.detail}") }
            problems.forEach { appendLine("Problem ${day.format(it.at)}: ${it.detail}") }
        }.trimEnd()
    }
}

object Reports {
    fun week(events: List<LockEvent>, now: Instant, length: Duration = Duration.ofDays(7)): WeeklyReport {
        val from = now.minus(length)
        val inRange = events.filter { !it.at.isBefore(from) && !it.at.isAfter(now) }.sortedBy { it.at }
        return WeeklyReport(
            from = from,
            to = now,
            counts = inRange.groupingBy { it.kind }.eachCount(),
            newApps = inRange.filter { it.kind == EventKind.APP_QUARANTINED }.map { it.detail }.distinct(),
            refusals = inRange.filter { it.kind == EventKind.DENIED || it.kind == EventKind.EMERGENCY_REFUSED },
            problems = inRange.filter { it.kind == EventKind.PASS_FAILED },
        )
    }
}

/** The event log as lines of text, newest kept: one event per line, `epochMillis<TAB>KIND<TAB>detail`. */
object EventLines {
    fun encode(event: LockEvent): String = "${event.at.toEpochMilli()}\t${event.kind.name}\t${event.detail.replace('\n', ' ').replace('\t', ' ')}"

    fun decode(line: String): LockEvent? {
        val parts = line.split('\t', limit = 3)
        if (parts.size < 2) return null
        val at = parts[0].toLongOrNull()?.let(Instant::ofEpochMilli) ?: return null
        val kind = EventKind.entries.firstOrNull { it.name == parts[1] } ?: return null
        return LockEvent(at, kind, parts.getOrElse(2) { "" })
    }

    /** Keeps only the newest [max] lines. */
    fun trim(lines: List<String>, max: Int = 1000): List<String> = if (lines.size <= max) lines else lines.takeLast(max)
}
