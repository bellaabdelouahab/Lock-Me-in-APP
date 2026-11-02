package app.lockmein.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

enum class RequestKind {
    /** Put an app on the night list. */
    NIGHT_ADD,

    /** Take an app off the always-blocked list. */
    BLOCKED_REMOVE,

    /** Open a closed browser. */
    BROWSER,

    /** Let a quarantined (new) app through. */
    RELEASE_APP,

    /** Any change to the settings. [ChangeRequest.settings] holds the whole new configuration. */
    SETTINGS,

    /** Take the whole protection off (the friend's code is still needed when it is carried out). */
    REMOVE_PROTECTION,
}

/** Something that opens the phone up, waiting for its time. */
@Serializable
data class ChangeRequest(
    val kind: RequestKind,
    /** The package name, or empty when the request is about no single app. */
    val target: String = "",
    val requestedAtMillis: Long,
    val settings: LockConfig? = null,
) {
    val id: String get() = "$kind:$target"
    val requestedAt: Instant get() = Instant.ofEpochMilli(requestedAtMillis)
}

/** The waiting requests: kept as text, one per kind and target. */
object Requests {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Asking again never restarts a wait: the first request counts. The exception is a settings request, which replaces the earlier one
     * and starts its wait again, so a small change cannot be swapped for a big one after the wait is over.
     */
    fun add(requests: List<ChangeRequest>, new: ChangeRequest): List<ChangeRequest> {
        val existing = requests.firstOrNull { it.id == new.id }
        return when {
            existing == null -> requests + new
            new.kind == RequestKind.SETTINGS -> requests.filter { it.id != new.id } + new
            else -> requests
        }
    }

    fun cancel(requests: List<ChangeRequest>, id: String): List<ChangeRequest> = requests.filter { it.id != id }

    fun encode(requests: List<ChangeRequest>): String = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ChangeRequest.serializer()), requests)

    fun decode(text: String): List<ChangeRequest> =
        runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ChangeRequest.serializer()), text) }.getOrDefault(emptyList())
}
