package app.lockmein

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import androidx.test.platform.app.InstrumentationRegistry
import app.lockmein.core.BuildMode
import app.lockmein.core.Inventory
import app.lockmein.core.Phase
import app.lockmein.core.PolicyPlanner
import app.lockmein.core.Restrictions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Runs on a real emulator where the app has been made device owner (`dpm set-device-owner`, see the CI workflow).
 * It proves Android itself enforces what the planner decides, which the JVM tests cannot.
 */
class PolicyApplierTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    private val admin = ComponentName(context, AdminReceiver::class.java)
    private val snapshot = DeviceInventory(context).read()
    private val planner = PolicyPlanner(OwnerRules.forDevice(snapshot, context.packageName), BuildConfig.DNS_HOST)
    private val inventory = Inventory(snapshot.installed)

    @Test
    fun theAppIsDeviceOwner() {
        assertTrue("run `dpm set-device-owner app.lockmein/.AdminReceiver` first", dpm.isDeviceOwnerApp(context.packageName))
    }

    @Test
    fun restrictionsAreInForceAndTheTestBuildKeepsDebugging() {
        val report = PolicyApplier(context).apply(planner.plan(Phase.DAY, BuildMode.TEST, inventory))
        assertEquals(emptyList<String>(), report.failed)

        for (key in listOf(Restrictions.SAFE_BOOT, Restrictions.CONFIG_DATE_TIME, Restrictions.ADD_USER, Restrictions.CONFIG_VPN, Restrictions.CONFIG_PRIVATE_DNS)) {
            assertTrue("$key should be on", users.hasUserRestriction(key))
        }
        assertFalse("the test build must keep USB debugging", users.hasUserRestriction(Restrictions.DEBUGGING))
        assertFalse(users.hasUserRestriction(Restrictions.FACTORY_RESET))
    }

    @Test
    fun dnsIsPinnedToTheHostAndLocked() {
        PolicyApplier(context).apply(planner.plan(Phase.DAY, BuildMode.TEST, inventory))
        assertEquals(DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME, dpm.getGlobalPrivateDnsMode(admin))
        assertEquals(BuildConfig.DNS_HOST, dpm.getGlobalPrivateDnsHost(admin))
        assertTrue(users.hasUserRestriction(Restrictions.CONFIG_PRIVATE_DNS))
    }

    @Test
    fun theAppCannotBeUninstalledOrStopped() {
        PolicyApplier(context).apply(planner.plan(Phase.DAY, BuildMode.TEST, inventory))
        assertTrue(dpm.isUninstallBlocked(admin, context.packageName))
        assertTrue(context.packageName in dpm.getUserControlDisabledPackages(admin))
    }

    @Test
    fun nightSuspendsUserFacingAppsAndMorningReleasesThem() {
        val night = planner.plan(Phase.NIGHT, BuildMode.TEST, inventory)
        val victim = night.suspended.firstOrNull() ?: error("the emulator has no app the night plan would suspend")

        PolicyApplier(context).apply(night)
        assertTrue("$victim should be suspended at night", dpm.isPackageSuspended(admin, victim))

        PolicyApplier(context).apply(planner.plan(Phase.DAY, BuildMode.TEST, inventory))
        val stillSuspendedByDay = planner.plan(Phase.DAY, BuildMode.TEST, inventory).suspended
        assertEquals(victim in stillSuspendedByDay, dpm.isPackageSuspended(admin, victim))
    }

    @Test
    fun theRunnerFollowsTheClockAndNamesTheNextPass() {
        val zone = ZoneId.systemDefault()
        val night = LocalDate.of(2026, 11, 3).atTime(2, 0).atZone(zone).toInstant() // a Tuesday, deep night
        val noon = LocalDate.of(2026, 11, 3).atTime(12, 0).atZone(zone).toInstant()
        val victim = planner.plan(Phase.NIGHT, BuildMode.TEST, inventory).suspended.first()

        val atNight = PolicyRunner(context).run(night)
        assertTrue("the night pass should finish: ${atNight.report.failed}", atNight.report.ok)
        assertTrue(dpm.isPackageSuspended(admin, victim))
        assertTrue(atNight.nextRunAt.isAfter(night) && !atNight.nextRunAt.isAfter(night.plus(PolicyRunner.CHECK_EVERY)))

        val atNoon = PolicyRunner(context).run(noon)
        assertTrue(atNoon.nextRunAt.isAfter(noon))
        assertEquals(victim in planner.plan(Phase.DAY, BuildMode.TEST, inventory).suspended, dpm.isPackageSuspended(admin, victim))
    }

    @Test
    fun protectedPackagesAreNeverSuspended() {
        PolicyApplier(context).apply(planner.plan(Phase.NIGHT, BuildMode.TEST, inventory))
        val launcher = snapshot.protectedPackages.first { it in snapshot.installed }
        assertFalse(dpm.isPackageSuspended(admin, launcher))
        assertFalse(dpm.isPackageSuspended(admin, context.packageName))
    }
}
