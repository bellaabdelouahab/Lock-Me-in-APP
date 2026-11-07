package app.lockmein

import android.content.Context
import app.lockmein.core.EventKind
import app.lockmein.core.EventLines
import app.lockmein.core.LockEvent
import java.io.File
import java.time.Instant

/** What happened, one line per event, in a file only this app can read. Kept to the newest 1000 events. */
class EventLog(context: Context) {
    private val file = File(context.filesDir, "events.log")

    // One lock for every instance: the alarm pass, the install receiver and the screens each make their own EventLog, and a
    // read-then-rewrite from two of them at once would drop events.
    fun add(kind: EventKind, detail: String = "", at: Instant = Instant.now()) = synchronized(LOCK) {
        val lines = EventLines.trim(readUnlocked().map(EventLines::encode) + EventLines.encode(LockEvent(at, kind, detail)))
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    fun read(): List<LockEvent> = synchronized(LOCK) { readUnlocked() }

    private fun readUnlocked(): List<LockEvent> = if (file.exists()) file.readLines().mapNotNull(EventLines::decode) else emptyList()

    fun clear() = synchronized(LOCK) { file.delete() }

    private companion object {
        val LOCK = Any()
    }
}
