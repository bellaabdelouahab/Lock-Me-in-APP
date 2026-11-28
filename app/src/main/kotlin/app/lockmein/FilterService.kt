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
import app.lockmein.core.AgreedFrame
import app.lockmein.core.AppSites
import app.lockmein.core.KnownBrowsers
import app.lockmein.core.BlockRecord
import app.lockmein.core.EventKind
import app.lockmein.core.StoredScores
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
    private var blockCard: BlockCard? = null
    var decisionMachine = FilterDecisionMachine()
        internal set

    val isWarningShowing: Boolean get() = overlay?.isShowing == true

    private var currentForegroundPackage: String? = null
    private var lastThumbnail: ByteArray? = null
    @Volatile private var lastScores: FilterScores? = null
    /** The last frame that was a hit, as JPEG, kept only in memory for "Save for review" (spec F3), with its time. */
    @Volatile private var lastHitJpeg: Pair<Long, ByteArray>? = null
    /** The label of the last thing tapped (an app icon), kept in memory for 3 s to name the app behind a "blocked" dialog. */
    private var lastClick: Pair<String, Long>? = null
    private var lastTryAt = 0L
    private var lastSettingsPage: String? = null
    /** The record saved with "Wrong?" during the current warning, if any: the close that may follow reuses it. */
    private var warningSaved: BlockRecord? = null
    /** Private tabs (FEATURES 3.16): last window check, last count, and since when the screen is black (Android 11 to 13). */
    private var lastProbeAt = 0L
    private var lastHiddenAt = 0L
    private var blackSince: Long? = null
    /** Sites of closed apps (FEATURES 3.18): the address-bar watch, what is closed (refreshed every 30 s), and recent hits. */
    private var siteJob: Job? = null
    @Volatile private var siteRules: Pair<Set<String>, Set<String>>? = null // closed now, installed
    private var siteRulesAt = 0L
    private var siteRulesLoading = false
    private val siteHits = ArrayDeque<Long>()
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
        blockCard = BlockCard(this)
        // The heartbeat FilterWatcher listens for: a filter that is listed but stops answering closes the watched apps.
        serviceScope.launch {
            while (isActive) {
                if (!heartbeatPausedForTesting) FilterWatcher.beat()
                delay(app.lockmein.core.FilterWatch.BEAT_EVERY_MS)
            }
        }
        FilterWatcher.start(this)
        reloadConfig()
        serviceScope.launch(Dispatchers.Default) { runCatching { siteBrowsers = DeviceInventory(this@FilterService).read().browsers } }
    }

    /** Reads the thresholds, per-app settings and judge again (after a change). */
    fun reloadConfig() {
        val config = ConfigStore(this).load()
        if (config != null) {
            decisionMachine = FilterDecisionMachine(
                pornHentaiThreshold = config.filterPornHentaiThreshold,
                sexyThreshold = config.filterSexyThreshold,
                animeThreshold = config.filterAnimeThreshold,
                seductiveThreshold = config.filterSeductiveThreshold.takeIf { config.filterSeductiveLive },
                appOffsets = config.filterAppOffsets,
                judge = config.filterJudge,
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            // A notification (not a toast) for the phone-use log: which app, when. Its text is never read.
            // With notification access on, NotifyListener counts them (it can leave out ongoing ones); this is the fallback.
            if (event.parcelableData is android.app.Notification && pkg != packageName && !NotifyListener.connected) PhoneUseLog(this).add(UseKind.NOTIFICATION, pkg)
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // Only the words of what was tapped (an app icon's name), only in memory, only to name the app behind a "blocked" dialog.
            lastClick = (event.text.joinToString(" ") + " " + (event.contentDescription ?: "")).trim() to System.currentTimeMillis()
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (pkg == packageName) return // Ignore Lock Me In's own windows

        // Android's dialog for a closed app: for a device owner, Settings' "blocked by admin" dialog with our support message; for
        // other suspensions SuspendedAppActivity. Counted as a try to open a closed app (Phone use page, weekly report).
        val cls = event.className?.toString().orEmpty()
        val dialogText = event.text.joinToString(" ")
        if (dialogText.contains(SUPPORT_MESSAGE_START) || cls.contains("SuspendedAppActivity") || cls.contains("ActionDisabledByAdmin") || cls.contains("AdminSupportDetails")) {
            countTry(dialogText)
            return
        }

        // A notification banner, the pulled-down shade or the keyboard also send this event. Only the app window that has focus
        // counts, otherwise the filter thought you had left the browser and stayed off when the banner went away.
        val app = foregroundApp() ?: return
        catchNewApp(app)
        // The Settings page on screen, so a "blocked by your admin" dialog over it can say which page (spec F4).
        if (app == SETTINGS && pkg == SETTINGS) lastSettingsPage = event.text.firstOrNull()?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: cls.substringAfterLast('.').takeIf { it.isNotEmpty() }

        if (app != currentForegroundPackage) {
            currentForegroundPackage = app
            PhoneUseLog(this).add(UseKind.APP, app)
            onForegroundAppChanged(app)
        }
    }

    /** Records a try to open a closed app, named by the dialog's text or the icon tapped just before, when it can be. */
    private fun countTry(dialogText: String) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastTryAt < 1_500) return // one dialog sends several events
        lastTryAt = nowMs
        val shown = dialogText + " " + (lastClick?.takeIf { nowMs - it.second < 3_000 }?.first ?: "")
        // The app the dialog came over (the dialog itself never becomes the foreground app), and Settings' page when it was Settings.
        val behind = currentForegroundPackage.orEmpty()
        val detail = if (behind == SETTINGS) "$behind > ${lastSettingsPage.orEmpty()}".removeSuffix(" > ") else behind
        val config = ConfigStore(this).load()
        serviceScope.launch(Dispatchers.Default) {
            val pkg = runCatching {
                val inventory = DeviceInventory(this@FilterService)
                val closed = if (config == null) emptyList() else ClosedReasons.all(this@FilterService, config).filter { it.closedNow }.map { it.packageName }
                closed.filter { inventory.label(it).let { l -> l.isNotBlank() && shown.contains(l, ignoreCase = true) } }.maxByOrNull { inventory.label(it).length }
            }.getOrNull().orEmpty()
            PhoneUseLog(this@FilterService).add(UseKind.BLOCKED, pkg, detail = if (pkg.isEmpty()) detail else "")
            // When it still cannot be named, the dialog's own words and what it came over go in the log.
            EventLog(this@FilterService).add(EventKind.BLOCKED_TRY, pkg.ifEmpty { "an app (not named; over ${detail.ifEmpty { "nothing known" }}; the dialog said: \"${dialogText.trim().take(80)}\")" })
            MoodAsk.afterTry(this@FilterService)
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
        stopSiteWatch()
        overlay?.dismiss()
        overlay = null
        blockCard?.dismiss()
        blockCard = null
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
        if (pkg in KnownBrowsers.ALL || pkg in (siteBrowsers ?: emptySet())) startSiteWatch(pkg) else stopSiteWatch()
        val config = ConfigStore(this).load()
        val isWatched = config != null && WatchedApps.isWatched(pkg, config.watchedNow())

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

    /** Every browser on the phone, read once in the background (a browser not in [KnownBrowsers] is still a browser). */
    @Volatile private var siteBrowsers: Set<String>? = null

    /** While a browser is in front, reads its address bar every second and leaves a closed app's site (FEATURES 3.18). */
    private fun startSiteWatch(pkg: String) {
        if (siteJob?.isActive == true) return
        siteJob = serviceScope.launch {
            while (isActive && currentForegroundPackage == pkg) {
                runCatching { checkSite(pkg) }
                delay(AppSites.CHECK_EVERY_MS)
            }
        }
    }

    private fun stopSiteWatch() {
        siteJob?.cancel()
        siteJob = null
    }

    private fun checkSite(pkg: String) {
        if (!ConfigStore(this).protectionActive) return
        val nowMs = System.currentTimeMillis()
        val rules = siteRules
        if ((rules == null || nowMs - siteRulesAt > SITE_RULES_EVERY_MS) && !siteRulesLoading) {
            siteRulesLoading = true
            serviceScope.launch(Dispatchers.Default) {
                try {
                    val config = ConfigStore(this@FilterService).load()
                    val snapshot = DeviceInventory(this@FilterService).read()
                    siteBrowsers = snapshot.browsers
                    siteRules = if (config == null) null else {
                        PolicyRunner(this@FilterService).planFor(config, asIfInstalled = AppSites.SITES.keys).suspended to snapshot.installed
                    }
                    siteRulesAt = System.currentTimeMillis()
                } catch (_: Exception) {
                } finally {
                    siteRulesLoading = false
                }
            }
        }
        val (closed, installed) = rules ?: return
        val bar = addressBar(pkg) ?: return
        val site = AppSites.closedSite(bar, closed, installed) ?: return
        while (siteHits.isNotEmpty() && nowMs - siteHits.first() > AppSites.STRIKE_WINDOW_MS) siteHits.removeFirst()
        siteHits.addLast(nowMs)
        if (siteHits.size == 1) {
            EventLog(this).add(EventKind.SITE_CLOSED, "${site.host} (${site.app}) in $pkg")
            PhoneUseLog(this).add(UseKind.BLOCKED, site.app)
            val label = DeviceInventory(this).label(site.app).takeIf { it != site.app }
            val note = if (label != null) "${site.host} is closed while $label is." else "${site.host} is closed by your rules."
            android.widget.Toast.makeText(this, note, android.widget.Toast.LENGTH_LONG).show()
        }
        performGlobalAction(if (AppSites.sendHome(siteHits.size)) GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK)
    }

    /**
     * The address bar's text in [pkg]'s window, or null while it is being edited (what is typed is not a page yet) or not on
     * screen. Known bar ids first; else any visible field whose id names a url, an omnibar, an address or a location bar.
     */
    private fun addressBar(pkg: String): String? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != pkg) return null
        val bar = URL_BAR_IDS.firstNotNullOfOrNull { id -> root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull { it.isVisibleToUser } }
            ?: findBar(root)
            ?: return null
        if (bar.isFocused) return null
        return bar.text?.toString()
    }

    private fun findBar(root: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (queue.isNotEmpty() && seen < MAX_BAR_NODES) {
            val node = queue.removeFirst()
            seen++
            val id = node.viewIdResourceName?.substringAfter(":id/")?.lowercase()
            if (id != null && node.isVisibleToUser && BAR_ID_WORDS.any { id.contains(it) } && node.text?.let { AppSites.host(it.toString()) } != null) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return null
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
        blackSince = null
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
            if (checkHidden(pkg, currentGray)) {
                bitmap.recycle()
                return
            }
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
                    // A hit frame is kept as JPEG in memory only, for "Save for review" after a block; written (encrypted) only on
                    // that second tap. Any other frame is dropped at once, never written to disk or logs.
                    if (m.ruleFor(pkg).isHit(result)) {
                        lastHitJpeg = runCatching {
                            java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                        }.getOrNull()?.let { System.currentTimeMillis() to it }
                    }
                    bitmap.recycle()
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

    /**
     * A screen the filter cannot see (FEATURES 3.16): a dark frame in a watched app makes Android (14+) say whether the app's
     * window is secure, as private tabs are; on Android 11 to 13 a watched browser black for 6 s counts. Such a screen is
     * sent home. True when it was.
     */
    private suspend fun checkHidden(pkg: String, gray: ByteArray): Boolean {
        val nowMs = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (!app.lockmein.core.HiddenScreen.mayBeHidden(gray) || nowMs - lastProbeAt < app.lockmein.core.HiddenScreen.PROBE_EVERY_MS) return false
            lastProbeAt = nowMs
            if (!withContext(Dispatchers.Main) { isSecureWindow(pkg) }) return false
        } else {
            blackSince = if (app.lockmein.core.HiddenScreen.isBlack(gray)) blackSince ?: nowMs else null
            if (!app.lockmein.core.HiddenScreen.blackTooLong(blackSince, nowMs, isBrowser = true)) return false
            val isBrowser = pkg in app.lockmein.core.KnownBrowsers.ALL ||
                runCatching { pkg in DeviceInventory(this).read().browsers }.getOrDefault(false)
            if (!isBrowser) return false
            blackSince = null
        }
        withContext(Dispatchers.Main) { sendHiddenHome(pkg) }
        return true
    }

    /** Android 14+: asks for a picture of [pkg]'s focused window alone; "secure window" means the filter is shown black. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun isSecureWindow(pkg: String): Boolean {
        val window = runCatching { windows }.getOrNull().orEmpty()
            .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName?.toString() == pkg }
            .let { w -> w.firstOrNull { it.isFocused } ?: w.firstOrNull { it.isActive } ?: w.firstOrNull() } ?: return false
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            takeScreenshotOfWindow(window.id, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    result.hardwareBuffer.close()
                    if (cont.isActive) cont.resumeWith(Result.success(false))
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resumeWith(Result.success(errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW))
                }
            })
        }
    }

    private fun sendHiddenHome(pkg: String) {
        overlay?.dismiss()
        decisionMachine.reset()
        performGlobalAction(GLOBAL_ACTION_HOME)
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastHiddenAt < app.lockmein.core.HiddenScreen.COUNT_EVERY_MS) return
        lastHiddenAt = nowMs
        EventLog(this).add(EventKind.HIDDEN_SCREEN, pkg)
        android.widget.Toast.makeText(this, "Private tabs close: the screen filter cannot see them.", android.widget.Toast.LENGTH_LONG).show()
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
                // A warning already up keeps its own record (and its "Saved"); only a new warning starts a new one.
                if (overlay?.isShowing != true) {
                    warningSaved = null
                    val record = blockRecord(pkg, scores, now)
                    val jpeg = lastHitJpeg?.takeIf { System.currentTimeMillis() - it.first < FeedbackStore.PENDING_MS }?.second
                    overlay?.show(onWrong = { saveWrong(record, jpeg) })
                }
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
                EventLog(this).add(EventKind.FILTER_BLOCKED, "$pkg until $until (${action.reason}) scores: $scores", now)
                recordBlock(pkg, scores, now)

                // Reapply policy to suspend the package via device owner. Off the main thread: a pass can wait on Android's DNS test.
                serviceScope.launch(Dispatchers.Default) { runCatching { PolicyRunner(this@FilterService).runIfActive(now) } }
            }
        }
    }

    private fun blockRecord(pkg: String, scores: FilterScores, now: Instant): BlockRecord {
        val m = decisionMachine
        return BlockRecord(
            id = java.util.UUID.randomUUID().toString(),
            atMillis = now.toEpochMilli(),
            pkg = pkg,
            scores = StoredScores.of(scores),
            agreed = m.agreedFrames.map { AgreedFrame(java.time.Duration.between(it.first, now).toMillis(), it.second) },
            rule = m.ruleFor(pkg).toString(),
            appOffset = m.appOffsets[pkg] ?: 0f,
            modelVersion = MODEL_VERSION,
            judgeVersion = m.judge?.version,
        )
    }

    /**
     * "Wrong?" on the warning (spec v3.1 F3): saves the warned screen, encrypted, labelled as claimed false, for the 19:30 review.
     * One tap, no second step; the warning, its countdown and any close carry on exactly as without it. Answers the button's word.
     */
    private fun saveWrong(record: BlockRecord, jpeg: ByteArray?): String {
        val store = FeedbackStore(this)
        val saved = runCatching { store.add(record); store.saveForReview(record.id, jpeg) }.getOrDefault(false)
        if (!saved) return "Not saved"
        warningSaved = record
        EventLog(this).add(EventKind.FILTER_SAVED, "${record.pkg} (${store.savesToday()} today)")
        return "Saved"
    }

    /** Keeps the block's record (scores, frames, thresholds) and its screenshot for training, and shows the close card. */
    private fun recordBlock(pkg: String, scores: FilterScores, now: Instant) {
        val saved = warningSaved
        warningSaved = null
        val jpeg = lastHitJpeg?.takeIf { System.currentTimeMillis() - it.first < FeedbackStore.PENDING_MS }?.second
        lastHitJpeg = null
        if (saved == null) {
            val record = blockRecord(pkg, scores, now)
            val store = FeedbackStore(this)
            // A close he lets stand is a real hit for training (FEATURES 3.20): its screenshot is kept, encrypted, until sent.
            serviceScope.launch(Dispatchers.IO) { runCatching { store.add(record); if (jpeg != null) store.keepImage(record.id, jpeg) } }
        }
        val label = runCatching { DeviceInventory(this).label(pkg) }.getOrDefault(pkg).ifBlank { pkg }
        blockCard?.show(label, if (saved != null) "Saved as a false alarm for training." else "The screen filter saw sexual or revealing content.")
    }

    companion object {
        /** The three models shipped in this build, recorded with each block. */
        val MODEL_VERSION = "nsfw-mnv2 + anime_rating + seductive-clip, app ${BuildConfig.VERSION_NAME}"

        /** A frame every half second (spec v3.1 F2); a phone that needs longer for a check skips frames instead of queueing them. */
        const val SAMPLE_INTERVAL_MS = 500L
        private const val MAX_TEXT_NODES = 400
        private val SUPPORT_MESSAGE_START = SUPPORT_MESSAGE.take(24)
        private const val SETTINGS = "com.android.settings"
        private const val MAX_TEXT_CHARS = 8_000
        private const val SITE_RULES_EVERY_MS = 30_000L
        private const val MAX_BAR_NODES = 150

        /** Address-bar ids: Chrome, Brave, Edge, Vivaldi, Kiwi (url_bar); Firefox; Samsung Internet; Opera; DuckDuckGo; Mi Browser. */
        private val URL_BAR_IDS = listOf(
            "url_bar", "mozac_browser_toolbar_url_view", "location_bar_edit_text", "url_field", "omnibarTextInput", "url", "url_text",
        )
        private val BAR_ID_WORDS = listOf("url", "omnibar", "address", "location_bar")

        @Volatile
        var instance: FilterService? = null

        /** Emulator tests: a filter that is switched on but has stopped answering. */
        @VisibleForTesting
        @Volatile
        var heartbeatPausedForTesting = false

        /** The app in front now, as the filter last saw it (null when the filter is not running). */
        val foregroundNow: String? get() = instance?.currentForegroundPackage

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
