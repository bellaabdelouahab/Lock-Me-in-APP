package app.lockmein.core

/**
 * A watched app whose screen the filter cannot see. Private tabs (Brave, Firefox, Edge, Mi Browser, Chrome where its policy
 * is not applied) mark their window secure, so Android hands the filter a black picture and nothing in it can ever be a hit.
 * The filter sends such a screen home (FEATURES 3.16).
 *
 * Android 14+ says it outright: a screenshot of the app's own window fails with "secure window". Older versions only give the
 * black picture, so there a watched browser that stays fully black for [BLACK_FOR_MS] counts, which no ordinary page does.
 */
object HiddenScreen {
    /** Below this (0 to 255) a thumbnail pixel counts as black. */
    const val BLACK_LEVEL = 12

    /** Share of the thumbnail that must be black: the status and navigation bars stay visible around a secure window. */
    const val DARK_SHARE = 0.80f

    /** Share that must be black on Android 11 to 13, where only the picture can tell. */
    const val BLACK_SHARE = 0.97f

    /** How long a watched browser must stay that black on Android 11 to 13 before it is sent home. */
    const val BLACK_FOR_MS = 6_000L

    /** At most one window check this often (Android limits screenshots to about three a second). */
    const val PROBE_EVERY_MS = 1_500L

    /** One private window sends several events; one count per opening. */
    const val COUNT_EVERY_MS = 3_000L

    fun blackShare(gray: ByteArray): Float {
        if (gray.isEmpty()) return 0f
        return gray.count { (it.toInt() and 0xFF) < BLACK_LEVEL }.toFloat() / gray.size
    }

    /** Dark enough that it may be a secure window: worth asking Android (14+) about the window itself. */
    fun mayBeHidden(gray: ByteArray): Boolean = blackShare(gray) >= DARK_SHARE

    /** Android 11 to 13: the frame is black enough to count toward [BLACK_FOR_MS]. */
    fun isBlack(gray: ByteArray): Boolean = blackShare(gray) >= BLACK_SHARE

    /** Android 11 to 13: black since [blackSinceMs] (null when the last frame was not), in a browser, long enough. */
    fun blackTooLong(blackSinceMs: Long?, nowMs: Long, isBrowser: Boolean): Boolean =
        isBrowser && blackSinceMs != null && nowMs - blackSinceMs >= BLACK_FOR_MS
}
