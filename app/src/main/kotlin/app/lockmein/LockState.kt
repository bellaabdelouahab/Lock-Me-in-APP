package app.lockmein

import android.content.Context
import java.time.Instant

/**
 * What the app remembers between runs, apart from the settings: the apps it already knew when protection started, the ones that arrived
 * since (held back until reviewed in the open window), the browsers asked for and approved, and the friend's emergency window.
 */
class LockState(context: Context) {
    private val prefs = context.getSharedPreferences("lock_state", Context.MODE_PRIVATE)

    val approvedBrowsers: Set<String> get() = read(APPROVED)
    val quarantined: Set<String> get() = read(QUARANTINED)

    /** End of the friend's emergency window, if one is open. */
    var emergencyUntil: Instant?
        get() = prefs.getLong(EMERGENCY, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        set(value) = prefs.edit().putLong(EMERGENCY, value?.toEpochMilli() ?: 0L).apply()

    /** The counter of the last friend code that was accepted, so a code is never used twice. */
    var lastCodeCounter: Long?
        get() = if (prefs.contains(LAST_CODE)) prefs.getLong(LAST_CODE, 0L) else null
        set(value) = prefs.edit().apply { if (value == null) remove(LAST_CODE) else putLong(LAST_CODE, value) }.apply()

    /** Browsers the owner asked for and when (the wait runs from the first request). */
    var browserRequests: Map<String, Instant>
        get() = prefs.getStringSet(REQUESTS, emptySet()).orEmpty().associate { it.substringBefore('=') to Instant.ofEpochMilli(it.substringAfter('=').toLong()) }
        set(value) = prefs.edit().putStringSet(REQUESTS, value.map { (pkg, at) -> "$pkg=${at.toEpochMilli()}" }.toSet()).apply()

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
    fun release(packageName: String) = prefs.edit().putStringSet(QUARANTINED, quarantined - packageName).apply()

    /** Forgets everything, for a fresh start after the protection is removed. */
    fun clear() = prefs.edit().clear().apply()

    private fun read(key: String): Set<String> = prefs.getStringSet(key, emptySet()).orEmpty().toSet()

    private companion object {
        const val KNOWN = "known"
        const val QUARANTINED = "quarantined"
        const val APPROVED = "approved_browsers"
        const val REQUESTS = "browser_requests"
        const val EMERGENCY = "emergency_until"
        const val LAST_CODE = "last_code_counter"
    }
}
