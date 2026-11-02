package app.lockmein

import app.lockmein.core.AppRules

/** The owner's choices from docs/DECISIONS.md, as package names on the Poco X7 Pro. */
object OwnerRules {
    const val BRAVE = "com.brave.browser"

    /**
     * Running at night besides the protected packages (which already cover Phone and Messages): Clock, Authenticator and
     * Deezer, the owner's music player. This is where the list starts; the owner edits it in the app.
     */
    val DEFAULT_NIGHT_ALLOWLIST = setOf(
        "com.android.deskclock",
        "com.google.android.deskclock",
        "com.azure.authenticator",
        "deezer.android.app",
    )

    /** The rules for this phone, with the two lists the owner edits taken from [state]. */
    fun forDevice(snapshot: DeviceSnapshot, appPackage: String, state: LockState) = AppRules(
        appPackage = appPackage,
        allowedBrowser = BRAVE,
        browsers = snapshot.browsers + BRAVE,
        nightAllowlist = state.nightAllowlist,
        protectedPackages = snapshot.protectedPackages,
        alwaysBlocked = state.alwaysBlocked,
    )
}
