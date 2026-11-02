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
    private val sundayWindow: Instant = LocalDate.of(2026, 11, 1).atTime(20, 30).atZone(zone).toInstant()
    private val monday: Instant = LocalDate.of(2026, 11, 2).atTime(12, 0).atZone(zone).toInstant()
    private val night: Instant = LocalDate.of(2026, 11, 3).atTime(2, 0).atZone(zone).toInstant()

    private val config = LockConfig(dnsHost = "dns.google", allowedBrowser = "com.brave.browser")
    private val planner get() = PolicyPlanner(Rules.forDevice(snapshot, context.packageName, config), config.dnsHost)
    private val inventory get() = Inventory(snapshot.installed)

    private fun anAppToSuspend(): String =
        snapshot.installed.first { it !in snapshot.protectedPackages && it != context.packageName && it !in config.nightAllowlist }

    @Before
    fun reset() {
        events.clear()
        state.clear()
        store.protectionActive = false
        store.save(config)
    }

    @After
    fun tidy() {
        store.protectionActive = false
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
        assertFalse("the test build must keep USB debugging", users.hasUserRestriction(Restrictions.DEBUGGING))
        assertFalse(users.hasUserRestriction(Restrictions.FACTORY_RESET))
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
    fun c1_nightSuspendsUserFacingAppsAndTheNextDayReleasesThem() {
        val victim = planner.plan(Phase.NIGHT, BuildMode.TEST, inventory).suspended.first()
        store.protectionActive = true

        val atNight = PolicyRunner(context).run(config, night)
        assertTrue("the night pass should finish: ${atNight.report.failed}", atNight.report.ok)
        assertTrue(dpm.isPackageSuspended(admin, victim))
        assertTrue(atNight.nextRunAt.isAfter(night) && !atNight.nextRunAt.isAfter(night.plus(PolicyRunner.CHECK_EVERY)))

        val noon = LocalDate.of(2026, 11, 3).atTime(12, 0).atZone(zone).toInstant()
        PolicyRunner(context).run(config, noon)
        assertEquals(victim in planner.plan(Phase.DAY, BuildMode.TEST, inventory).suspended, dpm.isPackageSuspended(admin, victim))
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
    fun d1_aNewAppIsHeldBackUntilTheOwnerLetsItThroughInTheWindow() {
        val app = anAppToSuspend()
        store.protectionActive = true
        // The first pass records what is installed (minus one app, as if it arrived later); the next pass finds it new.
        state.noteInstalled(snapshot.installed - app)
        PolicyRunner(context).run(config, monday)
        assertTrue(app in state.quarantined)
        assertTrue(dpm.isPackageSuspended(admin, app))
        assertTrue(events.read().any { it.kind == EventKind.APP_QUARANTINED && it.detail == app })

        val manager = ChangeManager(context)
        assertTrue(manager.releaseApp(app, monday, monday) is Outcome.Refused)
        assertTrue(app in state.quarantined)
        assertTrue(manager.releaseApp(app, sundayWindow, sundayWindow) is Outcome.Done)
        assertFalse(app in state.quarantined)
        assertTrue(events.read().any { it.kind == EventKind.APP_RELEASED })
    }

    @Test
    fun e1_theListsAreEditedOnlyWhereTheRulesAllow() {
        val app = anAppToSuspend()
        val manager = ChangeManager(context)
        store.protectionActive = true

        // Opening the phone up on a Monday is refused and changes nothing.
        assertTrue(manager.editList(AppList.NIGHT_ALLOWED, ListOp.ADD, app, monday, monday) is Outcome.Refused)
        assertFalse(app in store.load()!!.nightAllowlist)

        // The same edit in the Sunday window, with the network clock agreeing, goes through.
        assertTrue(manager.editList(AppList.NIGHT_ALLOWED, ListOp.ADD, app, sundayWindow, sundayWindow) is Outcome.Done)
        assertTrue(app in store.load()!!.nightAllowlist)

        // A wound-forward phone clock is not believed: the network says it is Monday.
        assertTrue(manager.editList(AppList.ALWAYS_BLOCKED, ListOp.REMOVE, app, sundayWindow, monday) is Outcome.Refused)

        // Closing the phone more works on a Monday.
        assertTrue(manager.editList(AppList.NIGHT_ALLOWED, ListOp.REMOVE, app, monday, monday) is Outcome.Done)
        assertTrue(manager.editList(AppList.ALWAYS_BLOCKED, ListOp.ADD, app, monday, monday) is Outcome.Done)
        assertTrue(dpm.isPackageSuspended(admin, app))

        // The phone's essentials cannot be listed at all.
        val launcher = snapshot.protectedPackages.first { it in snapshot.installed }
        assertTrue(manager.editList(AppList.ALWAYS_BLOCKED, ListOp.ADD, launcher, sundayWindow, sundayWindow) is Outcome.Refused)

        val kinds = events.read().map { it.kind }
        assertTrue(EventKind.DENIED in kinds && EventKind.LOOSENED in kinds && EventKind.TIGHTENED in kinds)
    }

    @Test
    fun e2_beforeProtectionStartsEverythingIsFree() {
        val app = anAppToSuspend()
        val manager = ChangeManager(context)
        assertTrue(manager.editList(AppList.NIGHT_ALLOWED, ListOp.ADD, app, monday, networkTime = null) is Outcome.Done)
        assertTrue(app in store.load()!!.nightAllowlist)
        assertTrue(manager.saveSettings(config.copy(nightStart = "21:30"), monday, networkTime = null) is Outcome.Done)
        assertEquals("21:30", store.load()!!.nightStart)
    }

    @Test
    fun e3_settingsChangesNeedTheWindowAfterProtectionStarts() {
        val manager = ChangeManager(context)
        store.protectionActive = true
        val changed = config.copy(nightStart = "21:30")
        assertTrue(manager.saveSettings(changed, monday, monday) is Outcome.Refused)
        assertEquals("22:00", store.load()!!.nightStart)
        assertTrue(manager.saveSettings(changed, sundayWindow, sundayWindow) is Outcome.Done)
        assertEquals("21:30", store.load()!!.nightStart)
        assertTrue("a broken value is refused even in the window", manager.saveSettings(changed.copy(dnsHost = "not a host"), sundayWindow, sundayWindow) is Outcome.Refused)
    }

    @Test
    fun f1_theFriendsCodeOpensAnEmergencyWindowOnce() {
        val manager = ChangeManager(context)
        val secret = manager.newFriendSecret()
        val now = Instant.now()
        val code = Totp.code(secret, Totp.counter(now))

        assertTrue(manager.redeemEmergency("000000".takeIf { it != code } ?: "000001", now) is Outcome.Refused)
        assertTrue(manager.redeemEmergency(code, now) is Outcome.Done)
        assertTrue(state.emergencyUntil!!.isAfter(now))
        assertTrue("a code works once", manager.redeemEmergency(code, now.plusSeconds(2)) is Outcome.Refused)

        val kinds = events.read().map { it.kind }
        assertTrue(EventKind.EMERGENCY_USED in kinds && EventKind.EMERGENCY_REFUSED in kinds)
    }

    @Test
    fun f2_anEmergencyWindowAllowsLoosening_evenOnAMondayAndOffline() {
        val manager = ChangeManager(context)
        val secret = manager.newFriendSecret()
        store.protectionActive = true
        val app = anAppToSuspend()
        assertTrue(manager.editList(AppList.NIGHT_ALLOWED, ListOp.ADD, app, monday, networkTime = null) is Outcome.Refused)

        state.emergencyUntil = monday.plusSeconds(3600)
        assertTrue(manager.editList(AppList.NIGHT_ALLOWED, ListOp.ADD, app, monday.plusSeconds(60), networkTime = null) is Outcome.Done)
        assertTrue(secret.isNotEmpty())
    }

    @Test
    fun f3_theEnrolmentCodeProvesTheSecretReachedTheFriend() {
        val manager = ChangeManager(context)
        val secret = manager.newFriendSecret()
        assertTrue(manager.hasFriendSecret)
        assertFalse(manager.verifyEnrolment("123456".takeIf { Totp.code(secret, Totp.counter(Instant.now())) != it } ?: "654321"))
        assertTrue(manager.verifyEnrolment(Totp.code(secret, Totp.counter(Instant.now()))))
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
    fun z_removingTheProtectionNeedsTheWindowAndTheCodeAndThenEndsDeviceOwnership() {
        val manager = ChangeManager(context)
        val secret = manager.newFriendSecret()
        manager.startProtection()
        val now = Instant.now()
        val code = Totp.code(secret, Totp.counter(now))

        // Outside the window, even the right code does nothing.
        assertTrue(manager.removeProtection(code, monday, monday) is Outcome.Refused)
        assertTrue(dpm.isDeviceOwnerApp(context.packageName))

        // In the window a wrong code does nothing.
        val wrong = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        assertTrue(manager.removeProtection(wrong, sundayWindow, sundayWindow) is Outcome.Refused)
        assertTrue(dpm.isDeviceOwnerApp(context.packageName))

        // Window and the right code together lift everything.
        val sundayCode = Totp.code(secret, Totp.counter(sundayWindow))
        // The emulator's clock is "now", so the code for the Sunday instant is checked against the instant given.
        val outcome = manager.removeProtection(sundayCode, sundayWindow, sundayWindow)
        assertTrue("removed: $outcome", outcome is Outcome.Done)
        assertFalse(dpm.isDeviceOwnerApp(context.packageName))
        assertFalse(store.protectionActive)
        assertFalse(users.hasUserRestriction(Restrictions.SAFE_BOOT))
        assertEquals("opportunistic", Settings.Global.getString(context.contentResolver, "private_dns_mode"))
    }
}
