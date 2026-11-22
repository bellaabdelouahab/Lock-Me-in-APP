package app.lockmein

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import app.lockmein.core.EventKind
import app.lockmein.core.FilterWatch
import java.util.concurrent.Executors

/**
 * Keeps the screen filter honest. Runs on its own, apart from the filter (an accessibility service Android can switch off),
 * and closes the watched apps within seconds when the filter is switched off in Accessibility (Android tells this service at
 * once) or stops answering (no heartbeat for 15 seconds). It opens them again only when the filter is back on and has answered
 * twice. Started on boot, after an update, by every policy pass and when the app starts, while protection is on.
 *
 * Only ever closes or reopens the watched apps, the way the plan says; the 15-minute watchdog pass still repairs everything else.
 */
class FilterWatcher : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var watch: FilterWatch
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = evaluate()
    }
    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            // On the uptime clock, which pauses while the phone sleeps: no wake-ups, and waking does not look like silence.
            main.postDelayed(this, FilterWatch.BEAT_EVERY_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        watch = FilterWatch(startedAt = SystemClock.uptimeMillis(), wasDown = LockState(this).filterDown)
        current = watch
        for (key in listOf(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, Settings.Secure.ACCESSIBILITY_ENABLED)) {
            contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer)
        }
        main.post(tick)
        // The phone-use log: screen on and off, unlocks (only a running receiver can hear these, not one in the manifest).
        registerReceiver(screen, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }

    private val screen = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val kind = when (intent.action) {
                Intent.ACTION_SCREEN_ON -> app.lockmein.core.UseKind.SCREEN_ON
                Intent.ACTION_SCREEN_OFF -> app.lockmein.core.UseKind.SCREEN_OFF
                Intent.ACTION_USER_PRESENT -> app.lockmein.core.UseKind.UNLOCK
                else -> return
            }
            PhoneUseLog(context).add(kind)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        contentResolver.unregisterContentObserver(observer)
        runCatching { unregisterReceiver(screen) }
        if (current === watch) current = null
        super.onDestroy()
    }

    /** On the main thread. */
    private fun evaluate() {
        if (!shouldRun(this)) {
            stopSelf()
            return
        }
        val state = LockState(this)
        val working = watch.check(SystemClock.uptimeMillis(), FilterService.isListed(this))
        if (working == !state.filterDown) return
        state.filterDown = !working
        val why = if (FilterService.isListed(this)) "not answering" else "switched off in Accessibility"
        worker.execute { if (working) reopen() else close(why) }
    }

    /** Closes the watched apps the plan closes while the filter is off. A block always completes: no network, no waiting. */
    private fun close(why: String) {
        try {
            val config = ConfigStore(this).load() ?: return
            val now = TrustedClock(this).now()
            val watched = PolicyRunner(this).planFor(config, now, filterServiceEnabled = false).suspended.intersect(config.watchedApps)
            val refused = dpm().setPackagesSuspended(admin(), watched.toTypedArray(), true).toList()
            EventLog(this).add(
                EventKind.FILTER_BLOCKED,
                "screen filter $why: ${watched.size} watched apps closed${if (refused.isNotEmpty()) ", refused: ${refused.joinToString()}" else ""}",
                now,
            )
        } catch (e: Exception) {
            Log.w(TAG, "closing the watched apps failed", e)
            EventLog(this).add(EventKind.PASS_FAILED, "screen filter off, closing the watched apps failed: $e")
        }
    }

    /** Reopens only the watched apps nothing else keeps closed (the plan, with the filter working, decides). */
    private fun reopen() {
        try {
            val config = ConfigStore(this).load() ?: return
            val now = TrustedClock(this).now()
            val closed = PolicyRunner(this).planFor(config, now, filterServiceEnabled = true).suspended
            val open = config.watchedApps - closed
            dpm().setPackagesSuspended(admin(), open.toTypedArray(), false)
            EventLog(this).add(EventKind.FILTER_CLEARED, "screen filter back on and answering: watched apps open again", now)
        } catch (e: Exception) {
            Log.w(TAG, "reopening the watched apps failed", e)
        }
    }

    private fun dpm() = getSystemService(DevicePolicyManager::class.java)
    private fun admin() = ComponentName(this, AdminReceiver::class.java)

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen filter watch", NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Lock Me In is watching the screen filter")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "LockMeIn"
        private const val CHANNEL = "filter_watch"
        private const val NOTIFICATION_ID = 7
        private val worker = Executors.newSingleThreadExecutor()

        @Volatile
        private var current: FilterWatch? = null

        /** For the emulator tests: whether the running watcher counts the filter as off (null when it is not running). */
        @androidx.annotation.VisibleForTesting
        val filterDownNow: Boolean? get() = current?.down

        /** The filter service is alive (called every 5 seconds by it). */
        fun beat() {
            val at = SystemClock.uptimeMillis()
            Handler(Looper.getMainLooper()).post { current?.beat(at) }
        }

        private fun shouldRun(context: Context): Boolean =
            !BuildConfig.PREVIEW && ConfigStore(context).protectionActive &&
                context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)

        /** Starts the watcher if protection is on and it is not running yet (a second start is a no-op). */
        fun start(context: Context) {
            if (!shouldRun(context)) return
            runCatching { context.startForegroundService(Intent(context, FilterWatcher::class.java)) }
                .onFailure { Log.w(TAG, "could not start the filter watcher", it) }
        }
    }
}
