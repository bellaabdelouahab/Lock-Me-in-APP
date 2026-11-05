package app.lockmein.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolicyPlannerTest {
    private val me = "app.lockmein"
    private val brave = "com.brave.browser"
    private val chrome = "com.android.chrome"
    private val firefox = "org.mozilla.firefox"
    private val youtube = "com.google.android.youtube"
    private val music = "com.google.android.apps.youtube.music"
    private val phone = "com.android.dialer"
    private val launcher = "com.miui.home"
    private val audio = "org.local.audio"
    private val instagram = "com.instagram.android"
    private val bank = "ma.bank.app"

    private val rules = AppRules(
        appPackage = me,
        allowedBrowser = brave,
        browsers = setOf(brave, chrome, firefox),
        nightClosed = setOf(instagram, music),
        protectedPackages = setOf(phone, launcher, "com.android.systemui", "com.android.settings", "com.android.inputmethod"),
    )
    private val planner = PolicyPlanner(rules, "abc123.dns.nextdns.io")
    private val installed = setOf(me, brave, chrome, firefox, youtube, music, phone, launcher, audio, instagram, bank, "com.android.mms", "com.android.deskclock", "com.azure.authenticator")
    private val inventory = Inventory(installed)

    @Test
    fun `by day the other browsers and the YouTube app are suspended, social apps and Brave are not`() {
        val plan = planner.plan(Phase.DAY, BuildMode.SEALED, inventory)
        assertEquals(setOf(chrome, firefox, youtube), plan.suspended)
        assertTrue(plan.unsuspended.containsAll(setOf(brave, instagram, bank, music, audio)))
    }

    @Test
    fun `an approved browser is not suspended`() {
        val plan = planner.plan(Phase.DAY, BuildMode.SEALED, inventory.copy(approvedBrowsers = setOf(chrome)))
        assertEquals(setOf(firefox, youtube), plan.suspended)
    }

    @Test
    fun `at night only the apps picked to close are added, everything else keeps running`() {
        val plan = planner.plan(Phase.NIGHT, BuildMode.SEALED, inventory)
        assertEquals(setOf(chrome, firefox, youtube, music, instagram), plan.suspended)
        assertFalse(plan.suspended.any { it in setOf(brave, bank, audio) || it in rules.protectedPackages || it == me })
    }

    @Test
    fun `a night pick that is protected is left alone`() {
        val plan = PolicyPlanner(rules.copy(nightClosed = setOf(phone, launcher)), "abc123.dns.nextdns.io").plan(Phase.NIGHT, BuildMode.SEALED, inventory)
        assertFalse(phone in plan.suspended || launcher in plan.suspended)
    }

    @Test
    fun `a night pick runs by day and is suspended at night while the others stay`() {
        assertFalse(music in planner.plan(Phase.DAY, BuildMode.SEALED, inventory).suspended)
        val night = planner.plan(Phase.NIGHT, BuildMode.SEALED, inventory)
        assertTrue(music in night.suspended)
        assertFalse(audio in night.suspended)
    }

    @Test
    fun `YouTube is blocked in every phase, the open window included`() {
        for (phase in Phase.entries) assertTrue(youtube in planner.plan(phase, BuildMode.SEALED, inventory).suspended)
    }

    @Test
    fun `an app the friend unblocked for an hour is let through, and nothing else`() {
        val night = planner.plan(Phase.NIGHT, BuildMode.SEALED, inventory.copy(temporarilyAllowed = setOf(instagram)))
        assertFalse(instagram in night.suspended)
        assertTrue(bank in night.suspended)
        assertTrue(youtube in planner.plan(Phase.DAY, BuildMode.SEALED, inventory.copy(temporarilyAllowed = setOf(instagram))).suspended)
    }

    @Test
    fun `every browser the phone knows is closed unless allowed or approved, Brave and the Google app included`() {
        val google = "com.google.android.googlequicksearchbox"
        val more = Inventory(installed + google, approvedBrowsers = emptySet())
        val chromeAllowed = PolicyPlanner(rules.copy(allowedBrowser = chrome), "abc123.dns.nextdns.io")
        val plan = chromeAllowed.plan(Phase.DAY, BuildMode.SEALED, more)
        assertTrue(setOf(brave, firefox, google).all { it in plan.suspended })
        assertFalse(chrome in plan.suspended)
    }

    @Test
    fun `Chrome gets managed configuration when it is installed`() {
        assertTrue(planner.plan(Phase.DAY, BuildMode.SEALED, inventory).managedChrome)
        assertFalse(planner.plan(Phase.DAY, BuildMode.SEALED, Inventory(installed - chrome)).managedChrome)
    }

    @Test
    fun `other browsers stay suspended in the open window until the owner approves them`() {
        assertEquals(setOf(chrome, firefox, youtube), planner.plan(Phase.OPEN_WINDOW, BuildMode.SEALED, inventory).suspended)
    }

    @Test
    fun `a new app is suspended until reviewed`() {
        val withNew = Inventory(installed + "com.new.app", quarantined = setOf("com.new.app"))
        assertTrue("com.new.app" in planner.plan(Phase.DAY, BuildMode.SEALED, withNew).suspended)
        assertTrue("com.new.app" in planner.plan(Phase.OPEN_WINDOW, BuildMode.SEALED, withNew).suspended)
    }

    @Test
    fun `an app the owner blocked stays suspended in every phase`() {
        val blocking = PolicyPlanner(rules.copy(alwaysBlocked = setOf(instagram)), "abc123.dns.nextdns.io")
        for (phase in Phase.entries) {
            assertTrue(instagram in blocking.plan(phase, BuildMode.SEALED, inventory).suspended, "phase $phase")
        }
    }

    @Test
    fun `a blocked app that is protected or not installed is left alone`() {
        val blocking = PolicyPlanner(rules.copy(alwaysBlocked = setOf(phone, "not.installed")), "abc123.dns.nextdns.io")
        val plan = blocking.plan(Phase.DAY, BuildMode.SEALED, inventory)
        assertFalse(phone in plan.suspended)
        assertFalse("not.installed" in plan.suspended)
    }

    @Test
    fun `an app added to the night list is closed at night`() {
        val more = PolicyPlanner(rules.copy(nightClosed = rules.nightClosed + bank), "abc123.dns.nextdns.io")
        assertTrue(bank in more.plan(Phase.NIGHT, BuildMode.SEALED, inventory).suspended)
        assertFalse(bank in planner.plan(Phase.NIGHT, BuildMode.SEALED, inventory).suspended)
    }

    @Test
    fun `the phone, the launcher, settings and this app are never suspended`() {
        for (phase in Phase.entries) {
            val suspended = planner.plan(phase, BuildMode.SEALED, inventory).suspended
            assertFalse(suspended.any { it in rules.protectedPackages || it == me }, "phase $phase")
        }
    }

    @Test
    fun `everything installed is either suspended or released, never both and never skipped`() {
        for (phase in Phase.entries) {
            val plan = planner.plan(phase, BuildMode.SEALED, inventory)
            assertTrue(plan.suspended.intersect(plan.unsuspended).isEmpty(), "phase $phase")
            val managed = inventory.installed - rules.protectedPackages - me
            assertEquals(managed, plan.suspended + plan.unsuspended, "phase $phase")
        }
    }

    @Test
    fun `packages that are not installed are left out of the plan`() {
        val plan = planner.plan(Phase.DAY, BuildMode.SEALED, Inventory(setOf(me, brave)))
        assertTrue(plan.suspended.isEmpty())
    }

    @Test
    fun `the test build keeps debugging and the factory reset, the sealed build closes them`() {
        val test = planner.plan(Phase.DAY, BuildMode.TEST, inventory).restrictions
        val sealed = planner.plan(Phase.DAY, BuildMode.SEALED, inventory).restrictions
        assertFalse(Restrictions.DEBUGGING in test)
        assertFalse(Restrictions.FACTORY_RESET in test)
        assertTrue(Restrictions.DEBUGGING in sealed && Restrictions.FACTORY_RESET in sealed)
        assertTrue(test.all { it in sealed })
    }

    @Test
    fun `both builds lock DNS, protect the app and fix the DNS host`() {
        for (mode in BuildMode.entries) {
            val plan = planner.plan(Phase.DAY, mode, inventory)
            assertTrue(Restrictions.CONFIG_PRIVATE_DNS in plan.restrictions)
            assertTrue(Restrictions.CONFIG_DATE_TIME in plan.restrictions)
            assertEquals(setOf(me), plan.uninstallBlocked)
            assertEquals(setOf(me), plan.userControlDisabled)
            assertEquals("abc123.dns.nextdns.io", plan.privateDnsHost)
        }
    }
}
