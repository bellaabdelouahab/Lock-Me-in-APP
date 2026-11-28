package app.lockmein

import android.content.Context
import app.lockmein.core.AppList
import app.lockmein.core.ChangeRequest
import app.lockmein.core.Requests
import app.lockmein.core.RequestResolution
import app.lockmein.core.ResolvedRequest
import java.time.Instant

/**
 * What the app remembers between runs, apart from the settings: the apps it already knew when protection started, the ones that arrived
 * since (quarantined until reviewed or released), the browsers approved, the requests that are waiting, request history, and temporary unblocks.
 */
class LockState(context: Context) {
    private val prefs = context.getSharedPreferences("lock_state", Context.MODE_PRIVATE)

    val approvedBrowsers: Set<String> get() = read(APPROVED)
    val quarantined: Set<String> get() = read(QUARANTINED)

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

    /** Apps the friend's code unblocked for an hour, and when each one ends. Expired ones are dropped on read. */
    fun unblocks(now: Instant): Map<String, Instant> = readUnblocks().filterValues { it.isAfter(now) }

    fun unblock(packageName: String, until: Instant) =
        prefs.edit().putStringSet(UNBLOCKS, (readUnblocks() + (packageName to until)).map { (pkg, at) -> "$pkg=${at.toEpochMilli()}" }.toSet()).apply()

    fun clearUnblocks() = prefs.edit().remove(UNBLOCKS).apply()

    private fun readUnblocks(): Map<String, Instant> =
        prefs.getStringSet(UNBLOCKS, emptySet()).orEmpty().associate { it.substringBefore('=') to Instant.ofEpochMilli(it.substringAfter('=').toLong()) }

    /** Apps suspended by the screen filter (for 30 minutes), and when each block ends. */
    fun filterBlocked(now: Instant): Map<String, Instant> = readFilterBlocked().filterValues { it.isAfter(now) }

    fun blockWithFilter(packageName: String, until: Instant) =
        prefs.edit().putStringSet(FILTER_BLOCKED, (readFilterBlocked() + (packageName to until)).map { (pkg, at) -> "$pkg=${at.toEpochMilli()}" }.toSet()).apply()

    fun clearFilterBlocked() = prefs.edit().remove(FILTER_BLOCKED).apply()

    fun clearFilterBlocked(packageName: String) = prefs.edit().putStringSet(
        FILTER_BLOCKED,
        readFilterBlocked().filterKeys { it != packageName }.map { "${it.key}=${it.value.toEpochMilli()}" }.toSet()
    ).apply()

    private fun readFilterBlocked(): Map<String, Instant> =
        prefs.getStringSet(FILTER_BLOCKED, emptySet()).orEmpty().associate { it.substringBefore('=') to Instant.ofEpochMilli(it.substringAfter('=').toLong()) }

    /** Last time the screen filter performed a check. */
    var lastFilterCheckMillis: Long
        get() = prefs.getLong(LAST_FILTER_CHECK, 0L)
        set(value) = prefs.edit().putLong(LAST_FILTER_CHECK, value).apply()

    /**
     * The screen filter was last seen off or silent (FilterWatcher), so watched apps stay closed. Kept across restarts: a phone that
     * boots with the filter off stays closed until the filter is back and answering.
     */
    /** Whether the phone fell back from the private DNS server to automatic DNS, and the probe counts (FEATURES 3.17). */
    var dnsFailover: app.lockmein.core.DnsFailover
        get() = app.lockmein.core.DnsFailover(
            fallbackSince = prefs.getLong(DNS_FALLBACK_SINCE, -1L).takeIf { it >= 0 },
            fails = prefs.getInt(DNS_FAILS, 0),
            firstFailAt = prefs.getLong(DNS_FIRST_FAIL, 0L),
            oks = prefs.getInt(DNS_OKS, 0),
            firstOkAt = prefs.getLong(DNS_FIRST_OK, 0L),
        )
        set(value) = prefs.edit()
            .putLong(DNS_FALLBACK_SINCE, value.fallbackSince ?: -1L)
            .putInt(DNS_FAILS, value.fails).putLong(DNS_FIRST_FAIL, value.firstFailAt)
            .putInt(DNS_OKS, value.oks).putLong(DNS_FIRST_OK, value.firstOkAt)
            .commit().let { }

    var filterDown: Boolean
        get() = prefs.getBoolean(FILTER_DOWN, false)
        set(value) = prefs.edit().putBoolean(FILTER_DOWN, value).apply()

    /** Last time the screen filter detected a violation. */
    var lastFilterHitMillis: Long
        get() = prefs.getLong(LAST_FILTER_HIT, 0L)
        set(value) = prefs.edit().putLong(LAST_FILTER_HIT, value).apply()

    /** The counter of the last holder code that was accepted, so a code is never used twice. */
    var lastCodeCounter: Long?
        get() = if (prefs.contains(LAST_CODE)) prefs.getLong(LAST_CODE, 0L) else null
        set(value) = prefs.edit().apply { if (value == null) remove(LAST_CODE) else putLong(LAST_CODE, value) }.apply()

    /** When the next policy pass is due (millis), as the last pass set its alarm. */
    var nextPassAt: Long
        get() = prefs.getLong(NEXT_PASS_AT, 0L)
        set(value) = prefs.edit().putLong(NEXT_PASS_AT, value).apply()

    /** The last DNS host Android would not take, why, the host it kept, and when; for the health check. */
    val dnsChangeFailed: String? get() = prefs.getString(DNS_CHANGE_FAILED, null)

    fun noteDnsChangeFailed(host: String, reason: String, kept: String, now: Instant) {
        val at = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm").withZone(java.time.ZoneId.systemDefault()).format(now)
        prefs.edit().putString(DNS_CHANGE_FAILED, "The change to $host failed on $at: $reason. The phone kept $kept.").apply()
    }

    fun clearDnsChangeFailed() = prefs.edit().remove(DNS_CHANGE_FAILED).apply()

    /** Everything that opens the phone up and is waiting for Sunday review or friend code approval. */
    var requests: List<ChangeRequest>
        get() = Requests.decode(prefs.getString(REQUESTS, "[]").orEmpty())
        set(value) = prefs.edit().putString(REQUESTS, Requests.encode(value)).apply()

    /** Adds or increments a change request. Returns the updated request count. */
    fun addRequest(request: ChangeRequest, now: Instant): Int {
        val updated = Requests.add(requests, request, now.toEpochMilli())
        requests = updated
        return updated.firstOrNull { it.id == request.id }?.count ?: 1
    }

    /** History of approved or dismissed requests with their counts. */
    var requestHistory: List<ResolvedRequest>
        get() = Requests.decodeHistory(prefs.getString(REQUEST_HISTORY, "[]").orEmpty())
        set(value) = prefs.edit().putString(REQUEST_HISTORY, Requests.encodeHistory(value)).apply()

    fun recordResolution(request: ChangeRequest, resolution: RequestResolution, now: Instant) {
        // Works whether or not the request is still queued (carrying one out already drops it from the queue).
        requests = requests.filter { it.id != request.id }
        requestHistory = listOf(ResolvedRequest(request.kind, request.target, request.count, request.requestedAtMillis, now.toEpochMilli(), resolution)) + requestHistory
    }

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

    /** Every app seen so far; null until the first pass recorded what was installed. */
    val knownPackages: Set<String>? get() = prefs.getStringSet(KNOWN, null)?.toSet()

    fun quarantine(packageName: String) {
        val known = prefs.getStringSet(KNOWN, emptySet()).orEmpty()
        val quar = quarantined
        prefs.edit()
            .putStringSet(KNOWN, known + packageName)
            .putStringSet(QUARANTINED, quar + packageName)
            .apply()
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
        const val REQUEST_HISTORY = "request_history"
        const val UNBLOCKS = "temporary_unblocks"
        const val LAST_CODE = "last_code_counter"
        const val FAILED_ATTEMPTS = "failed_code_attempts"
        const val LOCKOUT_UNTIL = "lockout_until"
        const val FILTER_BLOCKED = "filter_blocked_apps"
        const val LAST_FILTER_CHECK = "last_filter_check"
        const val LAST_FILTER_HIT = "last_filter_hit"
        const val FILTER_DOWN = "filter_down"
        const val DNS_FALLBACK_SINCE = "dns_fallback_since"
        const val DNS_CHANGE_FAILED = "dns_change_failed"
        const val NEXT_PASS_AT = "next_pass_at"
        const val DNS_FAILS = "dns_fails"
        const val DNS_FIRST_FAIL = "dns_first_fail"
        const val DNS_OKS = "dns_oks"
        const val DNS_FIRST_OK = "dns_first_ok"
    }
}
