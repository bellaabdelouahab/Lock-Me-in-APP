package app.lockmein

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.lockmein.core.BankPause
import app.lockmein.core.EventKind
import app.lockmein.core.LockConfig
import java.io.FileInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Spec D1, end to end on the emulator: a "bank" app (the test app, put on the pause list) comes to the front, the filter goes
 * off and the watcher counts it off (the watched apps close), and the filter is switched back on by the app itself when the
 * bank app leaves, at the cap, or by hand without the adb grant.
 */
class BankPauseTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ConfigStore(context)
    private val state = LockState(context)
    private val events = EventLog(context)
    private val bank = "app.lockmein.testapp"
    private val me = ComponentName(context, FilterService::class.java).flattenToString()

    private fun shell(cmd: String): String {
        val ui = runCatching {
            InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        }.getOrElse { InstrumentationRegistry.getInstrumentation().uiAutomation }
        return FileInputStream(ui.executeShellCommand(cmd).fileDescriptor).bufferedReader().use { it.readText() }
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, check: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!check()) {
            if (System.currentTimeMillis() - start > timeoutMs) throw AssertionError("timed out waiting for: $what")
            Thread.sleep(250)
        }
    }

    private fun filterOn() {
        shell("settings put secure enabled_accessibility_services $me")
        shell("settings put secure accessibility_enabled 1")
    }

    @Before
    fun setup() {
        FakeFilterBeat.start()
        if (!shell("pm list packages $bank").contains(bank)) shell("pm install -r /data/local/tmp/test-app.apk")
        events.clear()
        state.bankPause = null
        state.bankCapped = null
        state.downForBank = false
        store.save(LockConfig(dnsHost = "dns.google", watchedApps = setOf("com.android.chrome"), filterPauseApps = setOf(bank)))
        store.protectionActive = true
        store.setupComplete = true
        shell("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
        shell("appops set ${context.packageName} GET_USAGE_STATS allow")
        filterOn()
        FilterWatcher.start(context)
        waitFor("the watcher running and counting the filter on") { FilterWatcher.filterDownNow == false }
    }

    @After
    fun tearDown() {
        BankPauses.capMsForTesting = null
        state.bankPause = null
        state.bankCapped = null
        shell("am force-stop $bank")
        shell("input keyevent KEYCODE_HOME")
        filterOn()
        FakeFilterBeat.awaitWorking()
        FakeFilterBeat.stop()
        PolicyRunner(context).cancelAlarm()
    }

    /** The bank app comes to the front; the filter stops itself (or, when Android holds the service back, the shell stands in). */
    private fun openBank() {
        shell("monkey -p $bank -c android.intent.category.LAUNCHER 1")
        Thread.sleep(1_500)
        val service = FilterService.instance
        if (service != null) {
            service.onForegroundAppChangedForTesting(bank)
        } else {
            assertTrue(BankPauses.shouldPause(context, bank))
            shell("settings put secure enabled_accessibility_services \"\"")
        }
        waitFor("the pause recorded and the filter off") { state.bankPause?.pkg == bank && !FilterService.isListed(context) }
        waitFor("the watcher counting the filter off (watched apps closed)") { FilterWatcher.filterDownNow == true }
        waitFor("the pause logged") { events.read().any { it.kind == EventKind.BANK_PAUSE_STARTED } }
        assertFalse("a bank pause is not a filter switched off", events.read().any { it.kind == EventKind.FILTER_SERVICE_OFF })
        if (shell("pm list packages com.android.chrome").contains("com.android.chrome")) {
            assertTrue("watched apps stay closed during the pause", context.getSystemService(DevicePolicyManager::class.java).isPackageSuspended(ComponentName(context, AdminReceiver::class.java), "com.android.chrome"))
        }
    }

    private fun ended(): String = events.read().last { it.kind == EventKind.BANK_PAUSE_ENDED }.detail

    @Test
    fun leavingTheBankAppSwitchesTheFilterBackOn() {
        openBank()
        assertTrue("with the adb grant the pause is automatic", state.bankPause!!.auto)
        shell("input keyevent KEYCODE_HOME")
        waitFor("the filter back on") { FilterService.isListed(context) && state.bankPause == null }
        assertTrue(ended(), ended().endsWith(BankPause.LEFT))
        waitFor("the watcher counting it on again") { FilterWatcher.filterDownNow == false }
    }

    @Test
    fun staying_inTheBankAppEndsAtTheCapAndDoesNotPauseAgain() {
        BankPauses.capMsForTesting = 8_000
        openBank()
        waitFor("the cap") { FilterService.isListed(context) && state.bankPause == null }
        assertTrue(ended(), ended().endsWith(BankPause.CAP))
        assertEquals(bank, state.bankCapped)
        assertFalse("no new pause while the same bank app is still in front", BankPauses.shouldPause(context, bank))
    }

    @Test
    fun withoutTheGrantItWaitsToBeSwitchedBackOnByHand() {
        shell("pm revoke ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
        try {
            openBank()
            assertFalse(state.bankPause!!.auto)
            shell("input keyevent KEYCODE_HOME")
            Thread.sleep(8_000)
            assertFalse("it cannot switch itself back on", FilterService.isListed(context))
            assertNotNull(state.bankPause)
            filterOn() // by hand
            waitFor("the pause ended by hand") { state.bankPause == null && events.read().any { it.kind == EventKind.BANK_PAUSE_ENDED } }
            assertTrue(ended(), ended().endsWith(BankPause.BY_HAND))
        } finally {
            shell("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
        }
    }
}
