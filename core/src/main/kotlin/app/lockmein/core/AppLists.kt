package app.lockmein.core

import java.time.Instant

/** The two lists the owner can edit. */
enum class AppList {
    /** Apps that may run at night, besides the protected ones. */
    NIGHT_ALLOWED,

    /** Apps suspended in every phase. */
    ALWAYS_BLOCKED,
}

enum class ListOp { ADD, REMOVE }

data class EditResult(val list: Set<String>, val decision: Decision)

/**
 * Edits the two lists through the change gate. Whatever opens the phone up (more night apps, fewer blocked apps) needs the Sunday
 * window or the friend's emergency window; whatever closes it more is allowed at any time.
 */
class AppListEditor(private val gate: ChangeGate) {
    fun kindOf(list: AppList, op: ListOp): ChangeKind = when (list to op) {
        AppList.NIGHT_ALLOWED to ListOp.ADD, AppList.ALWAYS_BLOCKED to ListOp.REMOVE -> ChangeKind.LOOSEN
        else -> ChangeKind.TIGHTEN
    }

    fun edit(
        list: AppList,
        op: ListOp,
        current: Set<String>,
        packageName: String,
        untouchable: Set<String>,
        now: Instant,
        networkTime: Instant?,
        emergencyUntil: Instant?,
    ): EditResult {
        if (packageName in untouchable) return EditResult(current, Decision.Denied("this app keeps the phone working and cannot be listed"))
        val decision = gate.decide(kindOf(list, op), now, networkTime, emergencyUntil)
        if (decision is Decision.Denied) return EditResult(current, decision)
        return EditResult(if (op == ListOp.ADD) current + packageName else current - packageName, decision)
    }
}
