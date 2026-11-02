package app.lockmein.core

/** The two lists the owner can edit. */
enum class AppList {
    /** Apps that may run at night, besides the protected ones. */
    NIGHT_ALLOWED,

    /** Apps suspended in every phase. */
    ALWAYS_BLOCKED,
}

enum class ListOp { ADD, REMOVE }

data class EditResult(val list: Set<String>, val decision: Decision)

/** Which edits open the phone up. Those are requests that wait; the others apply at once. */
class AppListEditor {
    fun kindOf(list: AppList, op: ListOp): ChangeKind = when (list to op) {
        AppList.NIGHT_ALLOWED to ListOp.ADD, AppList.ALWAYS_BLOCKED to ListOp.REMOVE -> ChangeKind.LOOSEN
        else -> ChangeKind.TIGHTEN
    }

    /** The request kind a loosening edit becomes. */
    fun requestKind(list: AppList): RequestKind = if (list == AppList.NIGHT_ALLOWED) RequestKind.NIGHT_ADD else RequestKind.BLOCKED_REMOVE
}
