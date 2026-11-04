package app.lockmein.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrustedTimeTest {
    private val anchor = TimeAnchor(networkMs = 1_000_000L, elapsedMs = 5_000L, boot = 3)

    @Test
    fun countsFromTheNetworkTimeWhateverThePhoneClockSays() {
        assertEquals(1_060_000L, TrustedTime.at(anchor, elapsedMs = 65_000L, boot = 3))
    }

    @Test
    fun anotherBootOrACounterThatWentBackIsNotTrusted() {
        assertNull(TrustedTime.at(anchor, elapsedMs = 65_000L, boot = 4))
        assertNull(TrustedTime.at(anchor, elapsedMs = 1_000L, boot = 3))
        assertNull(TrustedTime.at(null, elapsedMs = 1_000L, boot = 3))
    }

    @Test
    fun anOldAnchorIsRenewed() {
        val sixHours = 6 * 3_600_000L
        assertFalse(TrustedTime.stale(anchor, elapsedMs = 5_000L + sixHours - 1, boot = 3, maxAgeMs = sixHours))
        assertTrue(TrustedTime.stale(anchor, elapsedMs = 5_000L + sixHours + 1, boot = 3, maxAgeMs = sixHours))
        assertTrue(TrustedTime.stale(anchor, elapsedMs = 6_000L, boot = 4, maxAgeMs = sixHours))
    }
}
