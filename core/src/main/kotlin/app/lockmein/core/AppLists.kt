package app.lockmein.core

/** The three lists the owner can edit. All list apps to close, so adding closes more and removing opens up. */
enum class AppList {
    /** Apps closed from the night start until fajr. */
    NIGHT_CLOSED,

    /** Apps suspended in every phase. */
    ALWAYS_BLOCKED,

    /** Apps suspended outside the daily open window (20:00 to 21:30). */
    WINDOW_APPS,

    /** Apps monitored by the on-device AI screen filter. */
    WATCHED,
}

enum class ListOp { ADD, REMOVE }

/** Which edits open the phone up. Those are requests that wait; the others apply at once. */
class AppListEditor {
    fun kindOf(list: AppList, op: ListOp): ChangeKind = if (op == ListOp.REMOVE) ChangeKind.LOOSEN else ChangeKind.TIGHTEN

    /** The request kind a loosening edit becomes. */
    fun requestKind(list: AppList): RequestKind = when (list) {
        AppList.NIGHT_CLOSED -> RequestKind.NIGHT_REMOVE
        AppList.ALWAYS_BLOCKED -> RequestKind.BLOCKED_REMOVE
        AppList.WINDOW_APPS -> RequestKind.WINDOW_REMOVE
        AppList.WATCHED -> RequestKind.WATCHED_REMOVE
    }
}
