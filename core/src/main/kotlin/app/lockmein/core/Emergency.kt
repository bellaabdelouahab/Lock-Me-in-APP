package app.lockmein.core

import java.time.Duration
import java.time.Instant

data class Redemption(val until: Instant, val counter: Long)

/** The friend's code opens a short window at any time. A code works once. */
class Emergency(private val secret: ByteArray, private val length: Duration = Duration.ofMinutes(60)) {
    /** @param lastUsedCounter the counter of the last accepted code, or null if none was ever accepted. */
    fun redeem(entered: String, now: Instant, lastUsedCounter: Long?): Redemption? {
        val counter = Totp.matchingCounter(secret, entered, now) ?: return null
        if (lastUsedCounter != null && counter <= lastUsedCounter) return null
        return Redemption(now.plus(length), counter)
    }
}
