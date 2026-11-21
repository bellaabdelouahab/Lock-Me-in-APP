package app.lockmein.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilterWatchTest {
    private val s = 1_000L

    private fun working(at: Long = 0L) = FilterWatch(startedAt = at, wasDown = false)

    @Test
    fun `switched off in Accessibility closes the watched apps at once, even with fresh heartbeats`() {
        val w = working()
        w.beat(1 * s)
        assertTrue(w.check(2 * s, listed = true))
        assertFalse(w.check(2 * s + 1, listed = false))
    }

    @Test
    fun `a filter that stops answering is off after 15 seconds of silence`() {
        val w = working()
        w.beat(5 * s)
        assertTrue(w.check(20 * s, listed = true))
        assertFalse(w.check(20 * s + 1, listed = true))
    }

    @Test
    fun `heartbeats every 5 seconds keep it on`() {
        val w = working()
        for (t in 5..120 step 5) {
            w.beat(t * s)
            assertTrue(w.check(t * s + 4 * s, listed = true))
        }
    }

    @Test
    fun `just after the app starts the filter gets 15 seconds for its first heartbeat`() {
        val w = working(at = 100 * s)
        assertTrue(w.check(110 * s, listed = true))
        assertFalse(w.check(116 * s, listed = true))
    }

    @Test
    fun `back on, it reopens only after two new heartbeats`() {
        val w = working()
        assertFalse(w.check(1 * s, listed = false))
        assertFalse(w.check(2 * s, listed = true), "listed again but no heartbeat yet")
        w.beat(3 * s)
        assertFalse(w.check(4 * s, listed = true), "one heartbeat is not enough")
        w.beat(8 * s)
        assertTrue(w.check(9 * s, listed = true))
    }

    @Test
    fun `heartbeats sent while it was switched off do not count`() {
        val w = working()
        assertFalse(w.check(1 * s, listed = false))
        w.beat(2 * s)
        w.beat(3 * s)
        assertFalse(w.check(4 * s, listed = false))
        assertFalse(w.check(5 * s, listed = true), "the earlier beats were reset")
        w.beat(6 * s)
        assertFalse(w.check(7 * s, listed = true))
        w.beat(11 * s)
        assertTrue(w.check(12 * s, listed = true))
    }

    @Test
    fun `a phone that booted with the filter off starts closed and stays closed without heartbeats`() {
        val w = FilterWatch(startedAt = 0L, wasDown = true)
        assertFalse(w.check(1 * s, listed = true))
        assertFalse(w.check(60 * s, listed = true))
        w.beat(61 * s)
        w.beat(66 * s)
        assertTrue(w.check(67 * s, listed = true))
    }

    @Test
    fun `a silent filter that answers again reopens after two heartbeats`() {
        val w = working()
        assertFalse(w.check(16 * s, listed = true))
        w.beat(17 * s)
        assertFalse(w.check(18 * s, listed = true))
        w.beat(22 * s)
        assertTrue(w.check(23 * s, listed = true))
    }

    @Test
    fun `the service is found in Android's list by its full or short name, and only when Accessibility is on`() {
        val pkg = "app.lockmein"
        val cls = "app.lockmein.FilterService"
        assertTrue(FilterWatch.isListed(true, "app.lockmein/.FilterService", pkg, cls))
        assertTrue(FilterWatch.isListed(true, "com.other/.A:app.lockmein/app.lockmein.FilterService", pkg, cls))
        assertFalse(FilterWatch.isListed(false, "app.lockmein/.FilterService", pkg, cls))
        assertFalse(FilterWatch.isListed(true, "", pkg, cls))
        assertFalse(FilterWatch.isListed(true, null, pkg, cls))
        assertFalse(FilterWatch.isListed(true, "app.lockmein.fake/.FilterService", pkg, cls), "another app's service with a similar name")
    }
}
