package app.lockmein.core

import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Instant

/** The friend's code: a secret made once, kept out of the owner's reach, and shared with the friend as an authenticator entry. */
object FriendCode {
    const val SECRET_BYTES = 20

    fun newSecret(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(SECRET_BYTES).also(random::nextBytes)

    /** The `otpauth://` text an authenticator app imports (as a link, or inside a QR code). */
    fun otpauthUri(secret: ByteArray, account: String = "Lock Me In", issuer: String = "Lock Me In"): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return "otpauth://totp/${enc(issuer)}:${enc(account)}?secret=${Base32.encode(secret)}&issuer=${enc(issuer)}&algorithm=SHA1&digits=6&period=${Totp.STEP_SECONDS}"
    }
}

/** What the friend's code can unlock. */
sealed interface CodeCheck {
    data class Accepted(val counter: Long) : CodeCheck
    data object Wrong : CodeCheck
    data object AlreadyUsed : CodeCheck
}

/** Checks a typed code and never accepts the same one twice. */
class CodeChecker(private val secret: ByteArray) {
    fun check(entered: String, now: Instant, lastUsedCounter: Long?): CodeCheck {
        val counter = Totp.matchingCounter(secret, entered, now) ?: return CodeCheck.Wrong
        if (lastUsedCounter != null && counter <= lastUsedCounter) return CodeCheck.AlreadyUsed
        return CodeCheck.Accepted(counter)
    }
}

/** Taking the protection off is the biggest loosening: it needs the open window (or an emergency) AND the friend's code. */
object Removal {
    fun decide(gate: Decision, code: CodeCheck): Decision = when {
        gate is Decision.Denied -> gate
        code is CodeCheck.Wrong -> Decision.Denied("the friend's code is wrong")
        code is CodeCheck.AlreadyUsed -> Decision.Denied("that code was already used; wait for the next one")
        else -> Decision.Allowed("window and friend's code")
    }
}
