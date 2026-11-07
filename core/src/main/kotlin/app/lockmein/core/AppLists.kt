package app.lockmein.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant

/** The three lists the owner can edit. All list apps to close, so adding closes more and removing opens up. */
enum class AppList {
    /** Apps closed from the night start until fajr. */
    NIGHT_CLOSED,

    /** Apps suspended in every phase. */
    ALWAYS_BLOCKED,

    /** Apps suspended outside the daily open window (20:00 to 21:30). */
    WINDOW_APPS,
}

enum class ListOp { ADD, REMOVE }

/** A scheduled removal of an app from a list, which takes effect automatically 7 days later. */
@Serializable
data class ScheduledRemoval(
    val list: AppList,
    val packageName: String,
    val scheduledAtMillis: Long,
    val dueAtMillis: Long,
) {
    val id: String get() = "${list.name}:$packageName"
    val scheduledAt: Instant get() = Instant.ofEpochMilli(scheduledAtMillis)
    val dueAt: Instant get() = Instant.ofEpochMilli(dueAtMillis)
    fun isDue(now: Instant): Boolean = now >= dueAt
}

object ScheduledRemovals {
    val DELAY: Duration = Duration.ofDays(7)

    fun schedule(list: AppList, packageName: String, now: Instant): ScheduledRemoval =
        ScheduledRemoval(list, packageName, now.toEpochMilli(), now.plus(DELAY).toEpochMilli())

    fun cancel(removals: List<ScheduledRemoval>, id: String): List<ScheduledRemoval> =
        removals.filter { it.id != id }

    fun due(removals: List<ScheduledRemoval>, now: Instant): List<ScheduledRemoval> =
        removals.filter { it.isDue(now) }

    fun remaining(removals: List<ScheduledRemoval>, now: Instant): List<ScheduledRemoval> =
        removals.filter { !it.isDue(now) }

    fun encode(removals: List<ScheduledRemoval>): String =
        Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ScheduledRemoval.serializer()), removals)

    fun decode(text: String): List<ScheduledRemoval> =
        runCatching { Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ScheduledRemoval.serializer()), text) }.getOrDefault(emptyList())
}

/** Which edits open the phone up. Those are requests that wait; the others apply at once. */
class AppListEditor {
    fun kindOf(list: AppList, op: ListOp): ChangeKind = if (op == ListOp.REMOVE) ChangeKind.LOOSEN else ChangeKind.TIGHTEN

    /** The request kind a loosening edit becomes. */
    fun requestKind(list: AppList): RequestKind = when (list) {
        AppList.NIGHT_CLOSED -> RequestKind.NIGHT_REMOVE
        AppList.ALWAYS_BLOCKED -> RequestKind.BLOCKED_REMOVE
        AppList.WINDOW_APPS -> RequestKind.BLOCKED_REMOVE
    }
}
