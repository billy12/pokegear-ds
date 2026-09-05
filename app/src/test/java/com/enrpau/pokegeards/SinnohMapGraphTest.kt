package com.enrpau.pokegeards

import com.enrpau.pokegeards.detection.SinnohAdjacency
import com.enrpau.pokegeards.map.GraphLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Checks the two halves of the region map that can be tested without a device:
 * the hand-authored adjacency graph, and the force-directed layout that turns it
 * into coordinates.
 *
 * Reads the real lumi_plat/locations.csv rather than a copied-out list, so the
 * coverage assertion below actually tracks the shipped pack.
 */
class SinnohMapGraphTest {

    private val names: List<String> by lazy {
        val f = listOf(
            "src/main/assets/packs/lumi_plat/locations.csv",
            "app/src/main/assets/packs/lumi_plat/locations.csv",
        ).map { File(it) }.firstOrNull { it.isFile }
            ?: error("locations.csv not found from working dir ${File(".").absolutePath}")
        f.readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",")[1] }
    }

    private val keys get() = names.map { it.lowercase() }

    // ---- adjacency coverage --------------------------------------------------

    @Test fun everyPackLocationHasAtLeastOneEdge() {
        val missing = keys.filter { SinnohAdjacency.EDGES[it].isNullOrEmpty() }
        assertEquals("locations with no adjacency edge: $missing", emptyList<String>(), missing)
    }

    @Test fun everyDeclaredEdgeNamesARealPackRow() {
        // Guards against a typo silently creating a phantom node.
        val known = keys.toSet()
        val phantom = SinnohAdjacency.EDGES.keys.filterNot { it in known }.sorted()
        assertEquals("adjacency names not present in locations.csv: $phantom", emptyList<String>(), phantom)
    }

    @Test fun theGraphIsOneConnectedComponent() {
        val known = keys.toSet()
        val seen = HashSet<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(keys.first())
        seen.add(keys.first())
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            for (next in SinnohAdjacency.EDGES[cur].orEmpty()) {
                if (next in known && seen.add(next)) stack.addLast(next)
            }
        }
        val unreached = known - seen
        assertEquals("unreachable from ${keys.first()}: ${unreached.sorted()}", emptySet<String>(), unreached)
        println("adjacency: ${known.size} locations, " +
            "${SinnohAdjacency.EDGES.values.sumOf { it.size } / 2} undirected edges, 1 component")
    }

    // ---- layout --------------------------------------------------------------

    private fun run() = GraphLayout.layout(keys, SinnohAdjacency.EDGES)

    @Test fun layoutPlacesEveryNodeAndReportsItsRun() {
        val r = run()
        assertEquals(keys.toSet(), r.positions.keys)
        assertEquals("nothing should be isolated after the adjacency pass", emptyList<String>(), r.isolated)
        println(
            "layout: n=${keys.size} iterations=${r.iterations} " +
                "lastMaxDisplacement=${r.lastMaxDisplacement} " +
                "bounds=${r.width} x ${r.height}"
        )
        assertTrue("layout should settle, not run away", r.width > 0f && r.height > 0f)
    }

    @Test fun noTwoTilesLandOnTopOfEachOther() {
        val r = run()
        val pts = keys.map { it to r.positions.getValue(it) }
        // The de-overlap pass works on 40 x 18 boxes (the defaults), so no pair may
        // be inside that box on BOTH axes. Report the worst offender if one is.
        var worstName = ""
        var worstPenetration = 0.0
        for (i in pts.indices) {
            for (j in i + 1 until pts.size) {
                val (na, a) = pts[i]
                val (nb, b) = pts[j]
                val ox = 40.0 - abs(a.x - b.x)
                val oy = 18.0 - abs(a.y - b.y)
                val pen = minOf(ox, oy)
                if (pen > worstPenetration) { worstPenetration = pen; worstName = "$na / $nb" }
            }
        }
        println("worst box penetration: $worstPenetration ($worstName)")
        assertTrue("tiles overlap: $worstName by $worstPenetration", worstPenetration <= 0.01)

        // And a plain centre-distance floor, independent of the box logic.
        var minDist = Double.MAX_VALUE
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            val a = pts[i].second; val b = pts[j].second
            val d = sqrt(((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)).toDouble())
            if (d < minDist) minDist = d
        }
        println("minimum pairwise centre distance: $minDist")
        assertTrue("closest pair is $minDist apart", minDist > 15.0)
    }

    @Test fun layoutIsDeterministic() {
        val a = run()
        val b = run()
        assertEquals(a.iterations, b.iterations)
        assertEquals(a.positions, b.positions)
    }

    @Test fun neighboursEndUpCloserThanStrangers() {
        // The whole point of using the graph: connected areas should sit nearer
        // each other than a random pair does.
        val r = run()
        fun d(x: String, y: String): Double {
            val p = r.positions.getValue(x); val q = r.positions.getValue(y)
            return sqrt(((p.x - q.x) * (p.x - q.x) + (p.y - q.y) * (p.y - q.y)).toDouble())
        }
        var edgeSum = 0.0; var edgeN = 0
        for ((from, tos) in SinnohAdjacency.EDGES) for (to in tos) if (from < to) {
            edgeSum += d(from, to); edgeN++
        }
        var allSum = 0.0; var allN = 0
        for (i in keys.indices) for (j in i + 1 until keys.size) { allSum += d(keys[i], keys[j]); allN++ }
        val meanEdge = edgeSum / edgeN
        val meanAll = allSum / allN
        println("mean neighbour distance=$meanEdge  mean any-pair distance=$meanAll")
        assertTrue("neighbours ($meanEdge) not closer than average ($meanAll)", meanEdge < meanAll * 0.5)
    }

    @Test fun isolatedNodesAreParkedNotCrashed() {
        val nodes = keys + "made up place"
        val r = GraphLayout.layout(nodes, SinnohAdjacency.EDGES)
        assertEquals(listOf("made up place"), r.isolated)
        val parked = r.positions.getValue("made up place")
        val mainMaxX = keys.maxOf { r.positions.getValue(it).x }
        assertTrue("parked node should sit to the right of the map", parked.x > mainMaxX)
    }
}
