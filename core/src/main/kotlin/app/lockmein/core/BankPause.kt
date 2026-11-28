package app.lockmein.core

/**
 * A pause of the screen filter for a bank app (spec D1): the bank apps refuse to open while any accessibility service is on.
 * [auto] is true when the app can switch the filter back on itself (WRITE_SECURE_SETTINGS, granted once by adb); otherwise
 * the owner switches it back on by hand, and the watched apps stay closed until then.
 */
data class BankPause(val pkg: String, val startMs: Long, val auto: Boolean) {
    fun encode() = "$pkg|$startMs|$auto"

    companion object {
        /** The longest a pause lasts before the filter is switched back on, bank app in front or not. */
        const val CAP_MS = 10 * 60_000L

        const val LEFT = "left the app"
        const val SCREEN_OFF = "screen off"
        const val CAP = "10-minute cap"
        const val BY_HAND = "switched back on by hand"

        fun decode(s: String?): BankPause? {
            val p = s?.split('|') ?: return null
            return runCatching { BankPause(p[0], p[1].toLong(), p[2].toBoolean()) }.getOrNull()
        }

        /**
         * Why the pause should end now, or null to keep it. [foreground] is the app in front when it is known (null otherwise);
         * [capMs] is [CAP_MS] except in the emulator tests.
         */
        fun endReason(pause: BankPause, nowMs: Long, foreground: String?, screenOff: Boolean, capMs: Long = CAP_MS): String? = when {
            screenOff -> SCREEN_OFF
            foreground != null && foreground != pause.pkg -> LEFT
            nowMs - pause.startMs >= capMs -> CAP
            else -> null
        }
    }
}
