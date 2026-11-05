package app.lockmein

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
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
import app.lockmein.core.Restrictions
import app.lockmein.core.Totp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
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
    // Requests made on Sunday 2026-10-25 have waited their seven days by Monday 2026-11-02; the window is that Monday 09:00 to 10:00.
    private val asked: Instant = LocalDate.of(2026, 10, 25).atTime(12, 0).atZone(zone).toInstant()
    private val window: Instant = LocalDate.of(2026, 11, 2).atTime(9, 30).atZone(zone).toInstant()
    private val monday: Instant = LocalDate.of(2026, 11, 2).atTime(12, 0).atZone(zone).toInstant()
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

    @Before
    fun reset() {
        events.clear()
        state.clear()
        store.protectionActive = false
        // These tests are about a finished setup: rules locked once protection is on. (Before completion every change is free.)
        store.setupComplete = true
        store.save(config)
    }

    @After
    fun tidy() {
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
            Restrictions.CONFIG_PRIVATE_DNS to UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
        )
        for ((ours, androids) in expected) assertEquals(androids, ours)
        if (android.os.Build.VERSION.SDK_INT >= 35) assertEquals(UserManager.DISALLOW_ADD_PRIVATE_PROFILE, Restrictions.ADD_PRIVATE_PROFILE)
    }

    @Test
    fun a2_theAppIsDeviceOwner() {
        assertTrue("run `dpm set-device-owner app.lockmein/.AdminReceiver` first", dpm.isDeviceOwnerApp(context.packageName))
    }

    @Test
    fun b1_restrictionsAreInForceAndTheTestBuildKeepsDebugging() {
        val report = PolicyApplier(context).apply(planner.plan(Phase.DAY, BuildMode.TEST, inventory))
        assertEquals(emptyList<String>(), report.failed)
        for (key in listOf(Restrictions.SAFE_BOOT, Restrictions.CONFIG_DATE_TIME, Restrictions.ADD_USER, Restrictions.CONFIG_VPN, Restrictions.CONFIG_PRIVATE_DNS)) {
            assertTrue("$key should be on", users.hasUserRestriction(key))
        }
        for (key in listOf(Restrictions.ADD_MANAGED_PROFILE, Restrictions.INSTALL_UNKNOWN_SOURCES_GLOBALLY)) assertTrue("$key should be on", users.hasUserRestriction(key))
        if (android.os.Build.VERSION.SDK_INT >= 35) assertTrue(users.hasUserRestriction(Restrictions.ADD_PRIVATE_PROFILE))
        assertFalse("the test build must keep USB debugging", users.hasUserRestriction(Restrictions.DEBUGGING))
        assertFalse(users.hasUserRestriction(Restrictions.FACTORY_RESET))
    }

    // The sealed build's extra restrictions (debugging, factory reset) are not applied here: they would cut the adb connection the test run uses.
    // The planner tests on the JVM check that the sealed plan includes them.
    @Test
    fun b5_theClockAndZoneComeFromTheNetwork() {
        PolicyApplier(context).apply(planner.plan(Phase.DAY, BuildMode.TEST, inventory))
        assertTrue(dpm.getAutoTimeEnabled(admin))
        assertTrue(dpm.getAutoTimeZoneEnabled(admin))
    }

    @Test
    fun b6_chromeIsConfiguredSoItsOwnSettingsCannotGoAroundTheFilter() {
        val installed = "com.android.chrome" in snapshot.installed
        PolicyApplier(context).apply(planner.plan(Phase.DAY, BuildMode.TEST, inventory))
        val policy = dpm.getApplicationRestrictions(admin, "com.android.chrome")
        if (installed) {
            assertEquals("off", policy.getString("DnsOverHttpsMode"))
            assertEquals(1, policy.getInt("IncognitoModeAvailability"))
            assertEquals(2, policy.getInt("ForceYouTubeRestrict"))
            assertTrue(policy.getBoolean("ForceGoogleSafeSearch"))
        } else {
            assertTrue(policy.isEmpty)
        }
    }

    @Test
    fun b2_dnsIsPinnedToTheConfiguredHostAndLocked() {
        PolicyApplier(context).apply(planner.plan(Phase.DAY, BuildMode.TEST, inventory))
        assertEquals(DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME, dpm.getGlobalPrivateDnsMode(admin))
        assertEquals(config.dnsHost, dpm.getGlobalPrivateDnsHost(admin))
        assertTrue(users.hasUserRestriction(Restrictions.CONFIG_PRIVATE_DNS))
    }

    @Test
    fun b3_theAppCannotBeUninstalledOrStopped() {
        PolicyApplier(context).apply(planner.plan(Phase.DAY, BuildMode.TEST, inventory))
        assertTrue(dpm.isUninstallBlocked(admin, context.packageName))
        assertTrue(context.packageName in dpm.getUserControlDisabledPackages(admin))
    }

    @Test
    fun c1_nightClosesOnlyThePickedAppsAndTheNextDayReleasesThem() {
        val (victim, bystander) = appsToSuspend().take(2)
        val picked = config.copy(nightClosed = setOf(victim))
        store.save(picked)
        store.protectionActive = true

        val atNight = PolicyRunner(context).run(picked, night)
        assertTrue("the night pass should finish: ${atNight.report.failed}", atNight.report.ok)
        assertTrue(dpm.isPackageSuspended(admin, victim))
        assertFalse("an app not picked keeps running at night", dpm.isPackageSuspended(admin, bystander))
        assertTrue(atNight.nextRunAt.isAfter(night) && !atNight.nextRunAt.isAfter(night.plus(PolicyRunner.CHECK_EVERY)))

        val noon = LocalDate.of(2026, 11, 3).atTime(12, 0).atZone(zone).toInstant()
        PolicyRunner(context).run(picked, noon)
        assertFalse(dpm.isPackageSuspended(admin, victim))
    }

    @Test
    fun c2_protectedPackagesAreNeverSuspended() {
        PolicyApplier(context).apply(planner.plan(Phase.NIGHT, BuildMode.TEST, inventory))
        val launcher = snapshot.protectedPackages.first { it in snapshot.installed }
        assertFalse(dpm.isPackageSuspended(admin, launcher))
        assertFalse(dpm.isPackageSuspended(admin, context.packageName))
    }

    @Test
    fun c3_aPassWritesItsResultToTheEventLog() {
        store.protectionActive = true
        PolicyRunner(context).run(config, monday)
        assertTrue(events.read().any { it.kind == EventKind.PASS_OK })
    }

    @Test
    fun d1_aNewAppIsHeldBackUntilItsRequestHasWaitedAndTheWindowIsOpen() {
        val app = anAppToSuspend()
        store.protectionActive = true
        // The first pass records what is installed (minus one app, as if it arrived later); the next pass finds it new.
        state.noteInstalled(snapshot.installed - app)
        PolicyRunner(context).run(config, monday)
        assertTrue(app in state.quarantined)
        assertTrue(dpm.isPackageSuspended(admin, app))
        assertTrue(events.read().any { it.kind == EventKind.APP_QUARANTINED && it.detail == app })

        val manager = ChangeManager(context)
        assertTrue(manager.releaseApp(app, asked) is Outcome.Done)
        val id = manager.requests().single().id
        assertTrue("not yet: the wait is not over", manager.applyRequest(id, asked.plusSeconds(3600), asked.plusSeconds(3600)) is Outcome.Refused)
        assertTrue("not on a Monday afternoon: the window is closed", manager.applyRequest(id, monday, monday) is Outcome.Refused)
        assertTrue(app in state.quarantined)
        assertTrue(manager.applyRequest(id, window, window) is Outcome.Done)
        assertFalse(app in state.quarantined)
        assertTrue(events.read().any { it.kind == EventKind.APP_RELEASED })
    }

    @Test
    fun e1_theListsAreEditedOnlyWhereTheRulesAllow() {
        val app = anAppToSuspend()
        val manager = ChangeManager(context)
        store.protectionActive = true

        // Closing an app at night works at once.
        assertTrue(manager.editList(AppList.NIGHT_CLOSED, ListOp.ADD, app, asked) is Outcome.Done)
        assertTrue(app in store.load()!!.nightClosed)

        // Opening the phone up (taking it off) is only a request: nothing changes at once, whatever the day.
        assertTrue(manager.editList(AppList.NIGHT_CLOSED, ListOp.REMOVE, app, asked) is Outcome.Done)
        assertTrue(app in store.load()!!.nightClosed)
        val id = manager.requests().single().id

        // A wound-forward phone clock is not believed: the network says it is still Monday afternoon.
        assertTrue(manager.applyRequest(id, window, monday) is Outcome.Refused)
        assertTrue("no network time, no change", manager.applyRequest(id, window, null) is Outcome.Refused)
        assertTrue(app in store.load()!!.nightClosed)

        // Waited, in the window, clocks agreeing: carried out.
        assertTrue(manager.applyRequest(id, window, window) is Outcome.Done)
        assertFalse(app in store.load()!!.nightClosed)
        assertTrue(manager.requests().isEmpty())

        // Closing the phone more works at once, on any day.
        assertTrue(manager.editList(AppList.NIGHT_CLOSED, ListOp.ADD, app, monday) is Outcome.Done)
        assertTrue(manager.editList(AppList.ALWAYS_BLOCKED, ListOp.ADD, app, monday) is Outcome.Done)
        assertTrue(dpm.isPackageSuspended(admin, app))

        // A request can be cancelled.
        assertTrue(manager.editList(AppList.ALWAYS_BLOCKED, ListOp.REMOVE, app, monday) is Outcome.Done)
        assertTrue(manager.cancelRequest(manager.requests().single().id) is Outcome.Done)
        assertTrue(manager.requests().isEmpty())

        // The phone's essentials cannot be listed at all.
        val launcher = snapshot.protectedPackages.first { it in snapshot.installed }
        assertTrue(manager.editList(AppList.ALWAYS_BLOCKED, ListOp.ADD, launcher, monday) is Outcome.Refused)

        val kinds = events.read().map { it.kind }
        assertTrue(EventKind.DENIED in kinds && EventKind.LOOSEN_REQUESTED in kinds && EventKind.LOOSENED in kinds && EventKind.TIGHTENED in kinds)
    }

    @Test
    fun e2_beforeProtectionStartsEverythingIsFree() {
        val app = anAppToSuspend()
        val manager = ChangeManager(context)
        assertTrue(manager.editList(AppList.NIGHT_CLOSED, ListOp.ADD, app, monday) is Outcome.Done)
        assertTrue(manager.editList(AppList.NIGHT_CLOSED, ListOp.REMOVE, app, monday) is Outcome.Done)
        assertFalse(app in store.load()!!.nightClosed)
        assertTrue(manager.saveSettings(config.copy(nightStart = "21:30"), monday) is Outcome.Done)
        assertEquals("21:30", store.load()!!.nightStart)
    }

    @Test
    fun e3_settingsChangesAreRequestsAfterProtectionStarts() {
        val manager = ChangeManager(context)
        store.protectionActive = true
        val changed = config.copy(nightStart = "21:30")
        assertTrue(manager.saveSettings(changed, asked) is Outcome.Done)
        assertEquals("22:00", store.load()!!.nightStart)

        // A bigger change asked later replaces the first one and starts its wait again.
        val bigger = changed.copy(nightStart = "23:30")
        assertTrue(manager.saveSettings(bigger, asked.plusSeconds(6 * 86_400L)) is Outcome.Done)
        val id = manager.requests().single().id
        assertTrue(manager.applyRequest(id, window, window) is Outcome.Refused)
        assertEquals("22:00", store.load()!!.nightStart)

        // A broken value is refused at once.
        assertTrue(manager.saveSettings(changed.copy(dnsHost = "not a host"), asked) is Outcome.Refused)
    }

    @Test
    fun e4_aBrowserIsAnOpeningLikeAnyOther() {
        val manager = ChangeManager(context)
        store.protectionActive = true
        assertTrue(manager.requestBrowser("com.brave.browser", asked) is Outcome.Done)
        assertTrue(manager.requestBrowser("com.brave.browser", asked.plusSeconds(3 * 86_400L)) is Outcome.Done)
        val request = manager.requests().single()
        assertEquals("the first request counts", asked.toEpochMilli(), request.requestedAtMillis)
        assertTrue("a browser needs no code but must wait", manager.applyRequest(request.id, asked.plusSeconds(1800), asked.plusSeconds(1800)) is Outcome.Refused)
        assertTrue("com.brave.browser" !in state.approvedBrowsers)
        assertTrue(manager.applyRequest(request.id, window, window) is Outcome.Done)
        assertTrue("com.brave.browser" in state.approvedBrowsers)
    }

    @Test
    fun f1_theFriendsCodeUnblocksOneAppForAnHourAndNothingElse() {
        val manager = ChangeManager(context)
        val secret = manager.newFriendSecret()
        store.protectionActive = true
        val app = anAppToSuspend()
        val now = Instant.now()
        val code = Totp.code(secret, Totp.counter(now))
        val wrong = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')

        assertTrue(manager.editList(AppList.ALWAYS_BLOCKED, ListOp.ADD, app, now) is Outcome.Done)
        assertTrue(dpm.isPackageSuspended(admin, app))

        assertTrue(manager.redeemEmergency(wrong, app, now) is Outcome.Refused)
        assertTrue(dpm.isPackageSuspended(admin, app))
        assertTrue(manager.redeemEmergency(code, app, now) is Outcome.Done)
        assertFalse("the app is open for the hour", dpm.isPackageSuspended(admin, app))
        assertTrue("no list and no setting changed", app in store.load()!!.alwaysBlocked)
        assertTrue("a code works once", manager.redeemEmergency(code, app, now.plusSeconds(2)) is Outcome.Refused)

        // After the hour the next pass closes it again.
        PolicyRunner(context).run(config.copy(alwaysBlocked = store.load()!!.alwaysBlocked), now.plusSeconds(3601))
        assertTrue(dpm.isPackageSuspended(admin, app))

        val kinds = events.read().map { it.kind }
        assertTrue(EventKind.EMERGENCY_USED in kinds && EventKind.EMERGENCY_REFUSED in kinds)
    }

    @Test
    fun f2_theEmergencyCodeCannotOpenTheWindowOrChangeAnything() {
        val manager = ChangeManager(context)
        val secret = manager.newFriendSecret()
        store.protectionActive = true
        val app = anAppToSuspend()
        assertTrue(manager.editList(AppList.NIGHT_CLOSED, ListOp.ADD, app, asked) is Outcome.Done)
        assertTrue(manager.editList(AppList.NIGHT_CLOSED, ListOp.REMOVE, app, asked) is Outcome.Done)
        val id = manager.requests().single().id
        val now = Instant.now()
        assertTrue(manager.redeemEmergency(Totp.code(secret, Totp.counter(now)), app, now) is Outcome.Done)
        assertTrue("an unblock is not a window", manager.applyRequest(id, monday, monday) is Outcome.Refused)
        assertTrue(app in store.load()!!.nightClosed)
    }

    @Test
    fun f3_theEnrolmentCodeProvesTheSecretReachedTheFriend() {
        val manager = ChangeManager(context)
        val secret = manager.newFriendSecret()
        assertTrue(manager.hasFriendSecret)
        val right = Totp.code(secret, Totp.counter(Instant.now()))
        val wrong = ((right.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        assertFalse(manager.verifyEnrolment(wrong))
        assertTrue(manager.verifyEnrolment(right))
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

    @Test
    fun y1_theHealthCheckReadsBackEveryRuleAfterAPass() {
        store.protectionActive = true
        TrustedClock(context).renewIfStale()
        assertTrue("the emulator is online, so network time is known", TrustedClock(context).trustedNow() != null)
        val result = PolicyRunner(context).run(config, monday)
        assertEquals(emptyList<String>(), result.report.failed)
        val health = Health(context).check(monday)
        assertEquals("every check passes once the rules are in force", emptyList<String>(), health.filter { !it.ok }.map { "${it.title}: ${it.detail}" })
        assertTrue(health.any { it.title == "Private DNS" } && health.any { it.title == "Can't be stopped or wiped" })
    }

    @Test
    fun y2_theHealthCheckSeesARuleThatWasUndone() {
        // An app that says it works while a rule was taken off is worse than none: undo one behind the app's back and look.
        store.protectionActive = true
        PolicyRunner(context).run(config, monday)
        dpm.clearUserRestriction(admin, Restrictions.CONFIG_VPN)
        dpm.setUninstallBlocked(admin, context.packageName, false)
        val failed = Health(context).check(monday).filter { !it.ok }.map { it.title }
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
    fun z_removingTheProtectionNeedsARequestTheWindowAndTheCodeAndThenEndsDeviceOwnership() {
        val manager = ChangeManager(context)
        val secret = manager.newFriendSecret()
        manager.startProtection()
        val code = Totp.code(secret, Totp.counter(window))
        val wrong = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')

        // No request, no removal, even with the right code in the window.
        assertTrue(manager.removeProtection(code, window, window) is Outcome.Refused)

        assertTrue(manager.requestRemoval(asked) is Outcome.Done)
        // Too early, wrong day, wrong clock: each refused even with the right code.
        assertTrue(manager.removeProtection(Totp.code(secret, Totp.counter(asked.plusSeconds(60))), asked.plusSeconds(60), asked.plusSeconds(60)) is Outcome.Refused)
        assertTrue(manager.removeProtection(Totp.code(secret, Totp.counter(monday)), monday, monday) is Outcome.Refused)
        assertTrue(manager.removeProtection(code, window, monday) is Outcome.Refused)
        assertTrue(dpm.isDeviceOwnerApp(context.packageName))

        // In the window a wrong code does nothing.
        assertTrue(manager.removeProtection(wrong, window, window) is Outcome.Refused)
        assertTrue(dpm.isDeviceOwnerApp(context.packageName))

        // Waited, window, clocks and the right code together lift everything.
        val outcome = manager.removeProtection(code, window, window)
        assertTrue("removed: $outcome", outcome is Outcome.Done)
        assertFalse(dpm.isDeviceOwnerApp(context.packageName))
        assertFalse(store.protectionActive)
        assertFalse(users.hasUserRestriction(Restrictions.SAFE_BOOT))
        assertEquals("opportunistic", Settings.Global.getString(context.contentResolver, "private_dns_mode"))
    }
}
