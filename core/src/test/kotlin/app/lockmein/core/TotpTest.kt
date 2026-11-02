package app.lockmein.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TotpTest {
    // RFC 6238 appendix B, SHA-1, secret "12345678901234567890".
    private val secret = "12345678901234567890".toByteArray()

    @Test
    fun `matches the RFC 6238 test vectors`() {
        assertEquals("94287082", Totp.code(secret, Totp.counter(Instant.ofEpochSecond(59)), digits = 8))
        assertEquals("07081804", Totp.code(secret, Totp.counter(Instant.ofEpochSecond(1111111109)), digits = 8))
        assertEquals("14050471", Totp.code(secret, Totp.counter(Instant.ofEpochSecond(1111111111)), digits = 8))
        assertEquals("89005924", Totp.code(secret, Totp.counter(Instant.ofEpochSecond(1234567890)), digits = 8))
    }

    @Test
    fun `six digit codes are the last six of the eight digit ones`() {
        assertEquals("287082", Totp.code(secret, Totp.counter(Instant.ofEpochSecond(59))))
    }

    @Test
    fun `accepts the current code and one step of drift either way`() {
        val now = Instant.ofEpochSecond(1_700_000_010)
        val current = Totp.counter(now)
        assertEquals(current, Totp.matchingCounter(secret, Totp.code(secret, current), now))
        assertEquals(current - 1, Totp.matchingCounter(secret, Totp.code(secret, current - 1), now))
        assertEquals(current + 1, Totp.matchingCounter(secret, Totp.code(secret, current + 1), now))
    }

    @Test
    fun `refuses an old code, a wrong code and malformed input`() {
        val now = Instant.ofEpochSecond(1_700_000_010)
        val current = Totp.counter(now)
        assertNull(Totp.matchingCounter(secret, Totp.code(secret, current - 2), now))
        val right = Totp.code(secret, current)
        val wrong = ((right.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        assertNull(Totp.matchingCounter(secret, wrong, now))
        assertNull(Totp.matchingCounter(secret, "12345", now))
        assertNull(Totp.matchingCounter(secret, "12345a", now))
        assertNull(Totp.matchingCounter(secret, "", now))
    }

    @Test
    fun `a code made with another secret is refused`() {
        val now = Instant.ofEpochSecond(1_700_000_010)
        val other = "other secret bytes!!".toByteArray()
        assertNull(Totp.matchingCounter(secret, Totp.code(other, Totp.counter(now)), now))
        assertNotNull(Totp.matchingCounter(other, Totp.code(other, Totp.counter(now)), now))
    }
}
