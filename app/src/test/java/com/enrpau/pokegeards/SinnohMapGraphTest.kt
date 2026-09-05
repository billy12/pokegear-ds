package com.enrpau.pokegeards

import com.enrpau.pokegeards.detection.SinnohAdjacency
import com.enrpau.pokegeards.map.GeographicAnchors
import com.enrpau.pokegeards.map.GraphLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Checks the two halves of the region map that can be tested without a device:
 * the hand-authored adjacency graph, and the anchor-based layout that turns it
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

    /** Layout units per reference-frame unit, for the default box size. */
    private val scale = GraphLayout.scaleFor(40.0, 6.0)

    private val CELL_W = 46.0   // nodeWidth + padding, the defaults
    private val CELL_H = 24.0   // nodeHeight + padding

    @Test fun layoutPlacesEveryNodeAndReportsItsRun() {
        val r = run()
        assertEquals(keys.toSet(), r.positions.keys)
        assertEquals("nothing should be isolated after the adjacency pass", emptyList<String>(), r.isolated)
        println(
            "layout: n=${keys.size} anchored=${r.anchored} derived=${keys.size - r.anchored} " +
                "separationPasses=${r.iterations} worstNudge=${r.lastMaxDisplacement} " +
                "bounds=${r.width} x ${r.height}"
        )
        assertTrue("layout should have real extent", r.width > 0f && r.height > 0f)
        // Most of the pack is cave floors and dungeon rooms, which the reference
        // map does not name; the anchored share is the towns/routes/landmarks.
        assertTrue("too few anchored rows: ${r.anchored}", r.anchored >= 50)
    }

    @Test fun noTwoTilesLandOnTopOfEachOther() {
        val r = run()
        val pts = keys.map { it to r.positions.getValue(it) }
        // Tiles are laid out (and separated) as CELL_W x CELL_H boxes, so no pair
        // may be inside that box on BOTH axes. Report the worst offender if one is.
        var worstName = ""
        var worstPenetration = 0.0
        for (i in pts.indices) {
            for (j in i + 1 until pts.size) {
                val (na, a) = pts[i]
                val (nb, b) = pts[j]
                val ox = CELL_W - abs(a.x - b.x)
                val oy = CELL_H - abs(a.y - b.y)
                val pen = minOf(ox, oy)
                if (pen > worstPenetration) { worstPenetration = pen; worstName = "$na / $nb" }
            }
        }
        println("worst box penetration: $worstPenetration ($worstName)")
        assertTrue("tiles overlap: $worstName by $worstPenetration", worstPenetration <= 0.05)

        // And a plain centre-distance floor, independent of the box logic.
        var minDist = Double.MAX_VALUE
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            val a = pts[i].second; val b = pts[j].second
            val d = sqrt(((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)).toDouble())
            if (d < minDist) minDist = d
        }
        println("minimum pairwise centre distance: $minDist")
        assertTrue("closest pair is $minDist apart", minDist > CELL_H * 0.9)
    }

    @Test fun layoutIsDeterministic() {
        val a = run()
        val b = run()
        assertEquals(a.iterations, b.iterations)
        assertEquals(a.positions, b.positions)
    }

    // ---- the anchors are the layout ------------------------------------------

    /** Rows the reference map names, and the point each one asked for. */
    private fun anchoredKeys(): List<Pair<String, com.enrpau.pokegeards.map.LayoutPoint>> =
        keys.mapNotNull { k -> GeographicAnchors.anchorFor(k)?.let { k to it } }

    /**
     * Positions are translated to the origin, so the anchor -> layout offset is
     * only knowable up to a constant. Take the median residual as that constant
     * and measure everyone's drift from it.
     */
    private fun drifts(): List<Pair<String, Double>> {
        val r = run()
        val res = anchoredKeys().map { (k, a) ->
            val p = r.positions.getValue(k)
            Triple(k, p.x - a.x * scale, p.y - a.y * scale)
        }
        val ox = res.map { it.second }.sorted()[res.size / 2]
        val oy = res.map { it.third }.sorted()[res.size / 2]
        return res.map { (k, x, y) ->
            k to sqrt((x - ox) * (x - ox) + (y - oy) * (y - oy))
        }
    }

    @Test fun everyAnchoredLocationSitsOnItsAnchor() {
        val d = drifts().sortedByDescending { it.second }
        println("anchored rows: ${d.size}")
        println("worst drift from anchor (layout units, cell is $CELL_W x $CELL_H):")
        d.take(6).forEach { (k, v) -> println("  $k  ${"%.1f".format(v)}") }
        // A row sharing its anchor with others (Turnback Cave's 21 rooms, Mt.
        // Coronet's 12 floors) is pushed out to its cell in the local cluster; a
        // few cells is the whole budget.
        assertTrue("worst drift ${d.first()} exceeds 3 cells", d.first().second <= 3.0 * CELL_W)
    }

    @Test fun aRowThatOwnsItsAnchorAloneDoesNotMove() {
        // Anchors only one pack row resolves to have nothing to make room for, so
        // they should be within a nudge of their exact transcribed coordinate.
        val byAnchor = anchoredKeys().groupBy { it.second }
        val alone = byAnchor.filter { it.value.size == 1 }.map { it.value.first().first }.toSet()
        val d = drifts().filter { it.first in alone }.sortedByDescending { it.second }
        println("${alone.size} rows own their anchor outright; worst: ${d.take(4)}")
        assertTrue("a sole occupant drifted: ${d.first()}", d.first().second <= 1.0 * CELL_W)
    }

    @Test fun laidOutDistancesTrackRealSinnohDistances() {
        // The point of the rewrite: how far apart two places end up on screen has
        // to track how far apart they really are. Correlate every anchored pair.
        val r = run()
        val a = anchoredKeys()
        val real = ArrayList<Double>()
        val drawn = ArrayList<Double>()
        for (i in a.indices) for (j in i + 1 until a.size) {
            val (ka, pa) = a[i]; val (kb, pb) = a[j]
            real.add(sqrt(((pa.x - pb.x) * (pa.x - pb.x) + (pa.y - pb.y) * (pa.y - pb.y)).toDouble()) * scale)
            val qa = r.positions.getValue(ka); val qb = r.positions.getValue(kb)
            drawn.add(sqrt(((qa.x - qb.x) * (qa.x - qb.x) + (qa.y - qb.y) * (qa.y - qb.y)).toDouble()))
        }
        val mr = real.average(); val md = drawn.average()
        var cov = 0.0; var vr = 0.0; var vd = 0.0
        for (i in real.indices) {
            cov += (real[i] - mr) * (drawn[i] - md)
            vr += (real[i] - mr) * (real[i] - mr)
            vd += (drawn[i] - md) * (drawn[i] - md)
        }
        val rho = cov / sqrt(vr * vd)
        println("anchored-pair distance correlation with the reference map: ${"%.4f".format(rho)}")
        assertTrue("layout no longer tracks real geography (rho=$rho)", rho > 0.98)
    }

    @Test fun theRegionIsOrientedLikeSinnoh() {
        val r = run()
        fun p(k: String) = r.positions.getValue(k)
        fun d(x: String, y: String): Double {
            val a = p(x); val b = p(y)
            return sqrt(((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)).toDouble())
        }
        // South-west starter corner hangs together...
        assertTrue("Twinleaf should be nearer Route 201 than Sunyshore",
            d("twinleaf town", "route 201") < d("twinleaf town", "sunyshore city"))
        assertTrue("Route 201 should be nearer Route 202 than Route 217",
            d("route 201", "route 202") < d("route 201", "route 217"))
        // ...the north stays north...
        assertTrue("Route 217 (Snowpoint) should be north of Route 220 (south coast)",
            p("route 217").y < p("route 220").y)
        assertTrue("Acuity Lakefront should be north of Pastoria",
            p("acuity lakefront").y < p("pastoria city").y)
        // ...and the west stays west.
        assertTrue("Canalave should be west of Sunyshore",
            p("canalave city").x < p("sunyshore city").x)
        assertTrue("Route 218 should be west of Route 222",
            p("route 218").x < p("route 222").x)
    }

    @Test fun splitRoutesTakeTheEndTheyActuallyBorder() {
        val r = run()
        fun p(k: String) = r.positions.getValue(k)
        // Resolved from SinnohAdjacency, not from the word in the brackets.
        assertTrue("Route 204 (North) should sit north of (South)",
            p("route 204 (north)").y < p("route 204 (south)").y)
        assertTrue("Route 205 (North) should sit north of (South)",
            p("route 205 (north)").y < p("route 205 (south)").y)
        assertTrue("Route 211 (West) should sit west of (East)",
            p("route 211 (west)").x < p("route 211 (east)").x)
        // And each half should land beside the place it borders.
        fun d(x: String, y: String): Double {
            val a = p(x); val b = p(y)
            return sqrt(((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)).toDouble())
        }
        assertTrue("Route 205 (North) belongs by Eterna Forest",
            d("route 205 (north)", "eterna forest") < d("route 205 (south)", "eterna forest"))
        assertTrue("Route 211 (West) belongs by Eterna City",
            d("route 211 (west)", "eterna city") < d("route 211 (east)", "eterna city"))
        assertTrue("Route 211 (East) belongs by Celestic Town",
            d("route 211 (east)", "celestic town") < d("route 211 (west)", "celestic town"))
    }

    @Test fun interiorsClusterOnTheirNearestNamedPlace() {
        val r = run()
        fun d(x: String, y: String): Double {
            val a = r.positions.getValue(x); val b = r.positions.getValue(y)
            return sqrt(((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)).toDouble())
        }
        val derived = keys.filter { GeographicAnchors.anchorFor(it) == null }
        println("${derived.size} rows have no anchor and were placed from the graph")
        assertTrue("expected the interior rows to be the unanchored ones", derived.size > 40)
        // The Old Chateau is off Eterna Forest; the Great Marsh is Pastoria's.
        for (room in derived.filter { it.startsWith("old chateau") }) {
            assertTrue("$room drifted away from Eterna Forest",
                d(room, "eterna forest") < d(room, "pastoria city"))
        }
        for (block in derived.filter { it.startsWith("great marsh") }) {
            assertTrue("$block drifted away from Pastoria",
                d(block, "pastoria city") < d(block, "eterna forest"))
        }
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
