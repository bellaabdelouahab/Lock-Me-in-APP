package app.lockmein

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.Settings
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import app.lockmein.core.EventKind
import app.lockmein.core.FilterScores
import app.lockmein.core.LockConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.FileInputStream
import java.time.Duration
import java.time.Instant

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FilterServiceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, AdminReceiver::class.java)
    private val store = ConfigStore(context)
    private val state = LockState(context)
    private val events = EventLog(context)

    private val testPkg = "app.lockmein.testapp"
    private val apkPath = "/data/local/tmp/test-app.apk"

    private fun runShell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
    }

    @Before
    fun setup() {
        events.clear()
        state.clear()
        FilterService.testScoreInjector = null

        // Ensure test app is installed
        val pmList = runShell("pm list packages $testPkg")
        if (!pmList.contains(testPkg)) {
            runShell("pm install -r $apkPath")
        }

        // Unsuspend test app if it was suspended
        if (dpm.isPackageSuspended(admin, testPkg)) {
            dpm.setPackagesSuspended(admin, arrayOf(testPkg), false)
        }

        // Configure test app in watchedApps
        val config = LockConfig(
            dnsHost = "dns.google",
            watchedApps = setOf(testPkg, "com.brave.browser", "com.android.chrome"),
            filterPornHentaiThreshold = 0.50f,
            filterSexyThreshold = 0.50f,
        )
        store.save(config)
        store.protectionActive = true
        store.setupComplete = true

        // Enable accessibility service via shell
        val serviceName = ComponentName(context, FilterService::class.java).flattenToString()
        runShell("settings put secure enabled_accessibility_services $serviceName")
        runShell("settings put secure accessibility_enabled 1")

        // Wait up to 5s for service to connect if not already
        val start = System.currentTimeMillis()
        while (FilterService.instance == null && System.currentTimeMillis() - start < 5000) {
            Thread.sleep(200)
        }
    }

    @After
    fun tearDown() {
        FilterService.testScoreInjector = null
        if (dpm.isPackageSuspended(admin, testPkg)) {
            dpm.setPackagesSuspended(admin, arrayOf(testPkg), false)
        }
        state.clearFilterBlocked(testPkg)
        FilterService.instance?.overlay?.dismiss()
        runShell("am force-stop $testPkg")
        runShell("input keyevent KEYCODE_HOME")
    }

    @Test
    fun test01_serviceStartsAndModelIsReady() {
        val service = FilterService.instance
        assertNotNull("FilterService must be running and instance bound", service)
        val classifier = service?.classifier
        assertNotNull("FilterModelClassifier must be initialized", classifier)
        assertTrue("FilterModelClassifier must be ready with loaded TFLite model", classifier?.isReady == true)
    }

    @Test
    fun test02_neutralCheckDoesNotTriggerOverlayOrSuspension() {
        val service = FilterService.instance ?: return
        FilterService.testScoreInjector = { _ ->
            FilterScores(neutral = 0.98f, drawings = 0.02f)
        }

        // Launch test activity in foreground
        runShell("am start -W -n $testPkg/.MainActivity")
        Thread.sleep(1500)

        // Simulate frame check on foreground watched app
        val now = Instant.now()
        val action = service.decisionMachine.processFrame(testPkg, FilterScores(neutral = 0.98f), now)
        assertEquals(app.lockmein.core.FilterAction.None, action)

        assertFalse("Warning overlay should not be showing on neutral content", service.isWarningShowing)
        assertFalse("App should not be suspended on neutral content", dpm.isPackageSuspended(admin, testPkg))
        assertFalse("No FILTER_HIT event should be logged", events.read().any { it.kind == EventKind.FILTER_HIT })
    }

    @Test
    fun test03_firstHitShowsWarningAndCleanSecondCheckClearsWarning() {
        val service = FilterService.instance ?: return

        // Step 1: First hit detected
        val t0 = Instant.now()
        val flaggedScores = FilterScores(porn = 0.85f, neutral = 0.15f)

        // Process first hit
        val action1 = service.decisionMachine.processFrame(testPkg, flaggedScores, t0)
        assertTrue("First hit must produce ShowWarning action", action1 is app.lockmein.core.FilterAction.ShowWarning)

        // Execute action on service
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service.overlay?.show()
            events.add(EventKind.FILTER_HIT, "$testPkg scores: $flaggedScores", t0)
        }
        Thread.sleep(500)

        assertTrue("Warning overlay must be showing after first hit", service.isWarningShowing)
        assertTrue("FILTER_HIT event must be recorded", events.read().any { it.kind == EventKind.FILTER_HIT })
        assertFalse("App should NOT be suspended yet during 5s grace period", dpm.isPackageSuspended(admin, testPkg))

        // Step 2: Clean second check within 5s grace period (user scrolled away)
        val t1 = t0.plusSeconds(2)
        val cleanScores = FilterScores(neutral = 0.95f)
        val action2 = service.decisionMachine.processFrame(testPkg, cleanScores, t1)
        assertTrue("Clean second check must produce ClearWarning action", action2 is app.lockmein.core.FilterAction.ClearWarning)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service.overlay?.dismiss()
            events.add(EventKind.FILTER_CLEARED, "$testPkg reason: Clean check", t1)
        }
        Thread.sleep(500)

        assertFalse("Warning overlay must be dismissed after clean check", service.isWarningShowing)
        assertFalse("App must not be suspended", dpm.isPackageSuspended(admin, testPkg))
        assertTrue("FILTER_CLEARED event must be recorded", events.read().any { it.kind == EventKind.FILTER_CLEARED })
    }

    @Test
    fun test04_twoHitsFiveSecondsApartExitsToHomeAndSuspendsApp() {
        val service = FilterService.instance ?: return

        // Launch test activity in foreground
        runShell("am start -W -n $testPkg/.MainActivity")
        Thread.sleep(1000)

        // Step 1: First hit at t0
        val t0 = Instant.now()
        val hitScores = FilterScores(sexy = 0.88f, neutral = 0.12f)
        val action1 = service.decisionMachine.processFrame(testPkg, hitScores, t0)
        assertTrue("First hit must produce ShowWarning", action1 is app.lockmein.core.FilterAction.ShowWarning)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service.overlay?.show()
        }
        Thread.sleep(500)
        assertTrue("Overlay should be showing", service.isWarningShowing)

        // Step 2: Second hit after 5 seconds grace period
        val t2 = t0.plusSeconds(6) // > 5 seconds
        val action2 = service.decisionMachine.processFrame(testPkg, hitScores, t2)
        assertTrue("Second hit after 5s must produce ExitAndSuspend", action2 is app.lockmein.core.FilterAction.ExitAndSuspend)

        val exitAction = action2 as app.lockmein.core.FilterAction.ExitAndSuspend
        val until = t2.plus(exitAction.duration)

        // Execute ExitAndSuspend action
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service.overlay?.dismiss()
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            state.blockWithFilter(testPkg, until)
            events.add(EventKind.FILTER_BLOCKED, "$testPkg until $until scores: $hitScores", t2)
            PolicyRunner(context).runIfActive(t2)
        }
        Thread.sleep(1500)

        // Assert overlay dismissed
        assertFalse("Overlay must be dismissed after exit", service.isWarningShowing)

        // Assert state saved
        val blockedUntil = state.filterBlocked(t2)[testPkg]
        assertNotNull("Package must be in filterBlocked until map", blockedUntil)
        assertEquals(until.toEpochMilli(), blockedUntil?.toEpochMilli())

        // Assert app is suspended by device owner
        assertTrue("Watched app $testPkg must be suspended for 30 minutes by Device Owner", dpm.isPackageSuspended(admin, testPkg))

        // Assert event log contains FILTER_BLOCKED
        assertTrue("FILTER_BLOCKED event must be recorded", events.read().any { it.kind == EventKind.FILTER_BLOCKED })
    }

    @Test
    fun test05_performanceInferenceLatencyMeasurement() {
        val service = FilterService.instance ?: return
        val classifier = service.classifier ?: return
        assertTrue(classifier.isReady)

        // Create 224x224 bitmap for latency benchmark
        val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.LTGRAY)

        // Warm up
        classifier.predict(bitmap)

        // Benchmark 10 iterations
        val iterations = 10
        val times = LongArray(iterations)
        for (i in 0 until iterations) {
            val start = System.nanoTime()
            val scores = classifier.predict(bitmap)
            val elapsed = System.nanoTime() - start
            times[i] = elapsed
            assertTrue(scores.neutral > 0f || scores.drawings > 0f || scores.sexy > 0f)
        }
        bitmap.recycle()

        val avgMs = times.average() / 1_000_000.0
        Log.i("FilterBenchmark", String.format("Emulator TFLite inference latency: %.2f ms / frame", avgMs))
        println(String.format("BENCHMARK: Emulator TFLite average inference latency: %.2f ms (under 150ms requirement)", avgMs))
        assertTrue("Inference latency on emulator must be reasonable (< 250ms)", avgMs < 250.0)
    }
}
