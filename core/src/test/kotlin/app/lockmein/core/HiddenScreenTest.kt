package app.lockmein.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HiddenScreenTest {
    private fun thumb(black: Int) = ByteArray(FrameDiff.TOTAL_PIXELS) { i -> if (i < black) 0 else 200.toByte() }

    @Test
    fun aSecureWindowWithItsBarsAroundIsDarkEnoughToAsk() {
        // Status bar on top and navigation bar below stay drawn: about 2 rows of 16 are not black.
        assertTrue(HiddenScreen.mayBeHidden(thumb(224)))
        assertFalse(HiddenScreen.mayBeHidden(thumb(150)), "an ordinary dark page with text is not asked about")
    }

    @Test
    fun onOldAndroidOnlyALongFullyBlackBrowserCounts() {
        assertTrue(HiddenScreen.isBlack(thumb(250)))
        assertFalse(HiddenScreen.isBlack(thumb(224)))
        assertFalse(HiddenScreen.blackTooLong(null, 10_000, isBrowser = true))
        assertFalse(HiddenScreen.blackTooLong(5_000, 10_000, isBrowser = true), "a black video frame for 5 s is not a private tab")
        assertTrue(HiddenScreen.blackTooLong(4_000, 10_000, isBrowser = true))
        assertFalse(HiddenScreen.blackTooLong(0, 10_000, isBrowser = false), "only browsers on old Android")
    }

    @Test
    fun theWeeklyReportCountsPrivateTabsSentHome() {
        val now = java.time.Instant.parse("2026-11-23T10:00:00Z")
        val text = Reports.week(listOf(LockEvent(now.minusSeconds(60), EventKind.HIDDEN_SCREEN, "com.brave.browser")), now).toText(java.time.ZoneOffset.UTC)
        assertTrue("Private tabs sent home: 1" in text)
    }
}
