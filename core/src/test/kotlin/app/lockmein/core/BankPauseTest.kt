package app.lockmein.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BankPauseTest {
    private val pause = BankPause("bank", 1_000, auto = true)

    @Test
    fun itEndsWhenTheBankAppLeavesTheScreenGoesOffOrAfterTenMinutes() {
        assertNull(BankPause.endReason(pause, 2_000, "bank", screenOff = false))
        assertNull(BankPause.endReason(pause, 2_000, null, screenOff = false), "not knowing what is in front keeps it until the cap")
        assertEquals(BankPause.LEFT, BankPause.endReason(pause, 2_000, "chat", screenOff = false))
        assertEquals(BankPause.SCREEN_OFF, BankPause.endReason(pause, 2_000, "bank", screenOff = true))
        assertEquals(BankPause.CAP, BankPause.endReason(pause, 1_000 + BankPause.CAP_MS, "bank", screenOff = false))
        assertEquals(pause, BankPause.decode(pause.encode()))
        assertNull(BankPause.decode("garbage"))
    }

    @Test
    fun theThreeBankAppsAreTheDefaultAndOnlyRemovingOneIsInstant() {
        val old = LockConfig(dnsHost = "x")
        assertEquals(setOf("com.attijariwafabank.lbankalik", "com.bankassafaa.main", "com.b3g.cih.online"), old.filterPauseApps)
        assertTrue(FilterTighten.onlyTightens(old, old.copy(filterPauseApps = old.filterPauseApps - "com.bankassafaa.main")))
        assertFalse(FilterTighten.onlyTightens(old, old.copy(filterPauseApps = old.filterPauseApps + "com.whatsapp")))
    }

    @Test
    fun theWeeklyReportListsEachPause() {
        val now = java.time.Instant.parse("2026-11-29T12:00:00Z")
        val events = listOf(
            LockEvent(now.minusSeconds(3600), EventKind.BANK_PAUSE_STARTED, "Simple"),
            LockEvent(now.minusSeconds(3400), EventKind.BANK_PAUSE_ENDED, "Simple, 3 min, left the app"),
        )
        val text = Reports.week(events, now).toText(java.time.ZoneId.of("UTC"))
        assertTrue(text.contains("Screen filter paused for a bank app: 1 times") && text.contains("Simple, 3 min, left the app"), text)
    }
}
