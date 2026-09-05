package com.enrpau.pokegeards

import com.enrpau.pokegeards.detection.FuzzyMatch
import com.enrpau.pokegeards.detection.LocationResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM checks for split-family route resolution.
 *
 * Ids and names below are copied verbatim from
 * app/src/main/assets/packs/lumi_plat/locations.csv, in that file's row order, so
 * the fuzzy matcher sees the same candidate ordering it sees at runtime.
 */
class LocationResolverTest {

    private val locs = listOf(
        54 to "Eterna City",
        195 to "Oreburgh Mine B1F",
        196 to "Oreburgh Mine B2F",
        197 to "Valley Windworks (Outside)",
        200 to "Eterna Forest",
        255 to "Oreburgh Gate - 1F",
        256 to "Oreburgh Gate - B1F",
        354 to "Route 201",
        355 to "Route 202",
        356 to "Route 203",
        357 to "Route 204 (South)",
        358 to "Route 204 (North)",
        359 to "Route 205 (South)",
        361 to "Route 205 (North)",
        362 to "Route 206",
        364 to "Route 207",
        392 to "Route 214",
    )

    // ---- base-name stripping -------------------------------------------------

    @Test fun baseName_stripsTheSuffixShapesInLumiPlat() {
        assertEquals("Route 204", LocationResolver.baseName("Route 204 (North)"))
        assertEquals("Route 204", LocationResolver.baseName("Route 204 (South)"))
        assertEquals("Oreburgh Gate", LocationResolver.baseName("Oreburgh Gate - 1F"))
        assertEquals("Oreburgh Gate", LocationResolver.baseName("Oreburgh Gate - B1F"))
        assertEquals("Oreburgh Mine", LocationResolver.baseName("Oreburgh Mine B1F"))
        assertEquals("Mt. Coronet", LocationResolver.baseName("Mt. Coronet - 4F (Waterfall)"))
        assertEquals("Great Marsh", LocationResolver.baseName("Great Marsh - Area 3"))
        assertEquals("Valley Windworks", LocationResolver.baseName("Valley Windworks (Outside)"))
        // untouched when there is no qualifier
        assertEquals("Route 203", LocationResolver.baseName("Route 203"))
        assertEquals("Eterna Forest", LocationResolver.baseName("Eterna Forest"))
    }

    // ---- the bug, before/after ----------------------------------------------

    @Test fun oldMatcher_getsSplitRoutesWrong() {
        // Documents what we are fixing: on full names alone, "Route 204" is one
        // edit from Route 201/202/203/214 and eight from either real row, so the
        // old path silently confirmed the wrong route entirely.
        assertEquals("Route 201", FuzzyMatch.bestPhrase("Route 204", locs)?.second)
    }

    @Test fun newResolver_findsBothHalvesAsAFamily() {
        val fam = LocationResolver().matchFamily("Route 204", locs).map { it.first }.sorted()
        assertEquals(listOf(357, 358), fam)
        val gate = LocationResolver().matchFamily("Oreburgh Gate", locs).map { it.first }.sorted()
        assertEquals(listOf(255, 256), gate)
        val mine = LocationResolver().matchFamily("Oreburgh Mine", locs).map { it.first }.sorted()
        assertEquals(listOf(195, 196), mine)
    }

    // ---- no regression on ordinary locations --------------------------------

    @Test fun plainLocationsResolveExactlyAsBefore() {
        for (name in listOf("Route 201", "Route 203", "Route 207", "Eterna City", "Eterna Forest")) {
            val r = LocationResolver()
            assertEquals("banner \"$name\"", FuzzyMatch.bestPhrase(name, locs)?.first, r.resolve(name, locs))
        }
        // noisy OCR + the FPS overlay still lands on the same single row
        assertEquals(354, LocationResolver().resolve("FPS 29.3\nRoute 2O1", locs))
        // and a floor-specific banner is trusted as-is, not widened to its family
        assertEquals(196, LocationResolver().resolve("Oreburgh Mine B2F", locs))
        assertEquals(256, LocationResolver().resolve("Oreburgh Gate - B1F", locs))
        // junk still matches nothing
        assertNull(LocationResolver().resolve("Press the A Button", locs))
        assertNull(LocationResolver().resolve("", locs))
    }

    // ---- the required scenario ----------------------------------------------

    @Test fun route203_then204_then_cave_then_back_resumesTheSameHalf() {
        val r = LocationResolver()

        assertEquals("Route 203 is a plain row", 356, r.resolve("Route 203", locs))

        // adjacency: Route 203 -> (Jubilife) -> Route 204's SOUTH mouth.
        // Note 357 is also the lowest id in the family, so this assertion alone
        // cannot tell adjacency from the deterministic fallback — see the contrast
        // at the end of this test and theNorthHalfIsReachableToo.
        assertEquals("first 204 read", 357, r.resolve("Route 204", locs))

        // Oreburgh Gate is itself a family; Route 204 (South) neighbours neither
        // half, so the scan falls back to Route 203, which touches 1F only.
        assertEquals("gate read", 255, r.resolve("Oreburgh Gate", locs))

        // re-entry: nothing in history neighbours a 204 half, but 357 is still in
        // history, so we resume the half we were actually on instead of re-guessing.
        assertEquals("second 204 read resumes South", 357, r.resolve("Route 204", locs))

        assertEquals(listOf(356, 255, 357), r.history())

        // Contrast: the identical banner, approached from the north side instead,
        // resolves to the other half. So the picks above are the adjacency map and
        // the history talking, not the lowest-id fallback.
        val fromNorth = LocationResolver()
        fromNorth.remember(359) // Route 205 (South), i.e. arriving via Floaroma
        assertEquals(358, fromNorth.resolve("Route 204", locs))
        assertEquals(255, fromNorth.resolve("Oreburgh Gate", locs))
        assertEquals("resumes North, not South", 358, fromNorth.resolve("Route 204", locs))
    }

    @Test fun theNorthHalfIsReachableToo() {
        // Coming off Route 205 (South) — i.e. through Floaroma Town — the same
        // "Route 204" banner must resolve to the other half.
        val r = LocationResolver()
        assertEquals(359, r.resolve("Route 205 (South)", locs))
        assertEquals(358, r.resolve("Route 204", locs))
        // and a plain "Route 205" banner after Eterna Forest picks North
        val r2 = LocationResolver()
        assertEquals(200, r2.resolve("Eterna Forest", locs))
        assertEquals(361, r2.resolve("Route 205", locs))
    }

    @Test fun stayPutBeatsAnOlderNeighbourSignal() {
        // In the mine: B1F, then B2F, then a re-read of the plain banner must not
        // snap back to B1F just because Oreburgh Gate is still in history.
        val r = LocationResolver()
        assertEquals(255, r.resolve("Oreburgh Gate - 1F", locs))
        assertEquals(195, r.resolve("Oreburgh Mine", locs))   // gate 1F touches B1F only
        assertEquals(196, r.resolve("Oreburgh Mine B2F", locs))
        assertEquals(196, r.resolve("Oreburgh Mine", locs))   // stays on B2F
    }

    @Test fun historyKeepsFiveDistinctIdsMostRecentLast() {
        val r = LocationResolver()
        listOf("Route 201", "Route 202", "Route 203", "Route 206", "Route 207", "Route 214")
            .forEach { r.resolve(it, locs) }
        assertEquals(listOf(355, 356, 362, 364, 392), r.history())

        // revisiting an id already held moves it to the end, it does not duplicate
        assertEquals(356, r.resolve("Route 203", locs))
        assertEquals(listOf(355, 362, 364, 392, 356), r.history())
        assertTrue(r.history().toSet().size == r.history().size)
    }
}
