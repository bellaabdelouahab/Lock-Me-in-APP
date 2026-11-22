package app.lockmein

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.VisibleForTesting
import app.lockmein.core.EventKind
import app.lockmein.core.FilterAction
import app.lockmein.core.FilterDecisionMachine
import app.lockmein.core.FilterScores
import app.lockmein.core.FrameDiff
import app.lockmein.core.UseKind
import app.lockmein.core.WatchedApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * On-device accessibility service that monitors watched apps.
 * Takes periodic screenshots via takeScreenshot (API 30+), analyzes screen changes,
 * evaluates frames using the on-device TFLite classifier, and enforces the 2-hit exit policy.
 */
class FilterService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var samplingJob: Job? = null

    var classifier: FilterModelClassifier? = null
        private set
    var overlay: FilterOverlay? = null
        private set
    var decisionMachine = FilterDecisionMachine()
        internal set

    val isWarningShowing: Boolean get() = overlay?.isShowing == true

    private var currentForegroundPackage: String? = null
    private var lastThumbnail: ByteArray? = null
    @Volatile private var lastScores: FilterScores? = null
    private var isTakingScreenshot = false
    private var screenshotAt = 0L
    // One check at a time: during a video every frame is new and a check can outlast the 1-second tick. Without this the
    // checks queued up (each holding a full screenshot) until the app ran out of memory and crashed.
    private val checking = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        classifier = FilterModelClassifier(this)
        overlay = FilterOverlay(this)
        // The heartbeat FilterWatcher listens for: a filter that is listed but stops answering closes the watched apps.
        serviceScope.launch {
            while (isActive) {
                if (!heartbeatPausedForTesting) FilterWatcher.beat()
                delay(app.lockmein.core.FilterWatch.BEAT_EVERY_MS)
            }
        }
        FilterWatcher.start(this)
        val config = ConfigStore(this).load()
        if (config != null) {
            decisionMachine = FilterDecisionMachine(
                pornHentaiThreshold = config.filterPornHentaiThreshold,
                sexyThreshold = config.filterSexyThreshold,
                animeThreshold = config.filterAnimeThreshold,
                seductiveThreshold = config.filterSeductiveThreshold.takeIf { config.filterSeductiveLive },
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            // A notification (not a toast) for the phone-use log: which app, when. Its text is never read.
            if (event.parcelableData is android.app.Notification && pkg != packageName) PhoneUseLog(this).add(UseKind.NOTIFICATION, pkg)
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (pkg == packageName) return // Ignore Lock Me In's own windows

        // A notification banner, the pulled-down shade or the keyboard also send this event. Only the app window that has focus
        // counts, otherwise the filter thought you had left the browser and stayed off when the banner went away.
        val app = foregroundApp() ?: return
        catchNewApp(app)

        if (app != currentForegroundPackage) {
            currentForegroundPackage = app
            PhoneUseLog(this).add(UseKind.APP, app)
            onForegroundAppChanged(app)
        }
    }

    /** The package of the focused (else topmost) app window; null when no app window is on screen. */
    private fun foregroundApp(): String? {
        val apps = runCatching { windows }.getOrNull().orEmpty().filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
        val top = apps.firstOrNull { it.isFocused } ?: apps.firstOrNull { it.isActive } ?: apps.firstOrNull() ?: return null
        return top.root?.packageName?.toString()
    }

    override fun onInterrupt() {
        stopSampling()
        overlay?.dismiss()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopSampling()
        overlay?.dismiss()
        overlay = null
        classifier?.close()
        classifier = null
        serviceScope.cancel()
    }

    /**
     * A new app opened before the install receiver closed it (it can be quicker than the broadcast): send the user home and
     * run a pass, which holds it back like any new app.
     */
    private fun catchNewApp(pkg: String) {
        val store = ConfigStore(this)
        if (!store.protectionActive) return
        val state = LockState(this)
        val known = state.knownPackages ?: return
        if (pkg in known && pkg !in state.quarantined) return
        if (runCatching { packageManager.getApplicationInfo(pkg, 0) }.isFailure) return // a window from no installed app
        if (pkg in DeviceInventory(this).read().protectedPackages) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        serviceScope.launch(Dispatchers.Default) { PolicyRunner(this@FilterService).runIfActive() }
    }

    /** Visible text of the active window (titles, captions, tags, the address bar), capped so a long feed stays cheap. */
    private fun screenText(): String {
        val root = rootInActiveWindow ?: return ""
        val out = StringBuilder()
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (queue.isNotEmpty() && seen < MAX_TEXT_NODES && out.length < MAX_TEXT_CHARS) {
            val node = queue.removeFirst()
            seen++
            if (node.isVisibleToUser) {
                node.text?.let { out.append(it).append('\n') }
                node.contentDescription?.let { out.append(it).append('\n') }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return out.toString()
    }

    @VisibleForTesting
    fun onForegroundAppChangedForTesting(pkg: String) {
        currentForegroundPackage = pkg
        onForegroundAppChanged(pkg)
    }

    private fun onForegroundAppChanged(pkg: String) {
        val config = ConfigStore(this).load()
        val isWatched = config != null && WatchedApps.isWatched(pkg, config.watchedApps)

        if (isWatched) {
            startSampling(pkg)
        } else {
            stopSampling()
            val resetAction = decisionMachine.reset()
            if (resetAction is FilterAction.ClearWarning) {
                overlay?.dismiss()
            }
        }
    }

    private fun startSampling(pkg: String) {
        if (samplingJob?.isActive == true) return

        samplingJob = serviceScope.launch {
            while (isActive && currentForegroundPackage == pkg) {
                // A screenshot whose answer never came must not stop the filter: after 3 s it is given up on.
                if (isTakingScreenshot && System.currentTimeMillis() - screenshotAt > 3_000) isTakingScreenshot = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !isTakingScreenshot) {
                    captureAndProcess(pkg)
                }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun stopSampling() {
        samplingJob?.cancel()
        samplingJob = null
        lastThumbnail = null
        lastScores = null
        isTakingScreenshot = false
    }

    private fun captureAndProcess(pkg: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        isTakingScreenshot = true
        screenshotAt = System.currentTimeMillis()

        val displayId = Display.DEFAULT_DISPLAY
        takeScreenshot(displayId, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hwBuffer = result.hardwareBuffer
                val softwareBitmap = try {
                    val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, result.colorSpace)
                    hwBitmap?.copy(Bitmap.Config.ARGB_8888, false).also { hwBitmap?.recycle() }
                } catch (e: Exception) {
                    null
                } finally {
                    hwBuffer.close()
                    isTakingScreenshot = false
                }

                if (softwareBitmap != null) {
                    processScreenshot(pkg, softwareBitmap)
                }
            }

            override fun onFailure(errorCode: Int) {
                isTakingScreenshot = false
                // Handle ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT by waiting for next cycle
            }
        })
    }

    private fun processScreenshot(pkg: String, bitmap: Bitmap) {
        if (!checking.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }
        serviceScope.launch(Dispatchers.Default) {
            try {
                check(pkg, bitmap)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                runCatching { bitmap.recycle() }
                EventLog(this@FilterService).add(EventKind.PASS_FAILED, "screen check failed: $e")
            } finally {
                checking.set(false)
            }
        }
    }

    private suspend fun check(pkg: String, bitmap: Bitmap) {
        run {
            val now = TrustedClock(this@FilterService).now()
            val state = LockState(this@FilterService)
            state.lastFilterCheckMillis = now.toEpochMilli()

            // 1. Frame-skip logic: Downscale to 16x16 thumbnail to detect static screens
            val thumb = Bitmap.createScaledBitmap(bitmap, FrameDiff.THUMB_WIDTH, FrameDiff.THUMB_HEIGHT, true)
            val thumbPixels = IntArray(FrameDiff.TOTAL_PIXELS)
            thumb.getPixels(thumbPixels, 0, FrameDiff.THUMB_WIDTH, 0, 0, FrameDiff.THUMB_WIDTH, FrameDiff.THUMB_HEIGHT)
            thumb.recycle()

            val currentGray = FrameDiff.rgbToGrayscale(thumbPixels)
            val changed = FrameDiff.hasChanged(lastThumbnail, currentGray)

            // A still screen is not read again (battery): it counts as the same frame once more, so a still picture is judged
            // like a video, by frames that agree. Never during a warning: the second check reads the screen afresh.
            val still = lastScores
            if (!changed && still != null && decisionMachine.state is app.lockmein.core.FilterState.Idle) {
                bitmap.recycle()
                val action = decisionMachine.processFrame(pkg, still, now)
                withContext(Dispatchers.Main) { executeAction(pkg, action, still, now) }
                return
            }
            lastThumbnail = currentGray

            // 2. Inference: check debug test hook or run model on background thread
            val testScores = testScoreInjector?.invoke(pkg)
            val text = withContext(Dispatchers.Main) { runCatching { screenText() }.getOrDefault("") }
            val textMatch = app.lockmein.core.ScreenText.match(text)
            val imageScores = if (testScores != null) {
                bitmap.recycle()
                testScores
            } else {
                val cls = classifier
                if (cls != null && cls.isReady) {
                    val m = decisionMachine
                    // The tile that comes closest to a threshold speaks for the screen.
                    val result = cls.predict(bitmap) { m.rule.level(it) }
                    bitmap.recycle() // Dropped immediately; never written to disk or logs
                    result
                } else {
                    bitmap.recycle()
                    FilterScores()
                }
            }

            val scores = imageScores.copy(textMatch = textMatch)
            lastScores = scores

            // 3. Process with Decision State Machine
            val action = decisionMachine.processFrame(pkg, scores, now)

            // 4. Carry out action on Main thread
            withContext(Dispatchers.Main) {
                executeAction(pkg, action, scores, now)
            }
        }
    }

    private fun executeAction(
        pkg: String,
        action: FilterAction,
        scores: FilterScores,
        now: Instant,
    ) {
        when (action) {
            is FilterAction.None -> Unit

            is FilterAction.ShowWarning -> {
                overlay?.show()
                val state = LockState(this)
                state.lastFilterHitMillis = now.toEpochMilli()
                EventLog(this).add(EventKind.FILTER_HIT, "$pkg scores: $scores", now)
            }

            is FilterAction.ClearWarning -> {
                overlay?.dismiss()
                EventLog(this).add(EventKind.FILTER_CLEARED, "$pkg reason: ${action.reason}", now)
            }

            is FilterAction.ExitAndSuspend -> {
                overlay?.dismiss()
                performGlobalAction(GLOBAL_ACTION_HOME)

                val state = LockState(this)
                val until = now.plus(action.duration)
                state.blockWithFilter(pkg, until)
                EventLog(this).add(EventKind.FILTER_BLOCKED, "$pkg until $until scores: $scores", now)

                // Reapply policy to suspend the package via device owner
                PolicyRunner(this).runIfActive(now)
            }
        }
    }

    companion object {
        /** A frame every half second (spec v3.1 F2); a phone that needs longer for a check skips frames instead of queueing them. */
        const val SAMPLE_INTERVAL_MS = 500L
        private const val MAX_TEXT_NODES = 400
        private val SUPPORT_MESSAGE_START = SUPPORT_MESSAGE.take(24)
        private const val MAX_TEXT_CHARS = 8_000

        @Volatile
        var instance: FilterService? = null

        /** Emulator tests: a filter that is switched on but has stopped answering. */
        @VisibleForTesting
        @Volatile
        var heartbeatPausedForTesting = false

        /** Test hook for emulator tests to inject scores without explicit images. */
        @VisibleForTesting
        var testScoreInjector: ((packageName: String) -> FilterScores?)? = null

        /** Switched on in Accessibility (for the setup screens: shows the moment it is turned on). */
        fun isEnabled(context: Context): Boolean = instance != null || isListed(context)

        /** Accessibility on and this service in Android's list of enabled services: what Android itself says, nothing cached. */
        fun isListed(context: Context): Boolean = app.lockmein.core.FilterWatch.isListed(
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1,
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            context.packageName,
            FilterService::class.java.name,
        )

        /**
         * What the rules go by: switched on AND answering (FilterWatcher checks its heartbeat). When it is not, the watched apps
         * are closed.
         */
        fun isWorking(context: Context): Boolean = isListed(context) && !LockState(context).filterDown
    }
}
