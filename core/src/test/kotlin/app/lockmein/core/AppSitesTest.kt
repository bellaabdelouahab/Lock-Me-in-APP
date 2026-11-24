package app.lockmein.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSitesTest {
    @Test
    fun readsTheHostTheWayBrowsersShowIt() {
        assertEquals("instagram.com", AppSites.host("instagram.com/reels"))
        assertEquals("www.instagram.com", AppSites.host("https://www.instagram.com/?hl=en"))
        assertEquals("m.facebook.com", AppSites.host("  M.Facebook.com  "))
        assertEquals("x.com", AppSites.host("x.com:443/home"))
        assertNull(AppSites.host("instagram reels"), "a search being typed")
        assertNull(AppSites.host(""))
        assertNull(AppSites.host("Search or type URL"))
        assertNull(AppSites.host("localhost"))
    }

    @Test
    fun aClosedAppsSiteIsClosedAndOnlyThen() {
        val closed = setOf("com.instagram.android", "com.google.android.youtube")
        assertEquals("com.instagram.android", AppSites.closedSite("www.instagram.com/p/abc", closed, emptySet())?.app)
        assertEquals("com.google.android.youtube", AppSites.closedSite("m.youtube.com/watch?v=1", closed, emptySet())?.app)
        assertNull(AppSites.closedSite("instagram.com", emptySet(), emptySet()))
        assertNull(AppSites.closedSite("google.com/search?q=instagram.com", closed, emptySet()))
        // Lookalikes are not the site.
        assertNull(AppSites.closedSite("notinstagram.com", closed, emptySet()))
        assertNull(AppSites.closedSite("instagram.com.example.org", closed, emptySet()))
    }

    @Test
    fun theMostSpecificSiteWins() {
        // YouTube closed, YouTube Music open: music.youtube.com stays open, youtube.com closes.
        val closed = setOf("com.google.android.youtube")
        assertNull(AppSites.closedSite("music.youtube.com", closed, emptySet()))
        assertEquals("com.google.android.apps.youtube.music", AppSites.appFor("music.youtube.com"))
        assertTrue(AppSites.closedSite("youtube.com", closed, emptySet()) != null)
    }

    @Test
    fun aSharedSiteStaysOpenWhileOneOfItsAppsIsOpen() {
        val both = setOf("com.facebook.katana", "com.facebook.lite")
        assertNull(AppSites.closedSite("facebook.com", setOf("com.facebook.katana"), installed = both), "Lite installed and open")
        assertTrue(AppSites.closedSite("facebook.com", setOf("com.facebook.katana"), installed = setOf("com.facebook.katana")) != null, "Lite not installed")
        assertTrue(AppSites.closedSite("facebook.com", both, installed = both) != null)
        // The app removed from the phone but still on the closed list: its site stays closed.
        assertTrue(AppSites.closedSite("facebook.com", both, installed = emptySet()) != null)
    }

    @Test
    fun backFirstThenHome() {
        assertFalse(AppSites.sendHome(1))
        assertFalse(AppSites.sendHome(2))
        assertTrue(AppSites.sendHome(3))
    }
}
