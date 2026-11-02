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

    @Synchronized
    fun add(kind: EventKind, detail: String = "", at: Instant = Instant.now()) {
        val lines = EventLines.trim(read().map(EventLines::encode) + EventLines.encode(LockEvent(at, kind, detail)))
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    @Synchronized
    fun read(): List<LockEvent> = if (file.exists()) file.readLines().mapNotNull(EventLines::decode) else emptyList()

    @Synchronized
    fun clear() {
        file.delete()
    }
}
