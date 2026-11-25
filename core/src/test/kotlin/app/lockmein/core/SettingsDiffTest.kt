package app.lockmein.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsDiffTest {
    private val now = LockConfig(dnsHost = "dns.example")

    @Test
    fun aSettingsRequestNamesEachFieldWithItsOldAndNewValue() {
        val asked = now.copy(nightStart = "23:00", openSecuredBrowsers = true)
        val changes = SettingsDiff.changes(now, asked)
        assertEquals(
            listOf(SettingChange("openSecuredBrowsers", "false", "true"), SettingChange("nightStart", "22:00", "23:00")),
            changes,
        )
        assertEquals("openSecuredBrowsers: false to true; nightStart: 22:00 to 23:00", SettingsDiff.summary(changes))
    }

    @Test
    fun listsSayWhatIsDroppedAndAdded() {
        val asked = now.copy(watchedApps = now.watchedApps - "com.brave.browser" + "org.example")
        val c = SettingsDiff.changes(now, asked).single()
        assertEquals("watchedApps", c.field)
        assertEquals("drops com.brave.browser", c.before)
        assertEquals("adds org.example", c.after)
    }

    @Test
    fun aTighteningMadeSinceShowsAsSomethingApprovalWouldUndo() {
        val asked = now.copy(nightStart = "23:00")
        val tightenedSince = now.copy(filterSexyThreshold = 0.6f)
        val changes = SettingsDiff.changes(tightenedSince, asked)
        assertTrue(changes.any { it.field == "filterSexyThreshold" && it.before == "0.6" })
        assertEquals("nothing (it matches the settings in force)", SettingsDiff.summary(SettingsDiff.changes(now, now)))
    }

    @Test
    fun aMapChangeIsNamedByItsKey() {
        val asked = now.copy(filterAppOffsets = mapOf("com.brave.browser" to -0.1f))
        assertEquals(listOf(SettingChange("filterAppOffsets.com.brave.browser", "none", "-0.1")), SettingsDiff.changes(now, asked))
    }
}
