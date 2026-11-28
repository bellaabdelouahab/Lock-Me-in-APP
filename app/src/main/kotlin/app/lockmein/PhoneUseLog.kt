package app.lockmein

import android.content.Context
import app.lockmein.core.UseEvent
import app.lockmein.core.UseKind
import app.lockmein.core.UseLines
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * What the phone was used for: apps coming to the front, screen on and off, unlocks and notifications, one small file per day in
 * the app's private storage (never shared, excluded from backups), kept [KEEP_DAYS] days. No screen content, no taps, no text.
 */
class PhoneUseLog(context: Context) {
    private val dir = File(context.noBackupFilesDir, "phone-use")

    fun add(kind: UseKind, pkg: String = "", at: Instant = Instant.now(), detail: String = "") {
        val line = UseLines.encode(UseEvent(at, kind, pkg, detail)) + "\n"
        writer.execute {
            runCatching {
                dir.mkdirs()
                val day = at.atZone(ZoneId.systemDefault()).toLocalDate()
                File(dir, "$day.log").appendText(line)
                if (day != lastPruned) {
                    lastPruned = day
                    dir.listFiles()?.filter { f -> f.name.removeSuffix(".log").let { runCatching { LocalDate.parse(it) }.getOrNull() }?.isBefore(day.minusDays(KEEP_DAYS)) == true }
                        ?.forEach { it.delete() }
                }
            }
        }
    }

    /** The events of [day] and the day before (an app opened before midnight still counts after it). */
    fun read(day: LocalDate): List<UseEvent> = listOf(day.minusDays(1), day).flatMap { d ->
        val f = File(dir, "$d.log")
        if (f.exists()) runCatching { f.readLines().mapNotNull(UseLines::decode) }.getOrDefault(emptyList()) else emptyList()
    }

    /** Every event kept, oldest first (for the export). */
    fun readAll(): List<UseEvent> = dir.listFiles().orEmpty().sortedBy { it.name }.flatMap { f ->
        runCatching { f.readLines().mapNotNull(UseLines::decode) }.getOrDefault(emptyList())
    }

    /** The oldest day kept: the log began on 2026-11-22 (0.4.10x), so nothing exists before it. */
    fun firstDay(): LocalDate? = dir.listFiles().orEmpty().mapNotNull { runCatching { LocalDate.parse(it.name.removeSuffix(".log")) }.getOrNull() }.minOrNull()

    companion object {
        const val KEEP_DAYS = 60L
        private val writer = Executors.newSingleThreadExecutor()
        @Volatile private var lastPruned: LocalDate? = null
    }
}
