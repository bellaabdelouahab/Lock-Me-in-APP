package app.lockmein.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchedAppsTest {
    private val defaults = LockConfig.WATCHED_APPS_DEFAULT

    @Test
    fun `default watched apps include Brave Chrome Facebook and Instagram`() {
        val expected = setOf(
            "com.brave.browser",
            "com.android.chrome",
            "com.facebook.katana",
            "com.instagram.android",
        )
        assertEquals(expected, defaults)
        for (pkg in expected) {
            assertTrue(WatchedApps.isWatched(pkg, defaults))
        }
    }

    @Test
    fun `pattern keywords match manga manhwa webtoon anime comic in package names`() {
        val matched = listOf(
            "com.naver.webtoon",
            "org.tachiyomi.manga",
            "com.topmanhwa.reader",
            "tv.crunchyroll.animeapp",
            "com.marvel.comics",
            "app.read_manga.free",
        )
        for (pkg in matched) {
            assertTrue(WatchedApps.matchesPattern(pkg), "Package $pkg should match pattern keywords")
            assertTrue(WatchedApps.isWatched(pkg, emptySet()), "Package $pkg should be watched even if not in explicit set")
        }
    }

    @Test
    fun `normal apps are not matched by pattern or defaults`() {
        val safe = listOf(
            "com.google.android.calculator",
            "com.google.android.deskclock",
            "com.google.android.apps.photos",
            "org.wikipedia",
            "ma.bank.app",
        )
        for (pkg in safe) {
            assertFalse(WatchedApps.matchesPattern(pkg), "Package $pkg should not match keywords")
            assertFalse(WatchedApps.isWatched(pkg, defaults), "Package $pkg should not be watched")
        }
    }

    @Test
    fun `resolveWatched extracts all watched apps from installed inventory`() {
        val installed = setOf(
            "com.brave.browser",
            "com.google.android.calculator",
            "com.naver.webtoon",
            "com.unknown.anime.viewer",
            "com.bank.app",
        )
        val resolved = WatchedApps.resolveWatched(installed, defaults)
        assertEquals(setOf("com.brave.browser", "com.naver.webtoon", "com.unknown.anime.viewer"), resolved)
    }
}
