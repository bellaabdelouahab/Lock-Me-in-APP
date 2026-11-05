package app.lockmein.core

/** Apps that are a way onto the web even when they do not call themselves a browser. */
object KnownBrowsers {
    const val CHROME = "com.android.chrome"

    val ALL: Set<String> = setOf(
        CHROME,
        "com.brave.browser",
        "com.mi.globalbrowser",
        "com.android.browser",
        "com.opera.browser",
        "com.opera.mini.native",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.duckduckgo.mobile.android",
        "com.sec.android.app.sbrowser",
        "com.vivaldi.browser",
        "org.torproject.torbrowser",
        "com.kiwibrowser.browser",
        // The Google app has a feed and opens links in its own browser.
        "com.google.android.googlequicksearchbox",
    )
}

/** What Chrome is told through managed configuration, so its own settings cannot go around the DNS filter or the safe-search rules. */
object ChromePolicy {
    const val PACKAGE = KnownBrowsers.CHROME
    const val DNS_OVER_HTTPS_MODE = "DnsOverHttpsMode"
    const val INCOGNITO_AVAILABILITY = "IncognitoModeAvailability"
    const val FORCE_GOOGLE_SAFE_SEARCH = "ForceGoogleSafeSearch"
    const val FORCE_YOUTUBE_RESTRICT = "ForceYouTubeRestrict"

    /** Chrome's own secure DNS off, private windows off, safe search forced, YouTube restricted mode strict. */
    val STRINGS: Map<String, String> = mapOf(DNS_OVER_HTTPS_MODE to "off")
    val INTS: Map<String, Int> = mapOf(INCOGNITO_AVAILABILITY to 1, FORCE_YOUTUBE_RESTRICT to 2)
    val BOOLEANS: Map<String, Boolean> = mapOf(FORCE_GOOGLE_SAFE_SEARCH to true)
}

/** Apps that carry video or open-ended feeds, some under an audio name: suggested for the night list, with the reason. The person decides. */
object NightRisk {
    private val VIDEO_CAPABLE = mapOf(
        "com.google.android.youtube" to "YouTube plays video.",
        "com.google.android.apps.youtube.music" to "YouTube Music plays music videos.",
        "com.spotify.music" to "Spotify carries video podcasts.",
        "deezer.android.app" to "Deezer carries video podcasts.",
        "com.netflix.mediaclient" to "Netflix is video.",
        "com.zhiliaoapp.musically" to "TikTok is a video feed.",
        "com.instagram.android" to "Instagram is a feed.",
        "com.facebook.katana" to "Facebook is a feed.",
    )

    fun warning(packageName: String): String? = VIDEO_CAPABLE[packageName]
}
