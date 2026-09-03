package com.enrpau.pokegeards

import com.enrpau.pokegeards.detection.FuzzyMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-JVM checks for the OCR text matcher (route banners + catch dialogue). */
class FuzzyMatchTest {

    private val routes = listOf(
        1 to "Route 201", 2 to "Route 202", 3 to "Route 203",
        4 to "Sandgem Town", 5 to "Twinleaf Town", 6 to "Lake Verity",
    )
    private val species = listOf(
        29 to "Nidoran-F", 32 to "Nidoran-M", 396 to "Starly", 399 to "Bidoof",
    )

    @Test fun banner_exactAndNoisy() {
        assertEquals(1, FuzzyMatch.bestPhrase("Route 201", routes)?.first)
        // ML Kit often drops/garbles a char and prepends the FPS overlay
        assertEquals(1, FuzzyMatch.bestPhrase("FPS 29.3\nRoute 2O1", routes)?.first)
        assertEquals(4, FuzzyMatch.bestPhrase("Sandgem Town", routes)?.first)
    }

    @Test fun banner_rejectsUnrelated() {
        assertNull(FuzzyMatch.bestPhrase("Press the A Button", routes))
        assertNull(FuzzyMatch.bestPhrase("", routes))
    }

    @Test fun catch_dialogueSpecies() {
        assertEquals(29, FuzzyMatch.bestPhrase("gotcha! nidoran was caught!", species)?.first)
        assertEquals(396, FuzzyMatch.bestPhrase("starly's data was added to the pokedex.", species)?.first)
    }

    @Test fun distinguishesNidoranMF() {
        assertEquals(29, FuzzyMatch.best("Nidoran-F", species)?.first)
        assertEquals(32, FuzzyMatch.best("Nidoran-M", species)?.first)
    }
}
