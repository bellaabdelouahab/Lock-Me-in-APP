package app.lockmein

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lockmein.core.PrayerTimes
import app.lockmein.core.PromptDue
import app.lockmein.core.PromptRecord
import app.lockmein.core.PromptSchedule
import app.lockmein.core.UseKind
import app.lockmein.ui.LockTheme
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * The center prompt (spec N2): one question in the middle of the screen at the first unlock after 07:45, 13:00 and 19:15 (moved
 * away from prayer times, none after 21:30), never over a call or Maps or Waze. One tap answers; "Later" twice (15 min each),
 * then it is logged as skipped. Each answer or skip goes to [PromptLog] with what was on screen before and the time since the
 * unlock; a score also goes to the mood log.
 */
object CenterPrompt {
    private const val PREFS = "lock_prompt"
    private val NAVIGATION = setOf("com.google.android.apps.maps", "com.waze")
    private val worker = Executors.newSingleThreadExecutor()

    /** From the screen receiver at each unlock. */
    fun onUnlock(context: Context) {
        val app = context.applicationContext
        worker.execute { runCatching { maybeShow(app, System.currentTimeMillis()) } }
    }

    private fun maybeShow(context: Context, unlockMs: Long) {
        if (BuildConfig.PREVIEW || !ConfigStore(context).protectionActive) return
        if (!context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)) return
        val config = ConfigStore(context).load() ?: return
        // Not over a call (or a voice or video chat), not while navigating.
        val mode = context.getSystemService(AudioManager::class.java).mode
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE) return
        Thread.sleep(1_500) // let the app that opens with the unlock come to the front first
        if (FilterService.foregroundNow in NAVIGATION) return
        val zone = ZoneId.systemDefault()
        val now = TrustedClock(context).now().atZone(zone)
        val prayers = PrayerTimes(config.fajrConfig(), zone).on(now.toLocalDate()).all
        val slot = PromptDue.at(now.toLocalTime(), System.currentTimeMillis(), now.toLocalDate(), prayers, records(context)) ?: return
        context.startActivity(
            Intent(context, PromptActivity::class.java)
                .putExtra(PromptActivity.SLOT, slot.toString())
                .putExtra(PromptActivity.UNLOCK_MS, unlockMs)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    /** Today's records (older days are dropped as they are written). */
    fun records(context: Context): List<PromptRecord> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("records", emptySet()).orEmpty().mapNotNull { line ->
            val p = line.split('|')
            runCatching { PromptRecord(LocalDate.parse(p[0]), LocalTime.parse(p[1]), p[2].toInt(), p[3].toLong(), p.getOrNull(4)?.ifEmpty { null }) }.getOrNull()
        }

    fun save(context: Context, r: PromptRecord) {
        val keep = records(context).filter { it.day == r.day && it.slot != r.slot } + r
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("records", keep.map { "${it.day}|${it.slot}|${it.snoozes}|${it.snoozedUntilMs}|${it.result.orEmpty()}" }.toSet()).apply()
    }
}

/** Every prompt answered or skipped, one line each, on the phone: when, which slot, the result, snoozes, the app before, seconds since the unlock. */
class PromptLog(context: Context) {
    private val file = File(context.noBackupFilesDir, "prompts.log")

    data class Line(val at: Instant, val slot: String, val result: String, val score: Int?, val snoozes: Int, val appBefore: String, val secondsSinceUnlock: Long)

    fun add(l: Line) {
        val text = listOf(l.at.toEpochMilli(), l.slot, l.result, l.score ?: "", l.snoozes, l.appBefore, l.secondsSinceUnlock).joinToString("\t") + "\n"
        writer.execute { runCatching { file.parentFile?.mkdirs(); file.appendText(text) } }
    }

    fun read(): List<Line> = if (!file.exists()) emptyList() else runCatching {
        file.readLines().mapNotNull { s ->
            val p = s.split('\t')
            runCatching { Line(Instant.ofEpochMilli(p[0].toLong()), p[1], p[2], p[3].toIntOrNull(), p[4].toInt(), p[5], p[6].toLong()) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    companion object {
        private val writer = Executors.newSingleThreadExecutor()
    }
}

/** The card itself: the question, five buttons, and "Later" (or "Skip" once both snoozes are used). */
class PromptActivity : ComponentActivity() {
    private var done = false
    private lateinit var slot: LocalTime
    private var unlockMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        slot = runCatching { LocalTime.parse(intent.getStringExtra(SLOT)) }.getOrNull() ?: return finish()
        unlockMs = intent.getLongExtra(UNLOCK_MS, System.currentTimeMillis())
        val today = LocalDate.now()
        val rec = CenterPrompt.records(this).firstOrNull { it.day == today && it.slot == slot } ?: PromptRecord(today, slot, 0, 0, null)
        val lastChance = rec.snoozes >= PromptSchedule.MAX_SNOOZES
        setContent {
            LockTheme {
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                    Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("How is your mood right now?", style = MaterialTheme.typography.titleLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            (1..5).forEach { score ->
                                FilledTonalButton(onClick = { answer(rec, score) }, modifier = Modifier.size(56.dp), contentPadding = PaddingValues(0.dp)) {
                                    Text("$score", style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                        Text("1 low   ...   5 good", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { later(rec); finish() }) { Text(if (lastChance) "Skip" else "Later (15 min)") }
                    }
                }
            }
        }
    }

    /** Leaving without a tap (home, back, the screen going off) counts as "Later". */
    override fun onStop() {
        super.onStop()
        if (!done && ::slot.isInitialized) {
            val today = LocalDate.now()
            later(CenterPrompt.records(this).firstOrNull { it.day == today && it.slot == slot } ?: PromptRecord(today, slot, 0, 0, null))
            finish()
        }
    }

    private fun answer(rec: PromptRecord, score: Int) {
        done = true
        CenterPrompt.save(this, rec.copy(result = PromptRecord.ANSWERED))
        MoodLog(this).add(score, "prompt $slot")
        log(PromptRecord.ANSWERED, score, rec.snoozes)
        finish()
    }

    private fun later(rec: PromptRecord) {
        if (done) return
        done = true
        if (rec.snoozes >= PromptSchedule.MAX_SNOOZES) {
            CenterPrompt.save(this, rec.copy(result = PromptRecord.SKIPPED))
            log(PromptRecord.SKIPPED, null, rec.snoozes)
        } else {
            CenterPrompt.save(this, rec.copy(snoozes = rec.snoozes + 1, snoozedUntilMs = System.currentTimeMillis() + PromptSchedule.SNOOZE.toMillis()))
        }
    }

    private fun log(result: String, score: Int?, snoozes: Int) {
        val now = System.currentTimeMillis()
        val before = PhoneUseLog(this).read(LocalDate.now()).filter { it.kind == UseKind.APP && it.at.toEpochMilli() <= unlockMs && it.pkg != packageName }
            .lastOrNull()?.pkg.orEmpty()
        PromptLog(this).add(PromptLog.Line(Instant.ofEpochMilli(now), slot.toString(), result, score, snoozes, before, (now - unlockMs) / 1000))
    }

    companion object {
        const val SLOT = "slot"
        const val UNLOCK_MS = "unlock_ms"
    }
}
