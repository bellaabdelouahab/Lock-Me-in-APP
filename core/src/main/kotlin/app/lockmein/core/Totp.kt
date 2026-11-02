package app.lockmein.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 6238 time-based one-time codes (HMAC-SHA1, 30 s steps) for the friend's code. */
object Totp {
    const val STEP_SECONDS = 30L

    fun counter(at: Instant): Long = at.epochSecond / STEP_SECONDS

    fun code(secret: ByteArray, counter: Long, digits: Int = 6): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        var modulus = 1
        repeat(digits) { modulus *= 10 }
        return (binary % modulus).toString().padStart(digits, '0')
    }

    /**
     * The counter the [entered] code belongs to, within [drift] steps of [now], or null when it matches none.
     * The caller must remember the returned counter and refuse it a second time.
     */
    fun matchingCounter(secret: ByteArray, entered: String, now: Instant, drift: Int = 1, digits: Int = 6): Long? {
        if (entered.length != digits || !entered.all { it.isDigit() }) return null
        val current = counter(now)
        var found: Long? = null
        // Every candidate is compared, so timing does not reveal which one matched.
        for (c in (current - drift)..(current + drift)) {
            val same = MessageDigest.isEqual(code(secret, c, digits).toByteArray(), entered.toByteArray())
            if (same && found == null) found = c
        }
        return found
    }
}
