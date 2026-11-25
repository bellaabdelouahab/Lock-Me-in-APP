package app.lockmein.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacesTest {
    @Test
    fun theDefaultPlaceIsAgadirWithMoroccosAngle() {
        val c = FajrConfig()
        assertEquals("Agadir, Morocco", Places.nameOf(c.latitude, c.longitude))
        assertEquals(19.0, Places.angleFor("Morocco"))
        assertEquals(18.0, Places.angleFor("Nowhere"))
    }

    @Test
    fun searchFindsByCityOrCountry() {
        assertTrue(Places.search("casa").any { it.city == "Casablanca" })
        assertTrue(Places.search("morocco").size > 30)
        assertEquals(Places.ALL, Places.search(" "))
    }

    @Test
    fun everyPlaceIsOneFajrCanBeWorkedOutFor() {
        Places.ALL.forEach { p ->
            assertTrue(p.latitude in -66.0..66.0 && p.longitude in -180.0..180.0, p.name)
        }
        assertEquals(Places.ALL.size, Places.ALL.map { it.name }.toSet().size, "no city twice")
    }

    @Test
    fun aPointFarFromEveryCityHasNoName() {
        assertNull(Places.nameOf(0.0, -30.0))
        val (p, km) = Places.nearest(30.40, -9.55)
        assertTrue(km < 10 && (p.city == "Agadir" || p.city == "Inezgane"))
    }
}
