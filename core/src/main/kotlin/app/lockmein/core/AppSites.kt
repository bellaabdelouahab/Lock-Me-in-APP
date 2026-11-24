package app.lockmein.core

/**
 * The website behind an app (FEATURES 3.18): while an app is closed, its site in a browser is closed too, so "I'll just open it
 * in the browser" stops working. Read from the browser's address bar through accessibility; nothing is kept.
 */
object AppSites {
    /** App package to the sites that are that app. A more specific site wins (music.youtube.com is YouTube Music, not YouTube). */
    val SITES: Map<String, Set<String>> = mapOf(
        "com.instagram.android" to setOf("instagram.com", "instagr.am"),
        "com.instagram.barcelona" to setOf("threads.net", "threads.com"),
        "com.facebook.katana" to setOf("facebook.com", "fb.com", "fb.watch"),
        "com.facebook.lite" to setOf("facebook.com", "fb.com", "fb.watch"),
        "com.facebook.orca" to setOf("messenger.com"),
        "com.zhiliaoapp.musically" to setOf("tiktok.com"),
        "com.ss.android.ugc.trill" to setOf("tiktok.com"),
        "com.google.android.youtube" to setOf("youtube.com", "youtu.be", "youtube-nocookie.com"),
        "com.google.android.apps.youtube.music" to setOf("music.youtube.com"),
        "com.google.android.apps.youtube.kids" to setOf("youtubekids.com"),
        "com.twitter.android" to setOf("x.com", "twitter.com"),
        "com.snapchat.android" to setOf("snapchat.com"),
        "com.reddit.frontpage" to setOf("reddit.com", "redd.it"),
        "com.pinterest" to setOf("pinterest.com", "pin.it"),
        "com.tumblr" to setOf("tumblr.com"),
        "com.netflix.mediaclient" to setOf("netflix.com"),
        "com.amazon.avod.thirdpartyclient" to setOf("primevideo.com"),
        "com.disney.disneyplus" to setOf("disneyplus.com"),
        "tv.twitch.android.app" to setOf("twitch.tv"),
        "com.discord" to setOf("discord.com"),
        "com.linkedin.android" to setOf("linkedin.com"),
        "com.ninegag.android.app" to setOf("9gag.com"),
        "com.quora.android" to setOf("quora.com"),
        "com.spotify.music" to setOf("open.spotify.com"),
    )

    /**
     * The host in an address bar's text, or null when it is not an address (a search being typed, an empty bar). Browsers show
     * "instagram.com/reels", "https://www.instagram.com/" or just "instagram.com".
     */
    fun host(addressBar: String): String? {
        var t = addressBar.trim().lowercase()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        t = t.substringAfter("://")
        t = t.substringBefore('/').substringBefore('?').substringBefore('#')
        t = t.substringAfterLast('@').substringBefore(':').trimEnd('.')
        if (!t.contains('.') || t.startsWith('.')) return null
        if (!t.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
        return t
    }

    /** The app whose site [host] is (the most specific site that matches), or null. */
    fun appFor(host: String): String? {
        var best: Pair<String, Int>? = null
        for ((pkg, sites) in SITES) for (site in sites) {
            if (host == site || host.endsWith(".$site")) {
                if (best == null || site.length > best.second) best = pkg to site.length
            }
        }
        return best?.first
    }

    /**
     * The closed app whose site the address bar shows, or null. A site shared by two apps (Facebook and Facebook Lite) is closed
     * when one of them is closed and none of them is installed and open, so it stays open while either app is.
     */
    fun closedSite(addressBar: String, closedNow: Set<String>, installed: Set<String>): ClosedSite? {
        val host = host(addressBar) ?: return null
        val owners = ownersOf(host)
        val closedOwner = owners.firstOrNull { it in closedNow } ?: return null
        if (owners.any { it in installed && it !in closedNow }) return null
        return ClosedSite(host, closedOwner)
    }

    private fun ownersOf(host: String): List<String> {
        var bestLength = 0
        val owners = mutableListOf<String>()
        for ((pkg, sites) in SITES) for (site in sites) {
            if (host == site || host.endsWith(".$site")) {
                when {
                    site.length > bestLength -> { bestLength = site.length; owners.clear(); owners += pkg }
                    site.length == bestLength && pkg !in owners -> owners += pkg
                }
            }
        }
        return owners
    }

    data class ClosedSite(val host: String, val app: String)

    /**
     * What to do on a closed site: go back (out of the site, keeping the browser) the first times, then home if back did not
     * leave it, so a site that keeps reopening itself cannot hold the browser.
     */
    fun sendHome(hitsInLast: Int): Boolean = hitsInLast > BACKS_BEFORE_HOME

    const val BACKS_BEFORE_HOME = 2
    const val STRIKE_WINDOW_MS = 8_000L
    const val CHECK_EVERY_MS = 1_000L
}
