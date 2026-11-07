package app.lockmein.core

import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Duration
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
    data class LockedOut(val until: Instant) : CodeCheck
}

/** Checks a typed code and never accepts the same one twice, with 5 wrong attempts causing a 30-minute lockout. */
class CodeChecker(private val secret: ByteArray) {
    fun check(
        entered: String,
        now: Instant,
        lastUsedCounter: Long?,
        failedAttempts: Int = 0,
        lockoutUntil: Instant? = null,
    ): CodeCheck {
        if (lockoutUntil != null && now < lockoutUntil) {
            return CodeCheck.LockedOut(lockoutUntil)
        }
        val counter = Totp.matchingCounter(secret, entered, now)
        if (counter == null) {
            val nextFailed = failedAttempts + 1
            return if (nextFailed >= MAX_FAILED_ATTEMPTS) {
                CodeCheck.LockedOut(now.plus(LOCKOUT_DURATION))
            } else {
                CodeCheck.Wrong
            }
        }
        if (lastUsedCounter != null && counter <= lastUsedCounter) {
            return CodeCheck.AlreadyUsed
        }
        return CodeCheck.Accepted(counter)
    }

    companion object {
        const val MAX_FAILED_ATTEMPTS = 5
        val LOCKOUT_DURATION: Duration = Duration.ofMinutes(30)
    }
}

/** The 6 possible actions unlocked by one valid friend code. */
sealed interface FriendAction {
    data class OpenAppForHour(val packageName: String) : FriendAction
    data class ApproveRequest(val requestId: String) : FriendAction
    data class StopBlockingApp(val list: AppList, val packageName: String) : FriendAction
    data class ReleaseApp(val packageName: String) : FriendAction
    data class ApproveBrowser(val packageName: String) : FriendAction
    data object RedoSetup : FriendAction
}

/**
 * Manages the single-action authorization: once a code is validated, exactly one action can be applied without asking for a second code.
 */
class FriendCodeAuthorizer(private val checker: CodeChecker) {
    sealed interface AuthResult {
        data class Authorized(val token: FriendAuthToken) : AuthResult
        data class Denied(val check: CodeCheck) : AuthResult
    }

    data class FriendAuthToken(val counter: Long, var consumed: Boolean = false)

    fun authorize(
        code: String,
        now: Instant,
        lastUsedCounter: Long?,
        failedAttempts: Int = 0,
        lockoutUntil: Instant? = null,
    ): Pair<AuthResult, CodeCheck> {
        val check = checker.check(code, now, lastUsedCounter, failedAttempts, lockoutUntil)
        return when (check) {
            is CodeCheck.Accepted -> AuthResult.Authorized(FriendAuthToken(check.counter)) to check
            else -> AuthResult.Denied(check) to check
        }
    }

    fun applyAction(token: FriendAuthToken, action: FriendAction): Decision {
        if (token.consumed) return Decision.Denied("This code was already used for an action")
        token.consumed = true
        return Decision.Allowed("Action applied with friend code: $action")
    }

    fun waste(token: FriendAuthToken): Decision {
        if (token.consumed) return Decision.Denied("Already consumed")
        token.consumed = true
        return Decision.Denied("Code wasted without choosing an action")
    }
}

/** Taking the protection off is the biggest loosening: it needs the open window (or an emergency) AND the friend's code. */
object Removal {
    fun decide(gate: Decision, code: CodeCheck): Decision = when {
        gate is Decision.Denied -> gate
        code is CodeCheck.Wrong -> Decision.Denied("the friend's code is wrong")
        code is CodeCheck.AlreadyUsed -> Decision.Denied("that code was already used; wait for the next one")
        code is CodeCheck.LockedOut -> Decision.Denied("too many wrong codes: locked out until ${code.until}")
        else -> Decision.Allowed("window and friend's code")
    }
}
