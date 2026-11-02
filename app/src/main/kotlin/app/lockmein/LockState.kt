package app.lockmein

import android.content.Context
import java.time.Instant

/**
 * What the app remembers between runs: the apps it already knew when it started, the ones that arrived since (quarantined until
 * the owner reviews them in the open window), and the browsers whose request was approved.
 */
class LockState(context: Context) {
    private val prefs = context.getSharedPreferences("lock_state", Context.MODE_PRIVATE)

    val approvedBrowsers: Set<String> get() = read(APPROVED)
    val quarantined: Set<String> get() = read(QUARANTINED)

    /** Marks packages that appeared since the last run. The first run records everything installed as known, so nothing existing is quarantined. */
    fun noteInstalled(installed: Set<String>) {
        val known = prefs.getStringSet(KNOWN, null)
        if (known == null) {
            prefs.edit().putStringSet(KNOWN, installed).apply()
            return
        }
        val arrived = installed - known
        if (arrived.isNotEmpty()) {
            prefs.edit()
                .putStringSet(KNOWN, known + arrived)
                .putStringSet(QUARANTINED, quarantined + arrived)
                .apply()
        }
    }

    /** Browsers the owner asked for and when (the wait runs from the first request). */
    var browserRequests: Map<String, Instant>
        get() = prefs.getStringSet(REQUESTS, emptySet()).orEmpty().associate { it.substringBefore('=') to Instant.ofEpochMilli(it.substringAfter('=').toLong()) }
        set(value) = prefs.edit().putStringSet(REQUESTS, value.map { (pkg, at) -> "$pkg=${at.toEpochMilli()}" }.toSet()).apply()

    fun approveBrowser(packageName: String) = prefs.edit().putStringSet(APPROVED, approvedBrowsers + packageName).apply()

    /** Reviewed in the open window: the app is released. */
    fun release(packageName: String) = prefs.edit().putStringSet(QUARANTINED, quarantined - packageName).apply()

    private fun read(key: String): Set<String> = prefs.getStringSet(key, emptySet()).orEmpty().toSet()

    private companion object {
        const val KNOWN = "known"
        const val QUARANTINED = "quarantined"
        const val APPROVED = "approved_browsers"
        const val REQUESTS = "browser_requests"
    }
}
