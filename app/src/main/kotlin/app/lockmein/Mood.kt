package app.lockmein

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import app.lockmein.core.MoodEntry
import app.lockmein.ui.LockTheme
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.Executors

/** The mood scores, one line each, in the app's private storage (never shared, excluded from backups). */
class MoodLog(context: Context) {
    private val file = File(context.noBackupFilesDir, "mood.log")

    fun add(score: Int, asked: String, at: Instant = Instant.now()) {
        require(score in 1..5)
        val line = MoodEntry.encode(MoodEntry(at, score, asked)) + "\n"
        writer.execute { runCatching { file.parentFile?.mkdirs(); file.appendText(line) } }
    }

    fun read(): List<MoodEntry> =
        if (file.exists()) runCatching { file.readLines().mapNotNull(MoodEntry::decode) }.getOrDefault(emptyList()) else emptyList()

    companion object {
        private val writer = Executors.newSingleThreadExecutor()
    }
}

/**
 * Asks for a mood score with one notification: once a day from [DAILY_AT] (when the phone is with him, not in the evening when it
 * stays at the office), and after a try on a closed app at most every [AFTER_TRY_GAP_MS]. A tap opens five buttons; ignoring it
 * costs nothing, it goes away on its own.
 */
object MoodAsk {
    val DAILY_AT: LocalTime = LocalTime.of(13, 0)
    const val AFTER_TRY_GAP_MS = 2 * 60 * 60_000L
    private const val CHANNEL = "mood"
    private const val ID = 31
    private const val PREFS = "lock_mood"

    /** From the filter watcher's tick. */
    fun tick(context: Context) {
        if (BuildConfig.PREVIEW || !ConfigStore(context).protectionActive) return
        val zone = ZoneId.systemDefault()
        val now = TrustedClock(context).now().atZone(zone)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = now.toLocalDate().toString()
        if (now.toLocalTime().isBefore(DAILY_AT) || prefs.getString("daily_on", null) == today) return
        prefs.edit().putString("daily_on", today).apply()
        if (MoodLog(context).read().any { it.at.atZone(zone).toLocalDate() == LocalDate.parse(today) }) return
        post(context, MoodEntry.DAILY, "How is your mood right now?", timeoutMs = 3 * 60 * 60_000L)
    }

    /** Right after a try on a closed app. */
    fun afterTry(context: Context) {
        if (BuildConfig.PREVIEW || !ConfigStore(context).protectionActive) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("after_try_at", 0L) < AFTER_TRY_GAP_MS) return
        prefs.edit().putLong("after_try_at", now).apply()
        post(context, MoodEntry.AFTER_TRY, "That app is closed. How is your mood right now?", timeoutMs = 30 * 60_000L)
    }

    fun cancel(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID)

    @android.annotation.SuppressLint("MissingPermission") // checked in allowed()
    private fun post(context: Context, asked: String, text: String, timeoutMs: Long) {
        if (!allowed(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mood check", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) })
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MoodActivity::class.java).putExtra(MoodActivity.ASKED, asked).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Mood, 1 to 5")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(timeoutMs)
            .build()
        runCatching { nm.notify(ID, n) }
    }

    /** Android 13 and later need the notification permission; the device owner grants it to its own app. */
    private fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return true
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return false
        runCatching {
            dpm.setPermissionGrantState(
                ComponentName(context, AdminReceiver::class.java), context.packageName, Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}

/** Five buttons and nothing else: the score is saved and the screen closes. */
class MoodActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val asked = intent.getStringExtra(ASKED) ?: MoodEntry.BY_HAND
        MoodAsk.cancel(this)
        setContent {
            LockTheme {
                Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("How is your mood right now?", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (1..5).forEach { score ->
                                FilledTonalButton(onClick = { MoodLog(this@MoodActivity).add(score, asked); finish() }, modifier = Modifier.size(52.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                                    Text("$score", style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                        Row(Modifier, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("1 low", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("   ...   ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("5 good", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { finish() }) { Text("Not now") }
                    }
                }
            }
        }
    }

    companion object {
        const val ASKED = "asked"
    }
}
