package app.lockmein.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

enum class RequestKind {
    /** Old: put an app on the night allowlist, which no longer exists. Kept so saved requests still load; carrying one out changes nothing. */
    NIGHT_ADD,

    /** Stop closing an app at night. */
    NIGHT_REMOVE,

    /** Take an app off the always-blocked list. */
    BLOCKED_REMOVE,

    /** Take an app off the window apps list. */
    WINDOW_REMOVE,

    /** Open a closed browser. */
    BROWSER,

    /** Let a quarantined (new) app through. */
    RELEASE_APP,

    /** Any change to the settings. [ChangeRequest.settings] holds the whole new configuration. */
    SETTINGS,

    /** Reopen the setup to go through the steps again (all but the friend's code). */
    REDO_SETUP,

    /** Take the whole protection off (monthly uninstall window). */
    REMOVE_PROTECTION,
}

@Serializable
enum class RequestResolution {
    APPROVED,
    DISMISSED,
}

/** Something that opens the phone up, waiting for Sunday review or friend code approval. */
@Serializable
data class ChangeRequest(
    val kind: RequestKind,
    /** The package name, or empty when the request is about no single app. */
    val target: String = "",
    val requestedAtMillis: Long,
    val count: Int = 1,
    val requestTimes: List<Long> = listOf(requestedAtMillis),
    val settings: LockConfig? = null,
) {
    val id: String get() = "$kind:$target"
    val requestedAt: Instant get() = Instant.ofEpochMilli(requestedAtMillis)
}

/** A previously approved or dismissed request kept in history with its count. */
@Serializable
data class ResolvedRequest(
    val kind: RequestKind,
    val target: String = "",
    val count: Int,
    val requestedAtMillis: Long,
    val resolvedAtMillis: Long,
    val resolution: RequestResolution,
) {
    val id: String get() = "$kind:$target"
    val requestedAt: Instant get() = Instant.ofEpochMilli(requestedAtMillis)
    val resolvedAt: Instant get() = Instant.ofEpochMilli(resolvedAtMillis)
}

/** The waiting requests and request history. */
object Requests {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Asking again never duplicates a request: it increments the counter and records the timestamp.
     * The initial requestedAtMillis is preserved so the wait is not pushed out.
     * For settings, the settings draft is updated with the new one.
     */
    fun add(requests: List<ChangeRequest>, new: ChangeRequest, nowMillis: Long = new.requestedAtMillis): List<ChangeRequest> {
        val existing = requests.firstOrNull { it.id == new.id }
        return if (existing == null) {
            requests + new.copy(count = 1, requestTimes = listOf(nowMillis))
        } else {
            val updated = existing.copy(
                count = existing.count + 1,
                requestTimes = existing.requestTimes + nowMillis,
                settings = if (new.kind == RequestKind.SETTINGS && new.settings != null) new.settings else existing.settings,
            )
            requests.map { if (it.id == new.id) updated else it }
        }
    }

    /** Sorts by most asked first (count descending), then oldest requestedAtMillis first. */
    fun sortByMostAsked(requests: List<ChangeRequest>): List<ChangeRequest> =
        requests.sortedWith(compareByDescending<ChangeRequest> { it.count }.thenBy { it.requestedAtMillis })

    fun cancel(requests: List<ChangeRequest>, id: String): List<ChangeRequest> = requests.filter { it.id != id }

    fun resolve(
        requests: List<ChangeRequest>,
        id: String,
        resolution: RequestResolution,
        now: Instant,
    ): Pair<List<ChangeRequest>, ResolvedRequest?> {
        val req = requests.firstOrNull { it.id == id } ?: return requests to null
        val resolved = ResolvedRequest(
            kind = req.kind,
            target = req.target,
            count = req.count,
            requestedAtMillis = req.requestedAtMillis,
            resolvedAtMillis = now.toEpochMilli(),
            resolution = resolution,
        )
        return requests.filter { it.id != id } to resolved
    }

    fun encode(requests: List<ChangeRequest>): String =
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ChangeRequest.serializer()), requests)

    fun decode(text: String): List<ChangeRequest> =
        runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ChangeRequest.serializer()), text) }.getOrDefault(emptyList())

    fun encodeHistory(history: List<ResolvedRequest>): String =
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ResolvedRequest.serializer()), history)

    fun decodeHistory(text: String): List<ResolvedRequest> =
        runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ResolvedRequest.serializer()), text) }.getOrDefault(emptyList())
}
