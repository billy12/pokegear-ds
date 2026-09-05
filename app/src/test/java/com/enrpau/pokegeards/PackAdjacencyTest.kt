package com.enrpau.pokegeards

import com.enrpau.pokegeards.detection.PackAdjacency
import com.enrpau.pokegeards.detection.SinnohAdjacency
import com.enrpau.pokegeards.map.GraphLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The base-name fallback that lets a pack using unsplit location names (bdsp:
 * "Route 204", "Oreburgh Gate") still land on the lumi_plat-keyed
 * [SinnohAdjacency] graph. Builds each pack's graph exactly the way MapActivity
 * does — `PackAdjacency.forPack(locs.map { it.name })` into [GraphLayout].
 *
 * Reads the shipped locations.csv files, not copied-out lists.
 */
class PackAdjacencyTest {

    private fun names(pack: String): List<String> {
        val f = listOf(
            "src/main/assets/packs/$pack/locations.csv",
            "app/src/main/assets/packs/$pack/locations.csv",
        ).map { File(it) }.firstOrNull { it.isFile }
            ?: error("$pack/locations.csv not found from working dir ${File(".").absolutePath}")
        return f.readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",")[1] }
    }

    private val bdsp by lazy { names("bdsp") }
    private val lumi by lazy { names("lumi_plat") }

    /** How many rows the *old* wiring (raw SinnohAdjacency.EDGES) left at degree 0. */
    private fun orphansAgainstRawEdges(rows: List<String>): List<String> {
        val present = rows.map { it.lowercase() }.toSet()
        return rows.map { it.lowercase() }.filter { k ->
            SinnohAdjacency.EDGES[k].orEmpty().none { it in present }
        }
    }

    private fun orphans(rows: List<String>, edges: Map<String, Set<String>>): List<String> =
        rows.map { it.lowercase() }.filter { edges[it].isNullOrEmpty() }

    // ---- lumi_plat must be untouched -----------------------------------------

    @Test fun lumiPlatGraphIsIdenticalToTheRawAdjacencyMap() {
        // Every lumi_plat row has an exact hit, so the fallback never fires and the
        // map screen gets byte-for-byte what it got before.
        assertEquals(SinnohAdjacency.EDGES, PackAdjacency.forPack(lumi))
    }

    @Test fun lumiPlatLaysOutToTheExactSameCoordinates() {
        // Equal maps can still iterate in a different order, and the force sums are
        // floating point, so compare the actual layout the map screen would get.
        val keys = lumi.map { it.lowercase() }
        val before = GraphLayout.layout(keys, SinnohAdjacency.EDGES)
        val after = GraphLayout.layout(keys, PackAdjacency.forPack(lumi))
        assertEquals(before.iterations, after.iterations)
        assertEquals(before.positions, after.positions)
        assertEquals(before.isolated, after.isolated)
        assertEquals(before.width, after.width, 0f)
        assertEquals(before.height, after.height, 0f)
    }

    @Test fun lumiPlatHasNoOrphansEitherWay() {
        assertEquals(emptyList<String>(), orphansAgainstRawEdges(lumi))
        assertEquals(emptyList<String>(), orphans(lumi, PackAdjacency.forPack(lumi)))
    }

    // ---- bdsp is the fix ------------------------------------------------------

    @Test fun bdspOrphanCountDropsSharply() {
        val before = orphansAgainstRawEdges(bdsp)
        val edges = PackAdjacency.forPack(bdsp)
        val after = orphans(bdsp, edges)
        println("bdsp rows=${bdsp.size} orphans before=${before.size} after=${after.size}")
        println("bdsp still unresolved: ${after.sorted()}")
        println("bdsp undirected edges=${edges.values.sumOf { it.size } / 2}")
        assertEquals("baseline drifted; expected the 39 reported orphans", 39, before.size)
        // 39 -> 11. The 11 left over are rows with no lumi_plat counterpart to
        // inherit an edge from (Grand Underground, Roaming Sinnoh, Hall of Origin,
        // Newmoon Island, Flower Paradise, Spear Pillar, Floaroma Meadow, Hearthome
        // City, Oreburgh Mining Museum) plus the "Pokémon League" / "Route 224"
        // pair, which lumi_plat spells "Pokemon League" without the accent.
        assertEquals("orphans after the fallback (was $before)", 11, after.size)
    }

    @Test fun bdspGraphBuildsAndLaysOutWithoutThrowing() {
        val keys = bdsp.map { it.lowercase() }
        val r = GraphLayout.layout(keys, PackAdjacency.forPack(bdsp))
        assertEquals(keys.toSet(), r.positions.keys)
        println("bdsp layout: n=${keys.size} iterations=${r.iterations} isolated=${r.isolated}")
        assertEquals("bdsp isolated column", 11, r.isolated.size)
    }

    @Test fun bdspStarterAreaIsWiredUp() {
        val e = PackAdjacency.forPack(bdsp)
        for (k in listOf("route 201", "route 202", "route 203", "route 204", "oreburgh gate", "oreburgh mine")) {
            println("$k -> ${e[k].orEmpty().sorted()}")
        }
        assertTrue("route 201 -> route 202", e["route 201"].orEmpty().contains("route 202"))
        assertTrue("route 202 -> route 203", e["route 202"].orEmpty().contains("route 203"))
        assertTrue("route 203 -> route 204", e["route 203"].orEmpty().contains("route 204"))
        // Oreburgh Gate inherits the union of its lumi_plat 1F/B1F halves' edges.
        assertTrue("oreburgh gate -> route 203", e["oreburgh gate"].orEmpty().contains("route 203"))
        assertTrue("oreburgh gate -> route 207", e["oreburgh gate"].orEmpty().contains("route 207"))
        assertTrue("oreburgh gate -> oreburgh mine", e["oreburgh gate"].orEmpty().contains("oreburgh mine"))
        // Route 204's two halves collapse, so it keeps both its south and north links.
        assertTrue("route 204 -> route 202", e["route 204"].orEmpty().contains("route 202"))
        assertTrue("route 204 -> route 205", e["route 204"].orEmpty().contains("route 205"))
    }

    @Test fun theFallbackNeverInventsANodeOutsideThePack() {
        val present = bdsp.map { it.lowercase() }.toSet()
        val edges = PackAdjacency.forPack(bdsp)
        val phantom = (edges.keys + edges.values.flatten()).filterNot { it in present }.sorted()
        assertEquals("edges naming rows bdsp does not have: $phantom", emptyList<String>(), phantom)
    }

    @Test fun bdspGraphIsMirrored() {
        val edges = PackAdjacency.forPack(bdsp)
        for ((from, tos) in edges) for (to in tos) {
            assertTrue("$to -> $from missing", edges[to].orEmpty().contains(from))
        }
    }
}
