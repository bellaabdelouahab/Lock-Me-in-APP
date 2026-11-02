package app.lockmein

import app.lockmein.core.AppRules

/** The owner's choices from docs/DECISIONS.md, as package names on the Poco X7 Pro. */
object OwnerRules {
    const val BRAVE = "com.brave.browser"

    /**
     * Running at night besides the protected packages (which already cover Phone and Messages): Clock, Authenticator and
     * Deezer, the owner's music player.
     */
    private val nightAllowlist = setOf(
        "com.android.deskclock",
        "com.google.android.deskclock",
        "com.azure.authenticator",
        "deezer.android.app",
    )

    fun forDevice(snapshot: DeviceSnapshot, appPackage: String) = AppRules(
        appPackage = appPackage,
        allowedBrowser = BRAVE,
        browsers = snapshot.browsers + BRAVE,
        nightAllowlist = nightAllowlist,
        protectedPackages = snapshot.protectedPackages,
    )
}
