package app.lockmein.core

/**
 * Rules and matching for watched apps monitored by the on-device AI screen filter.
 * In addition to explicitly listed packages (default: Brave, Chrome, Facebook, Instagram),
 * any installed package whose name contains manga, manhwa, webtoon, anime, or comic is watched.
 */
object WatchedApps {
    val PATTERN_KEYWORDS = listOf("manga", "manhwa", "webtoon", "anime", "comic")

    fun matchesPattern(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return PATTERN_KEYWORDS.any { lower.contains(it) }
    }

    fun isWatched(packageName: String, watchedApps: Set<String>): Boolean {
        return packageName in watchedApps || matchesPattern(packageName)
    }

    /** Returns all packages from [installed] that match either the explicit [watchedApps] list or the pattern keywords. */
    fun resolveWatched(installed: Set<String>, watchedApps: Set<String>): Set<String> {
        return installed.filter { isWatched(it, watchedApps) }.toSet()
    }
}
