package app.lockmein

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import app.lockmein.core.AppList
import app.lockmein.core.BuildMode
import app.lockmein.core.EventKind
import app.lockmein.core.Inventory
import app.lockmein.core.ListOp
import app.lockmein.core.KnownBrowsers
import app.lockmein.core.LockConfig
import app.lockmein.core.Phase
import app.lockmein.core.PolicyPlanner
import app.lockmein.core.RequestKind
import app.lockmein.core.Restrictions
import app.lockmein.core.Totp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Runs on a real emulator where the app has been made device owner (`dpm set-device-owner`, see scripts/ci-emulator.sh).
 * It proves Android itself enforces what the planner decides, and that every change goes through the rules, which the JVM tests cannot.
 * The last test (z...) removes the protection, so it runs last.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PolicyApplierTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    private val admin = ComponentName(context, AdminReceiver::class.java)
    private val store = ConfigStore(context)
    private val state = LockState(context)
    private val events = EventLog(context)
    private val snapshot = DeviceInventory(context).read()

    private val zone = ZoneId.systemDefault()
    // Sunday 2026-11-01 at 20:30 is in Sunday settings window (20:00-21:00), daily window (20:00-21:30), and monthly uninstall window (1st 20:00-21:00)
    private val sunday1stEvening: Instant = LocalDate.of(2026, 11, 1).atTime(20, 30).atZone(zone).toInstant()
    // Sunday 2026-11-08 at 20:30 is in Sunday settings window and daily window, but NOT in monthly uninstall window
    private val sunday8thEvening: Instant = LocalDate.of(2026, 11, 8).atTime(20, 30).atZone(zone).toInstant()
    // Monday 2026-11-02 at 12:00 is outside all windows
    private val mondayNoon: Instant = LocalDate.of(2026, 11, 2).atTime(12, 0).atZone(zone).toInstant()
    // Monday 2026-11-02 at 15:00 is outside daily window (20:00-21:30)
    private val monday1500: Instant = LocalDate.of(2026, 11, 2).atTime(15, 0).atZone(zone).toInstant()
    // Monday 2026-11-02 at 20:05 is inside daily window (20:00-21:30)
    private val monday2005: Instant = LocalDate.of(2026, 11, 2).atTime(20, 5).atZone(zone).toInstant()
    // Monday 2026-11-02 at 20:30 is inside daily window, but NOT in uninstall window (it's the 2nd) and NOT settings window (it's Monday)
    private val monday2030: Instant = LocalDate.of(2026, 11, 2).atTime(20, 30).atZone(zone).toInstant()
    private val night: Instant = LocalDate.of(2026, 11, 3).atTime(2, 0).atZone(zone).toInstant()

    private val config = LockConfig(dnsHost = "dns.google", allowedBrowser = "com.brave.browser")
    private val planner get() = PolicyPlanner(Rules.forDevice(snapshot, context.packageName, config), config.dnsHost)
    private val inventory get() = Inventory(snapshot.installed)

    private fun anAppToSuspend(): String =
        appsToSuspend().first()

    /** Plain apps: not protected, not this app, not a browser or YouTube (those close for their own reasons). */
    private fun appsToSuspend(): List<String> = snapshot.installed.filter {
        it !in snapshot.protectedPackages && it != context.packageName && it !in snapshot.browsers && it !in KnownBrowsers.ALL && it != "com.google.android.youtube"
    }.sorted()

    private val packageReceiver = PackageReceiver()

    @Before
    fun reset() {
        PackageReceiver.awaitIdle()
        events.clear()
        state.clear()
        store.protectionActive = false
        // These tests are about a finished setup: rules locked once protection is on. (Before completion every change is free.)
        store.redoing = false
        store.setupComplete = true
        store.save(config)

        runCatching { context.unregisterReceiver(packageReceiver) }
        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(packageReceiver, filter)
        }
    }

    @After
    fun tidy() {
        runCatching { context.unregisterReceiver(packageReceiver) }
        PackageReceiver.awaitIdle()
        store.protectionActive = false
        store.setupComplete = false
        state.clear()
        if (dpm.isDeviceOwnerApp(context.packageName)) PolicyApplier(context).release(snapshot.installed)
    }

    @Test
    fun a1_everyRestrictionKeyIsTheOneAndroidUses() {
        // The logic module is free of Android, so it spells the keys out; this keeps those spellings honest.
        val expected = mapOf(
            Restrictions.DEBUGGING to UserManager.DISALLOW_DEBUGGING_FEATURES,
            Restrictions.SAFE_BOOT to UserManager.DISALLOW_SAFE_BOOT,
            Restrictions.FACTORY_RESET to UserManager.DISALLOW_FACTORY_RESET,
            Restrictions.CONFIG_DATE_TIME to UserManager.DISALLOW_CONFIG_DATE_TIME,
            Restrictions.ADD_USER to UserManager.DISALLOW_ADD_USER,
            Restrictions.ADD_MANAGED_PROFILE to UserManager.DISALLOW_ADD_MANAGED_PROFILE,
            Restrictions.CONFIG_VPN to UserManager.DISALLOW_CONFIG_VPN,
            Restrictions.INSTALL_UNKNOWN_SOURCES_GLOBALLY to UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
            Restrictions.CONFIG_PRIVATE_DNS to "disallow_config_private_dns",
        )
        for ((ours, androids) in expected) assertEquals(androids, ours)
        if (android.os.Build.VERSION.SDK_INT >= 35) assertEquals("no_add_private_profile" /* UserManager.DISALLOW_ADD_PRIVATE_PROFILE, API 35 */, Restrictions.ADD_PRIVATE_PROFILE)

        val plan = planner.plan(Phase.DAY, BuildMode.SEALED, inventory)
        expected.keys.filter { it != Restrictions.INSTALL_UNKNOWN_SOURCES_GLOBALLY }.forEach { key ->
            assertTrue("$key is planned in sealed build", key in plan.restrictions)
        }
    }

    @Test
    fun b1_privateDnsTakesEffectAndIsPinned() {
        val report = PolicyApplier(context).apply(planner.plan(Phase.DAY, BuildMode.TEST, inventory))
        assertEquals(emptyList<String>(), report.failed)
        assertEquals(DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME, dpm.getGlobalPrivateDnsMode(admin))
        assertEquals("dns.google", dpm.getGlobalPrivateDnsHost(admin))
        assertTrue("user cannot change private DNS", users.hasUserRestriction(Restrictions.CONFIG_PRIVATE_DNS))
        for (key in listOf(Restrictions.SAFE_BOOT, Restrictions.CONFIG_DATE_TIME, Restrictions.ADD_USER, Restrictions.CONFIG_VPN)) {
            assertTrue("$key should be on", users.hasUserRestriction(key))
        }
        assertTrue(users.hasUserRestriction(Restrictions.ADD_MANAGED_PROFILE))
        assertFalse("installing apps is allowed; new apps are held back instead", users.hasUserRestriction(Restrictions.INSTALL_UNKNOWN_SOURCES_GLOBALLY))
        if (android.os.Build.VERSION.SDK_INT >= 35) assertTrue(users.hasUserRestriction(Restrictions.ADD_PRIVATE_PROFILE))
        assertFalse("the test build must keep USB debugging", users.hasUserRestriction(Restrictions.DEBUGGING))
        assertFalse(users.hasUserRestriction(Restrictions.FACTORY_RESET))
    }

    @Test
    fun b2_autoTimeAndZoneAreForced() {
        PolicyApplier(context).apply(planner.plan(Phase.DAY, BuildMode.TEST, inventory))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME_ZONE))
    }

    @Test
    fun c1_suspendingAndUnsuspendingChangesTheStateAndroidSees() {
        val app = anAppToSuspend()
        val closed = planner.plan(Phase.DAY, BuildMode.TEST, inventory).copy(suspended = setOf(app), unsuspended = emptySet())
        val open = planner.plan(Phase.DAY, BuildMode.TEST, inventory).copy(suspended = emptySet(), unsuspended = setOf(app))

        PolicyApplier(context).apply(closed)
        assertTrue("$app is suspended", dpm.isPackageSuspended(admin, app))

        PolicyApplier(context).apply(open)
        assertFalse("$app is open again", dpm.isPackageSuspended(admin, app))
    }

    @Test
    fun c2_theLauncherAndThisAppAreNeverSuspended() {
        PolicyApplier(context).apply(planner.plan(Phase.NIGHT, BuildMode.TEST, inventory))
        val launcher = snapshot.protectedPackages.first { it in snapshot.installed }
        assertFalse(dpm.isPackageSuspended(admin, launcher))
        assertFalse(dpm.isPackageSuspended(admin, context.packageName))
    }

    @Test
    fun c3_aPassWritesItsResultToTheEventLog() {
        store.protectionActive = true
        PolicyRunner(context).run(config, mondayNoon)
        assertTrue(events.read().any { it.kind == EventKind.PASS_OK })
    }

    @Test
    fun d1_aNewAppIsHeldBackUntilQuarantineIsReleasedInWindowOrByFriendCode() {
        val app = anAppToSuspend()
        store.protectionActive = true
        // The first pass records what is installed (minus one app, as if it arrived later); the next pass finds it new.
        state.noteInstalled(snapshot.installed - app)
        PolicyRunner(context).run(config, mondayNoon)
        assertTrue(app in state.quarantined)
        assertTrue(dpm.isPackageSuspended(admin, app))
        assertTrue(events.read().any { it.kind == EventKind.APP_QUARANTINED && it.detail == app })

        val manager = ChangeManager(context)
        // Releasing during the week records a request
        val reqOutcome = manager.releaseApp(app, mondayNoon)
        assertTrue(reqOutcome is Outcome.Done)
        assertTrue(app in state.quarantined)
        val reqId = "${RequestKind.RELEASE_APP}:$app"

        // Outside Sunday settings window, approval is refused
        assertTrue(manager.approveRequest(reqId, mondayNoon) is Outcome.Refused)
        assertTrue(app in state.quarantined)

        // Inside Sunday settings window, approval succeeds and releases the app
        assertTrue(manager.approveRequest(reqId, sunday8thEvening) is Outcome.Done)
        assertFalse(app in state.quarantined)
        assertTrue(events.read().any { it.kind == EventKind.APP_RELEASED })
    }

    @Test
    fun e1_theListsAreEditedOnlyWhereTheRulesAllow() {
        val app = anAppToSuspend()
        val manager = ChangeManager(context)
        store.protectionActive = true

        // Closing an app at night works at once.
        assertTrue(manager.editList(AppList.NIGHT_CLOSED, ListOp.ADD, app, mondayNoon) is Outcome.Done)
        assertTrue(app in store.load()!!.nightClosed)

        // Removing an app when locked records a request for Sunday review (nothing changes yet).
        val removeRes = manager.editList(AppList.NIGHT_CLOSED, ListOp.REMOVE, app, mondayNoon)
        assertTrue(removeRes is Outcome.Done)
        assertTrue(app in store.load()!!.nightClosed)
        val reqId = "${RequestKind.NIGHT_REMOVE}:$app"
        val req = state.requests.firstOrNull { it.id == reqId }
        assertNotNull(req)
        assertEquals(1, req!!.count)

        // Asking again increments the count to 2
        val repeatRes = manager.editList(AppList.NIGHT_CLOSED, ListOp.REMOVE, app, mondayNoon.plusSeconds(30))
        assertTrue(repeatRes is Outcome.Done)
        assertEquals(2, state.requests.first { it.id == reqId }.count)

        // Dismissing the request drops it and records in history
        assertTrue(manager.dismissRequest(reqId, mondayNoon) is Outcome.Done)
        assertTrue(state.requests.none { it.id == reqId })
        assertTrue(state.requestHistory.any { it.id == reqId && it.count == 2 })

        // Adding to always blocked works at once on any day.
        assertTrue(manager.editList(AppList.ALWAYS_BLOCKED, ListOp.ADD, app, mondayNoon) is Outcome.Done)
        assertTrue(dpm.isPackageSuspended(admin, app))

        // Protected packages cannot be listed.
        val launcher = snapshot.protectedPackages.first { it in snapshot.installed }
        assertTrue(manager.editList(AppList.ALWAYS_BLOCKED, ListOp.ADD, launcher, mondayNoon) is Outcome.Refused)

        val kinds = events.read().map { it.kind }
        assertTrue(EventKind.LOOSEN_REQUESTED in kinds && EventKind.TIGHTENED in kinds)
    }

    @Test
    fun e2_beforeProtectionStartsEverythingIsFree() {
        val app = anAppToSuspend()
        val manager = ChangeManager(context)
        assertTrue(manager.editList(AppList.NIGHT_CLOSED, ListOp.ADD, app, mondayNoon) is Outcome.Done)
        assertTrue(manager.editList(AppList.NIGHT_CLOSED, ListOp.REMOVE, app, mondayNoon) is Outcome.Done)
        assertFalse(app in store.load()!!.nightClosed)
        assertTrue(manager.saveSettings(config.copy(nightStart = "21:30"), mondayNoon) is Outcome.Done)
        assertEquals("21:30", store.load()!!.nightStart)
    }

    @Test
    fun e3_settingsChangesAreRequestedAndReviewedOnSunday() {
        val manager = ChangeManager(context)
        store.protectionActive = true
        val changed = config.copy(nightStart = "21:30")

        // Changing settings when locked records a request for Sunday review
        assertTrue(manager.saveSettings(changed, mondayNoon) is Outcome.Done)
        assertEquals("22:00", store.load()!!.nightStart)
        val reqId = "${RequestKind.SETTINGS}:"
        assertNotNull(state.requests.firstOrNull { it.id == reqId })

        // Outside Sunday window: approval refused
        assertTrue(manager.approveRequest(reqId, mondayNoon) is Outcome.Refused)
        assertEquals("22:00", store.load()!!.nightStart)

        // Inside Sunday window: approval applies the change
        assertTrue(manager.approveRequest(reqId, sunday8thEvening) is Outcome.Done)
        assertEquals("21:30", store.load()!!.nightStart)
    }

    @Test
    fun e4_aBrowserApprovalIsRequestedAndReviewedOnSunday() {
        val manager = ChangeManager(context)
        store.protectionActive = true
        val browser = "com.brave.browser"

        // Approving browser when locked records a request
        assertTrue(manager.approveBrowser(browser, mondayNoon) is Outcome.Done)
        assertFalse(browser in state.approvedBrowsers)
        val reqId = "${RequestKind.BROWSER}:$browser"
        assertNotNull(state.requests.firstOrNull { it.id == reqId })

        // Outside Sunday window: approval refused
        assertTrue(manager.approveRequest(reqId, mondayNoon) is Outcome.Refused)

        // Inside Sunday window: approved
        assertTrue(manager.approveRequest(reqId, sunday8thEvening) is Outcome.Done)
        assertTrue(browser in state.approvedBrowsers)
    }

    @Test
    fun f1_theEnrolmentCodeProvesTheSecretReachedTheFriend() {
        val manager = ChangeManager(context)
        val secret = manager.newFriendSecret()
        assertTrue(manager.hasFriendSecret)
        val right = Totp.code(secret, Totp.counter(Instant.now()))
        val wrong = ((right.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        assertFalse(manager.verifyEnrolment(wrong))
        assertTrue(manager.verifyEnrolment(right))
    }

    @Test
    fun f2_friendCodeAcceptedOnceThenActionAppliedWithoutSecondCode() {
        val manager = ChangeManager(context)
        val secret = manager.newFriendSecret()
        store.protectionActive = true
        val app = anAppToSuspend()
        store.save(config.copy(alwaysBlocked = setOf(app)))

        // Initial policy pass suspends the always-blocked app
        PolicyRunner(context).run(config.copy(alwaysBlocked = setOf(app)), mondayNoon)
        assertTrue(dpm.isPackageSuspended(admin, app))

        // Entering valid code authorizes action token
        val code = Totp.code(secret, Totp.counter(mondayNoon))
        val (token, res) = manager.checkHolderCode(code, mondayNoon)
        assertTrue("code accepted", res is Outcome.Done)
        assertNotNull(token)

        // One action is applied WITHOUT entering any second code (unblock for an hour)
        val unblockRes = manager.friendUnblockApp(app, mondayNoon)
        assertTrue("action applied without second code", unblockRes is Outcome.Done)
        assertTrue(app in state.unblocks(mondayNoon).keys)
        assertFalse("app is unsuspended for an hour", dpm.isPackageSuspended(admin, app))
        assertTrue("one code allows one action only", manager.friendRedoSetup(mondayNoon) is Outcome.Refused)

        // Code counter was used up: using the same code again is refused
        val (secondToken, secondRes) = manager.checkHolderCode(code, mondayNoon)
        assertTrue("same code cannot be used twice", secondRes is Outcome.Refused)
        assertNull(secondToken)
    }

    @Test
    fun f3_fiveWrongCodesLockoutFor30Minutes() {
        val manager = ChangeManager(context)
        val secret = manager.newFriendSecret()
        store.protectionActive = true
        val validCode = Totp.code(secret, Totp.counter(mondayNoon))
        val wrongCode = ((validCode.toInt() + 1) % 1_000_000).toString().padStart(6, '0')

        for (i in 1..4) {
            val (token, res) = manager.checkHolderCode(wrongCode, mondayNoon)
            assertTrue("attempt $i should be refused", res is Outcome.Refused)
            assertNull(token)
            assertFalse(state.isLockedOut(mondayNoon))
        }

        // 5th attempt locks out
        val (fifthToken, fifth) = manager.checkHolderCode(wrongCode, mondayNoon)
        assertTrue(fifth is Outcome.Refused)
        assertNull(fifthToken)
        assertTrue(state.isLockedOut(mondayNoon))

        // Even correct code is rejected during lockout
        val (duringToken, duringLockout) = manager.checkHolderCode(validCode, mondayNoon.plus(Duration.ofMinutes(10)))
        assertTrue(duringLockout is Outcome.Refused)
        assertNull(duringToken)

        // After 30 minutes lockout expires
        val afterLockout = mondayNoon.plus(Duration.ofMinutes(31))
        assertFalse(state.isLockedOut(afterLockout))
        val validCodeAfter = Totp.code(secret, Totp.counter(afterLockout))
        val (afterToken, afterRes) = manager.checkHolderCode(validCodeAfter, afterLockout)
        assertTrue(afterRes is Outcome.Done)
        assertNotNull(afterToken)
    }

    @Test
    fun f4_windowAppSuspendedAt1500AndNotAt2005() {
        val app = anAppToSuspend()
        val customConfig = config.copy(windowApps = setOf(app))
        store.save(customConfig)
        store.protectionActive = true

        // At 15:00 outside daily window (20:00 to 21:30): app is suspended
        val at1500 = PolicyRunner(context).run(customConfig, monday1500)
        assertTrue(at1500.report.ok)
        assertTrue(dpm.isPackageSuspended(admin, app))

        // At 20:05 inside daily window: app is unsuspended
        val at2005 = PolicyRunner(context).run(customConfig, monday2005)
        assertTrue(at2005.report.ok)
        assertFalse(dpm.isPackageSuspended(admin, app))
    }

    @Test
    fun f5_uninstallItemHiddenOnThe2ndAndShownOnThe1stAt2030() {
        val schedule = Rules.schedule(config)
        val manager = ChangeManager(context)
        store.protectionActive = true

        // On the 2nd at 20:30: uninstall window is closed, uninstall refused
        assertFalse(schedule.inUninstallWindow(monday2030))
        assertTrue(manager.removeLockMeIn(monday2030) is Outcome.Refused)

        // On the 1st at 20:30: uninstall window is open
        assertTrue(schedule.inUninstallWindow(sunday1stEvening))
    }

    @Test
    fun f6_migrationAddsTheFivePackagesWithoutDroppingExistingOnes() {
        val customApp = "com.custom.myapp"
        val initial = LockConfig(
            dnsHost = "dns.google",
            alwaysBlocked = setOf(customApp),
            windowApps = emptySet(),
        )
        val migrated = ConfigStore.migrate(initial)

        assertTrue("existing custom package preserved", customApp in migrated.alwaysBlocked)
        for (pkg in LockConfig.ALWAYS_BLOCKED_DEFAULT) {
            assertTrue("$pkg added to alwaysBlocked", pkg in migrated.alwaysBlocked)
        }
        for (pkg in LockConfig.WINDOW_APPS_DEFAULT) {
            assertTrue("$pkg added to windowApps", pkg in migrated.windowApps)
        }
    }

    @Test
    fun f7_requestCountingAndSundayReviewRules() {
        val manager = ChangeManager(context)
        val app = anAppToSuspend()
        val customConfig = config.copy(nightClosed = setOf(app))
        store.save(customConfig)
        store.protectionActive = true

        // 1. Request made on Monday
        val req1 = manager.editList(AppList.NIGHT_CLOSED, ListOp.REMOVE, app, mondayNoon)
        assertTrue(req1 is Outcome.Done)
        val reqId = "${RequestKind.NIGHT_REMOVE}:$app"
        assertEquals(1, state.requests.first { it.id == reqId }.count)

        // 2. Request asked again increments count to 2
        manager.editList(AppList.NIGHT_CLOSED, ListOp.REMOVE, app, mondayNoon.plusSeconds(60))
        assertEquals(2, state.requests.first { it.id == reqId }.count)

        // 3. Outside Sunday settings window, approval is refused
        assertTrue(manager.approveRequest(reqId, mondayNoon) is Outcome.Refused)
        assertTrue(app in store.load()!!.nightClosed)

        // 4. In Sunday window (sunday8thEvening 20:30): approved without extra delay
        assertTrue(manager.approveRequest(reqId, sunday8thEvening) is Outcome.Done)
        assertFalse("app removed from nightClosed after Sunday approval", app in store.load()!!.nightClosed)
        assertTrue("request queue cleared", state.requests.none { it.id == reqId })
        assertTrue("recorded in history with count 2", state.requestHistory.any { it.id == reqId && it.count == 2 })

        // 5. Request made DURING Sunday window waits until next Sunday
        val app2 = appsToSuspend().last()
        store.save(store.load()!!.copy(alwaysBlocked = setOf(app2)))
        manager.editList(AppList.ALWAYS_BLOCKED, ListOp.REMOVE, app2, sunday8thEvening)
        val req2Id = "${RequestKind.BLOCKED_REMOVE}:$app2"

        // In same Sunday window: approval refused
        val resSameSunday = manager.approveRequest(req2Id, sunday8thEvening)
        assertTrue("requests made during window wait until next Sunday", resSameSunday is Outcome.Refused)

        // Next Sunday: approved
        val nextSunday = sunday8thEvening.plus(Duration.ofDays(7))
        assertTrue(manager.approveRequest(req2Id, nextSunday) is Outcome.Done)
        assertFalse(app2 in store.load()!!.alwaysBlocked)
    }

    @Test
    fun g1_theSecretIsStoredEncryptedAndComesBackOnlyToThisApp() {
        val secrets = SecretStore(context)
        val secret = ByteArray(20) { (it * 7 + 3).toByte() }
        secrets.save(secret)
        assertTrue(secrets.hasSecret)
        assertEquals(secret.toList(), secrets.load()!!.toList())
        val raw = context.getSharedPreferences("lock_secret", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse("the raw secret must not be in the file", raw.contains(android.util.Base64.encodeToString(secret, android.util.Base64.NO_WRAP)))
    }

    @Test
    fun h1_startingProtectionNeedsAFriendCodeAndAppliesThePolicy() {
        val manager = ChangeManager(context)
        SecretStore(context).clear()
        assertTrue(manager.startProtection() is Outcome.Refused)
        assertFalse(store.protectionActive)

        manager.newFriendSecret()
        val started = manager.startProtection()
        assertTrue("started: $started", started is Outcome.Done)
        assertTrue(store.protectionActive)
        assertTrue(users.hasUserRestriction(Restrictions.CONFIG_PRIVATE_DNS))
        assertTrue(events.read().any { it.kind == EventKind.PROTECTION_STARTED })
    }

    private fun runShellCommand(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return java.io.FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
    }

    @Test
    fun i1_newlyInstalledAppIsSuspendedWithin10Seconds() {
        val testPkg = "app.lockmein.testapp"
        val apkPath = "/data/local/tmp/test-app.apk"

        // Ensure not installed before test
        runShellCommand("pm uninstall $testPkg")
        Thread.sleep(1000)

        store.protectionActive = true

        // Install test APK
        val installOutput = runShellCommand("pm install -r $apkPath")
        assertTrue("pm install failed: $installOutput", installOutput.contains("Success"))

        // Assert it is suspended within 10 seconds without any other trigger
        val start = System.currentTimeMillis()
        var suspended = false
        while (System.currentTimeMillis() - start < 10_000) {
            if (dpm.isPackageSuspended(admin, testPkg)) {
                suspended = true
                break
            }
            Thread.sleep(500)
        }
        assertTrue("Newly installed app $testPkg must be suspended within 10s without any other trigger", suspended)

        // Clean up
        runShellCommand("pm uninstall $testPkg")
        PackageReceiver.awaitIdle()
    }

    @Test
    fun y1_theHealthCheckReadsBackEveryRuleAfterAPass() {
        store.protectionActive = true
        TrustedClock(context).renewIfStale()
        assertTrue("the emulator is online, so network time is known", TrustedClock(context).trustedNow() != null)
        val result = PolicyRunner(context).run(config, mondayNoon)
        assertEquals(emptyList<String>(), result.report.failed)
        val health = Health(context).check(mondayNoon)
        assertEquals("every check passes once the rules are in force", emptyList<String>(), health.filter { !it.ok }.map { "${it.title}: ${it.detail}" })
        assertTrue(health.any { it.title == "Private DNS" } && health.any { it.title == "Can't be stopped or wiped" })
    }

    @Test
    fun y2_theHealthCheckSeesARuleThatWasUndone() {
        // An app that says it works while a rule was taken off is worse than none: undo one behind the app's back and look.
        store.protectionActive = true
        PolicyRunner(context).run(config, mondayNoon)
        dpm.clearUserRestriction(admin, Restrictions.CONFIG_VPN)
        dpm.setUninstallBlocked(admin, context.packageName, false)
        val failed = Health(context).check(mondayNoon).filter { !it.ok }.map { it.title }
        assertTrue("restrictions: $failed", "Phone restrictions" in failed)
        assertTrue("uninstall: $failed", "Can't be uninstalled" in failed)
    }

    @Test
    fun y3_beforeTheSetupIsCompleteAChangeWorksAtOnce() {
        store.setupComplete = false
        store.protectionActive = true
        val app = anAppToSuspend()
        store.save(config.copy(nightClosed = setOf(app)))
        val outcome = ChangeManager(context).editList(AppList.NIGHT_CLOSED, ListOp.REMOVE, app)
        assertTrue("$outcome", outcome is Outcome.Done)
        assertFalse(app in store.load()!!.nightClosed)
        assertTrue("no request queued", state.requests.isEmpty())
    }

    @Test
    fun y4_startRedoRequestedAndReviewedOnSundayOrDirectWithFriendCode() {
        val manager = ChangeManager(context)
        store.protectionActive = true
        // Outside window: startRedo records request
        assertTrue(manager.startRedo(mondayNoon) is Outcome.Done)
        assertTrue(store.setupComplete)
        val reqId = "${RequestKind.REDO_SETUP}:"
        assertNotNull(state.requests.firstOrNull { it.id == reqId })

        // In settings window: approveRequest reopens setup
        assertTrue(manager.approveRequest(reqId, sunday8thEvening) is Outcome.Done)
        assertFalse(store.setupComplete)
        assertTrue(store.redoing)
        assertTrue("reset stays closed in a redo", manager.resetSetup() is Outcome.Refused)
        assertTrue(store.protectionActive)

        val app = anAppToSuspend()
        store.save(config.copy(nightClosed = setOf(app)))
        assertTrue(manager.editList(AppList.NIGHT_CLOSED, ListOp.REMOVE, app) is Outcome.Done)
        assertFalse("changes are free while setup is open", app in store.load()!!.nightClosed)

        // Finishing setup locks rules again
        store.setupComplete = true
        assertFalse("finishing ends the redo", store.redoing)

        // Direct redo with friend code works at once
        val secret = manager.newFriendSecret()
        val code = Totp.code(secret, Totp.counter(mondayNoon))
        val (token, authRes) = manager.checkHolderCode(code, mondayNoon)
        assertTrue(authRes is Outcome.Done)
        assertNotNull(token)
        assertTrue(manager.friendRedoSetup(mondayNoon) is Outcome.Done)
        assertTrue(store.redoing)
        assertFalse(store.setupComplete)
    }

    @Test
    fun z_removingTheProtectionWorksOnlyInMonthlyUninstallWindowAndEndsDeviceOwnership() {
        val manager = ChangeManager(context)
        manager.newFriendSecret()
        manager.startProtection()
        assertTrue(dpm.isDeviceOwnerApp(context.packageName))

        // Outside monthly uninstall window (e.g. 2nd at 20:30): refused
        assertTrue(manager.removeLockMeIn(monday2030) is Outcome.Refused)
        assertTrue(dpm.isDeviceOwnerApp(context.packageName))

        // Inside monthly uninstall window (1st at 20:30): Done and ends device ownership
        val outcome = manager.removeLockMeIn(sunday1stEvening)
        assertTrue("removed: $outcome", outcome is Outcome.Done)
        assertFalse(dpm.isDeviceOwnerApp(context.packageName))
        assertFalse(store.protectionActive)
        assertFalse(users.hasUserRestriction(Restrictions.SAFE_BOOT))
        assertEquals("opportunistic", Settings.Global.getString(context.contentResolver, "private_dns_mode"))
    }
}
