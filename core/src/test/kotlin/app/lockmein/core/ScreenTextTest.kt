package app.lockmein.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScreenTextTest {
    @Test
    fun `genre tags, titles and searches are caught in any case`() {
        assertEquals("Ecchi", ScreenText.match("Genres: Action, Ecchi, Romance"))
        assertEquals("HAREM", ScreenText.match("ISEKAI HAREM CHAPTER 12"))
        assertEquals("18+", ScreenText.match("Rated 18+ only"))
        assertEquals("fan service", ScreenText.match("lots of fan service in ep 3"))
        assertEquals("bikini", ScreenText.match("Beach episode: bikini contest"))
        assertEquals("nue", ScreenText.match("photo nue"))
        assertEquals("سكس", ScreenText.match("فيديو سكس"))
    }

    @Test
    fun `parts of other words and ordinary pages are not caught`() {
        assertNull(ScreenText.match("Essex and Middlesex"))
        assertNull(ScreenText.match("Sussex county"))
        assertNull(ScreenText.match("Chapter 45: the final battle. Magic, swords, school life."))
        assertNull(ScreenText.match("Avenue des sexagénaires"))
        assertNull(ScreenText.match("مثير للاهتمام"))
        assertNull(ScreenText.match(""))
    }
}
