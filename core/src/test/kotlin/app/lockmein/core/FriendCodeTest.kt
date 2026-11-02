package app.lockmein.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FriendCodeTest {
    private val secret = "12345678901234567890".toByteArray()
    private val now = Instant.ofEpochSecond(1_700_000_010)
    private val checker = CodeChecker(secret)
    private val goodCode = Totp.code(secret, Totp.counter(now))

    @Test
    fun `a new secret is long, random and different each time`() {
        val a = FriendCode.newSecret()
        val b = FriendCode.newSecret()
        assertEquals(FriendCode.SECRET_BYTES, a.size)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `the authenticator link carries the secret and the settings a code needs`() {
        val uri = FriendCode.otpauthUri(secret)
        assertTrue(uri.startsWith("otpauth://totp/Lock%20Me%20In:Lock%20Me%20In?"))
        assertTrue("secret=${Base32.encode(secret)}" in uri)
        assertTrue("digits=6" in uri && "period=30" in uri && "algorithm=SHA1" in uri)
    }

    @Test
    fun `a secret from the link makes the same codes the app checks`() {
        val fromLink = Base32.decode(Regex("secret=([A-Z2-7]+)").find(FriendCode.otpauthUri(secret))!!.groupValues[1])!!
        assertEquals(goodCode, Totp.code(fromLink, Totp.counter(now)))
    }

    @Test
    fun `the right code is accepted once`() {
        val first = assertIs<CodeCheck.Accepted>(checker.check(goodCode, now, lastUsedCounter = null))
        assertEquals(CodeCheck.AlreadyUsed, checker.check(goodCode, now.plusSeconds(3), lastUsedCounter = first.counter))
    }

    @Test
    fun `a wrong or malformed code is refused`() {
        val wrong = ((goodCode.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        assertEquals(CodeCheck.Wrong, checker.check(wrong, now, null))
        assertEquals(CodeCheck.Wrong, checker.check("12", now, null))
        assertEquals(CodeCheck.Wrong, checker.check("", now, null))
    }

    @Test
    fun `removing the protection needs the window and the friend's code together`() {
        val open = Decision.Allowed("open window")
        val closed = Decision.Denied("changes apply in the open window")
        assertIs<Decision.Allowed>(Removal.decide(open, CodeCheck.Accepted(5)))
        assertIs<Decision.Denied>(Removal.decide(closed, CodeCheck.Accepted(5)))
        assertIs<Decision.Denied>(Removal.decide(open, CodeCheck.Wrong))
        assertIs<Decision.Denied>(Removal.decide(open, CodeCheck.AlreadyUsed))
        assertEquals(closed, Removal.decide(closed, CodeCheck.Wrong))
    }
}
