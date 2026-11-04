package app.lockmein

import android.content.Context
import app.lockmein.core.ChangeRequest
import app.lockmein.core.Requests
import java.time.Instant

/**
 * What the app remembers between runs, apart from the settings: the apps it already knew when protection started, the ones that arrived
 * since (held back until reviewed in the open window), the browsers approved, the requests that are waiting, and the apps the friend unblocked for an hour.
 */
class LockState(context: Context) {
    private val prefs = context.getSharedPreferences("lock_state", Context.MODE_PRIVATE)

    val approvedBrowsers: Set<String> get() = read(APPROVED)
    val quarantined: Set<String> get() = read(QUARANTINED)

    /** Apps the friend's code unblocked for an hour, and when each one ends. Expired ones are dropped on read. */
    fun unblocks(now: Instant): Map<String, Instant> = readUnblocks().filterValues { it.isAfter(now) }

    fun unblock(packageName: String, until: Instant) =
        prefs.edit().putStringSet(UNBLOCKS, (readUnblocks() + (packageName to until)).map { (pkg, at) -> "$pkg=${at.toEpochMilli()}" }.toSet()).apply()

    fun clearUnblocks() = prefs.edit().remove(UNBLOCKS).apply()

    private fun readUnblocks(): Map<String, Instant> =
        prefs.getStringSet(UNBLOCKS, emptySet()).orEmpty().associate { it.substringBefore('=') to Instant.ofEpochMilli(it.substringAfter('=').toLong()) }

    /** The counter of the last friend code that was accepted, so a code is never used twice. */
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

    /** Reviewed in the open window: the app is released. */
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
        const val UNBLOCKS = "temporary_unblocks"
        const val LAST_CODE = "last_code_counter"
    }
}
