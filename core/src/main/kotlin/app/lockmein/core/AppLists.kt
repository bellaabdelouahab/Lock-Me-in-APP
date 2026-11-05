package app.lockmein.core

/** The two lists the owner can edit. Both list apps to close, so adding closes more and removing opens up. */
enum class AppList {
    /** Apps closed from the night start until fajr. */
    NIGHT_CLOSED,

    /** Apps suspended in every phase. */
    ALWAYS_BLOCKED,
}

enum class ListOp { ADD, REMOVE }

/** Which edits open the phone up. Those are requests that wait; the others apply at once. */
class AppListEditor {
    fun kindOf(list: AppList, op: ListOp): ChangeKind = if (op == ListOp.REMOVE) ChangeKind.LOOSEN else ChangeKind.TIGHTEN

    /** The request kind a loosening edit becomes. */
    fun requestKind(list: AppList): RequestKind = if (list == AppList.NIGHT_CLOSED) RequestKind.NIGHT_REMOVE else RequestKind.BLOCKED_REMOVE
}
