package app.lockmein

import android.content.Context
import app.lockmein.core.AppList
import app.lockmein.core.ChangeRequest
import app.lockmein.core.CodeChecker
import app.lockmein.core.Requests
import app.lockmein.core.ScheduledRemoval
import app.lockmein.core.ScheduledRemovals
import java.time.Instant

/**
 * What the app remembers between runs, apart from the settings: the apps it already knew when protection started, the ones that arrived
 * since (held back until reviewed in the open window), the browsers approved, the requests that are waiting, the holder code session,
 * and scheduled removals.
 */
class LockState(context: Context) {
    private val prefs = context.getSharedPreferences("lock_state", Context.MODE_PRIVATE)

    val approvedBrowsers: Set<String> get() = read(APPROVED)
    val quarantined: Set<String> get() = read(QUARANTINED)

    /** Holder code 15-minute change session */
    var sessionUntil: Instant?
        get() = if (prefs.contains(SESSION_UNTIL)) Instant.ofEpochMilli(prefs.getLong(SESSION_UNTIL, 0L)) else null
        set(value) = prefs.edit().apply {
            if (value == null) remove(SESSION_UNTIL) else putLong(SESSION_UNTIL, value.toEpochMilli())
        }.apply()

    fun inSession(now: Instant): Boolean = sessionUntil?.isAfter(now) == true

    fun startSession(now: Instant) {
        sessionUntil = now.plus(CodeChecker.SESSION_DURATION)
        failedCodeAttempts = 0
        lockoutUntil = null
    }

    fun endSession() {
        sessionUntil = null
    }

    /** Failed code attempts counter for 5-wrong-codes lockout */
    var failedCodeAttempts: Int
        get() = prefs.getInt(FAILED_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(FAILED_ATTEMPTS, value).apply()

    var lockoutUntil: Instant?
        get() = if (prefs.contains(LOCKOUT_UNTIL)) Instant.ofEpochMilli(prefs.getLong(LOCKOUT_UNTIL, 0L)) else null
        set(value) = prefs.edit().apply {
            if (value == null) remove(LOCKOUT_UNTIL) else putLong(LOCKOUT_UNTIL, value.toEpochMilli())
        }.apply()

    fun isLockedOut(now: Instant): Boolean = lockoutUntil?.isAfter(now) == true

    /** Scheduled removals of apps (due 7 days later) */
    var scheduledRemovals: List<ScheduledRemoval>
        get() = ScheduledRemovals.decode(prefs.getString(SCHEDULED_REMOVALS, "[]").orEmpty())
        set(value) = prefs.edit().putString(SCHEDULED_REMOVALS, ScheduledRemovals.encode(value)).apply()

    fun scheduleRemoval(list: AppList, packageName: String, now: Instant) {
        val newRemoval = ScheduledRemovals.schedule(list, packageName, now)
        scheduledRemovals = scheduledRemovals.filter { it.id != newRemoval.id } + newRemoval
    }

    fun cancelRemoval(id: String) {
        scheduledRemovals = ScheduledRemovals.cancel(scheduledRemovals, id)
    }

    /** Legacy unblocks: empty map */
    fun unblocks(now: Instant): Map<String, Instant> = emptyMap()
    fun unblock(packageName: String, until: Instant) = Unit
    fun clearUnblocks() = Unit

    /** The counter of the last holder code that was accepted, so a code is never used twice. */
    var lastCodeCounter: Long?
        get() = if (prefs.contains(LAST_CODE)) prefs.getLong(LAST_CODE, 0L) else null
        set(value) = prefs.edit().apply { if (value == null) remove(LAST_CODE) else putLong(LAST_CODE, value) }.apply()

    /** Everything that opens the phone up and is waiting for its time. */
    var requests: List<ChangeRequest>
        get() = Requests.decode(prefs.getString(REQUESTS, "[]").orEmpty())
        set(value) = prefs.edit().putString(REQUESTS, Requests.encode(value)).apply()

    /**
     * Records the apps installed now and returns the ones that appeared since the last call. The first call records everything as known,
     * so nothing that was already there is held back.
     */
    fun noteInstalled(installed: Set<String>): Set<String> {
        val known = prefs.getStringSet(KNOWN, null)
        if (known == null) {
            prefs.edit().putStringSet(KNOWN, installed).apply()
            return emptySet()
        }
        val arrived = installed - known
        if (arrived.isNotEmpty()) {
            prefs.edit()
                .putStringSet(KNOWN, known + arrived)
                .putStringSet(QUARANTINED, quarantined + arrived)
                .apply()
        }
        return arrived
    }

    fun approveBrowser(packageName: String) = prefs.edit().putStringSet(APPROVED, approvedBrowsers + packageName).apply()

    /** System apps Android refuses to let any app suspend (on Xiaomi: Security, the Play Store). Left alone, reported in the check. */
    val unsuspendable: Set<String> get() = read(UNSUSPENDABLE)
    fun noteUnsuspendable(packages: Collection<String>) {
        if (packages.isNotEmpty()) prefs.edit().putStringSet(UNSUSPENDABLE, unsuspendable + packages).apply()
    }

    fun release(packageName: String) = prefs.edit().putStringSet(QUARANTINED, quarantined - packageName).apply()

    /** Forgets everything, for a fresh start after the protection is removed. */
    fun clear() = prefs.edit().clear().apply()

    private fun read(key: String): Set<String> = prefs.getStringSet(key, emptySet()).orEmpty().toSet()

    private companion object {
        const val KNOWN = "known"
        const val QUARANTINED = "quarantined"
        const val UNSUSPENDABLE = "unsuspendable"
        const val APPROVED = "approved_browsers"
        const val REQUESTS = "change_requests"
        const val LAST_CODE = "last_code_counter"
        const val SESSION_UNTIL = "session_until"
        const val FAILED_ATTEMPTS = "failed_code_attempts"
        const val LOCKOUT_UNTIL = "lockout_until"
        const val SCHEDULED_REMOVALS = "scheduled_removals"
    }
}
