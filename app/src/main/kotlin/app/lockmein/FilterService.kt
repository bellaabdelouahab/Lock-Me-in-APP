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
    private var isTakingScreenshot = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        classifier = FilterModelClassifier(this)
        overlay = FilterOverlay(this)
        val config = ConfigStore(this).load()
        if (config != null) {
            decisionMachine = FilterDecisionMachine(
                pornHentaiThreshold = config.filterPornHentaiThreshold,
                sexyThreshold = config.filterSexyThreshold,
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // Ignore Lock Me In's own windows

        if (pkg != currentForegroundPackage) {
            currentForegroundPackage = pkg
            onForegroundAppChanged(pkg)
        }
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
        isTakingScreenshot = false
    }

    private fun captureAndProcess(pkg: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        isTakingScreenshot = true

        val displayId = Display.DEFAULT_DISPLAY
        takeScreenshot(displayId, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hwBuffer = result.hardwareBuffer
                val colorSpace = result.colorSpace
                val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                val softwareBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)

                hwBuffer.close()
                hwBitmap?.recycle()
                isTakingScreenshot = false

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
        serviceScope.launch(Dispatchers.Default) {
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

            if (!changed) {
                // Screen is static; skip inference to save battery
                bitmap.recycle()
                return@launch
            }
            lastThumbnail = currentGray

            // 2. Inference: check debug test hook or run model on background thread
            val testScores = testScoreInjector?.invoke(pkg)
            val scores = if (testScores != null) {
                bitmap.recycle()
                testScores
            } else {
                val cls = classifier
                if (cls != null && cls.isReady) {
                    val result = cls.predict(bitmap)
                    bitmap.recycle() // Dropped immediately; never written to disk or logs
                    result
                } else {
                    bitmap.recycle()
                    FilterScores()
                }
            }

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

                val until = now.plus(action.duration)
                val state = LockState(this)
                state.blockWithFilter(pkg, until)
                EventLog(this).add(EventKind.FILTER_BLOCKED, "$pkg until $until scores: $scores", now)

                // Reapply policy to suspend the package via device owner
                PolicyRunner(this).runIfActive(now)
            }
        }
    }

    companion object {
        const val SAMPLE_INTERVAL_MS = 1000L

        @Volatile
        var instance: FilterService? = null

        /** Test hook for emulator tests to inject scores without explicit images. */
        @VisibleForTesting
        var testScoreInjector: ((packageName: String) -> FilterScores?)? = null

        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            val expectedComponent = ComponentName(context, FilterService::class.java).flattenToString()
            val expectedComponentShort = ComponentName(context, FilterService::class.java).flattenToShortString()
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            return enabledServices.contains(expectedComponent) || enabledServices.contains(expectedComponentShort)
        }
    }
}
