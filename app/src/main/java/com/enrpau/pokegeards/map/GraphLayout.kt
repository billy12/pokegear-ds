package com.enrpau.pokegeards.map

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One laid-out node centre, in the layout's own arbitrary units. */
data class LayoutPoint(val x: Float, val y: Float)

/**
 * Positions plus enough diagnostics to assert on the run in a unit test.
 *
 * [positions] are translated so the tightest box around every node centre starts
 * at (0, 0); [width]/[height] are that box's size, so a caller can size a canvas
 * from the result without re-measuring.
 */
data class LayoutResult(
    val positions: Map<String, LayoutPoint>,
    val width: Float,
    val height: Float,
    /** De-overlap passes actually run before nothing moved any more. */
    val iterations: Int,
    /** Largest single-node move any de-overlap pass made. */
    val lastMaxDisplacement: Float,
    /** Nodes with no anchor and no path to one; parked in a column on the right. */
    val isolated: List<String>,
    /** How many nodes sat on a [GeographicAnchors] entry rather than being derived. */
    val anchored: Int = 0,
)

/**
 * Puts every area where it really is.
 *
 * Positions come from [GeographicAnchors] — transcribed real Sinnoh geography —
 * not from a simulation. An earlier version ran a Fruchterman-Reingold force
 * layout over the [com.enrpau.pokegeards.detection.SinnohAdjacency] graph, which
 * optimises for even edge lengths and has no reason to resemble a region: Sinnoh's
 * route graph is mostly long branching chains, so it came out as straight diagonal
 * strings of tiles around one clump. Nothing about that read as a map.
 *
 * Three steps:
 *
 *  1. every location the reference map names (exact or [GeographicAnchors.baseKey]
 *     match) goes straight onto its coordinate, scaled so one tile-plus-padding
 *     covers [REF_TILE_SPAN] units of the reference frame;
 *  2. everything else — cave floors, dungeon rooms, the ~100 interior rows a real
 *     overworld map never prints — is assigned to the nearest anchored location by
 *     breadth-first search over the adjacency graph, so Old Chateau's nine rooms
 *     end up on Eterna Forest and Mt. Coronet's floors on Mt. Coronet;
 *  3. everything sharing a point (an anchor plus whatever BFS hung off it, or two
 *     rows of one split zone) is spread over a small grid of cells around it,
 *     ordered nearest-first so anchored rows keep the middle and derived ones
 *     radiate outwards.
 *
 * A final [separate] pass nudges apart tiles from *different* anchors that still
 * overlap — a strictly local minimum-move fix for the handful of places the real
 * map draws closer together than a tile is wide, not a physics run over the graph.
 *
 * Deterministic: no random source, and every tie is broken by input order.
 * No Android types, so it is unit-testable on the JVM.
 */
object GraphLayout {

    /**
     * Reference-frame units covered by one tile plus its padding. Bigger = tiles
     * take up more of the region, so the map packs tighter and more neighbours
     * need separating; smaller = truer to the reference map but harder to read.
     */
    const val REF_TILE_SPAN = 26.0

    /** Enough to clear the overlaps a real anchor set produces; it converges well inside this. */
    private const val SEPARATION_PASSES = 400

    /** Overlap this small counts as cleared. */
    private const val CLEAR = 0.01

    /**
     * Lay [nodes] out using [edges] (name -> neighbour names, already mirrored,
     * e.g. `SinnohAdjacency.EDGES`). Node keys and edge keys must use the same
     * casing; callers normally lowercase both.
     *
     * @param nodeWidth  laid-out box width; also sets the reference-frame scale.
     * @param nodeHeight laid-out box height.
     * @param padding    extra clear space kept around each box.
     */
    fun layout(
        nodes: List<String>,
        edges: Map<String, Set<String>>,
        nodeWidth: Double = 40.0,
        nodeHeight: Double = 18.0,
        padding: Double = 6.0,
    ): LayoutResult {
        if (nodes.isEmpty()) return LayoutResult(emptyMap(), 0f, 0f, 0, 0f, emptyList())

        val index = HashMap<String, Int>(nodes.size * 2)
        nodes.forEachIndexed { i, n -> index.putIfAbsent(n, i) }

        val cellW = nodeWidth + padding
        val cellH = nodeHeight + padding
        val scale = cellW / REF_TILE_SPAN

        // ---- step 1: anchors -------------------------------------------------
        // Host point per node, in layout units. Null until something places it.
        val hostX = DoubleArray(nodes.size)
        val hostY = DoubleArray(nodes.size)
        val placed = BooleanArray(nodes.size)
        // Hops from the anchored node this one hangs off; 0 for anchored nodes.
        val depth = IntArray(nodes.size)

        // Split zones first, so "Route 204 (North)" gets its own end of the road
        // rather than the midpoint both halves would otherwise share.
        val splitPoints = HashMap<String, LayoutPoint>()
        for (base in GeographicAnchors.SPLITS.keys) {
            val rows = nodes.filter { GeographicAnchors.baseKey(it) == base }
            splitPoints.putAll(GeographicAnchors.splitPoints(base, rows, edges))
        }

        var anchoredCount = 0
        for ((node, i) in index) {
            val p = splitPoints[node] ?: GeographicAnchors.anchorFor(node) ?: continue
            hostX[i] = p.x * scale
            hostY[i] = p.y * scale
            placed[i] = true
            depth[i] = 0
            anchoredCount++
        }

        // ---- step 2: BFS out from every anchored node at once -----------------
        // Seeded in node order, so an unanchored node equidistant from two anchors
        // deterministically joins the earlier one.
        val queue = ArrayDeque<Int>()
        for (i in nodes.indices) if (placed[i]) queue.addLast(i)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (nb in edges[nodes[cur]].orEmpty()) {
                val j = index[nb] ?: continue
                if (placed[j]) continue
                placed[j] = true
                hostX[j] = hostX[cur]
                hostY[j] = hostY[cur]
                depth[j] = depth[cur] + 1
                queue.addLast(j)
            }
        }

        val isolated = nodes.indices.filter { !placed[it] }

        // ---- step 3: spread each shared point over a small grid ---------------
        val groups = LinkedHashMap<Long, MutableList<Int>>()
        for (i in nodes.indices) {
            if (!placed[i]) continue
            groups.getOrPut(key(hostX[i], hostY[i], cellW, cellH)) { mutableListOf() }.add(i)
        }

        val xs = DoubleArray(nodes.size)
        val ys = DoubleArray(nodes.size)
        for (members in groups.values) {
            // Anchored rows first (depth 0), then outwards by hop count; input order
            // settles the rest. Combined with the nearest-first cell order below,
            // that keeps the anchored tile on its real coordinate.
            val ordered = members.sortedWith(compareBy({ depth[it] }, { it }))
            val cells = cells(ordered.size, cellW, cellH)
            ordered.forEachIndexed { n, i ->
                val (ci, cj) = cells[n]
                xs[i] = hostX[i] + ci * cellW
                ys[i] = hostY[i] + cj * cellH
            }
        }

        // ---- separate anything from different anchors that still collides -----
        val connected = nodes.indices.filter { placed[it] }
        val (passes, worstMove) = separate(connected, xs, ys, cellW, cellH)

        // ---- park the unreachable nodes in a column on the right --------------
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (a in connected) {
            minX = min(minX, xs[a]); maxX = max(maxX, xs[a])
            minY = min(minY, ys[a]); maxY = max(maxY, ys[a])
        }
        if (connected.isEmpty()) { minX = 0.0; minY = 0.0; maxX = 0.0; maxY = 0.0 }
        if (isolated.isNotEmpty()) {
            val colX = maxX + cellW * 1.5
            isolated.forEachIndexed { i, node ->
                xs[node] = colX + cellW * (i / 24)
                ys[node] = minY + cellH * (i % 24)
            }
            for (a in isolated) {
                minX = min(minX, xs[a]); maxX = max(maxX, xs[a])
                minY = min(minY, ys[a]); maxY = max(maxY, ys[a])
            }
        }

        // ---- translate to origin ---------------------------------------------
        val out = HashMap<String, LayoutPoint>(nodes.size * 2)
        for ((node, i) in index) {
            out[node] = LayoutPoint((xs[i] - minX).toFloat(), (ys[i] - minY).toFloat())
        }
        return LayoutResult(
            positions = out,
            width = (maxX - minX).toFloat(),
            height = (maxY - minY).toFloat(),
            iterations = passes,
            lastMaxDisplacement = worstMove.toFloat(),
            isolated = isolated.map { nodes[it] },
            anchored = anchoredCount,
        )
    }

    /**
     * Where a node ends up relative to its anchor, expressed in the layout's own
     * units — the same value [layout] would produce, so a caller can ask "how far
     * did this tile get pushed off its real coordinate" without re-running.
     */
    fun scaleFor(nodeWidth: Double, padding: Double): Double = (nodeWidth + padding) / REF_TILE_SPAN

    /** Group key. Quantised so two anchors written the same way always collide. */
    private fun key(x: Double, y: Double, cellW: Double, cellH: Double): Long {
        val qx = Math.round(x / cellW * 1000.0)
        val qy = Math.round(y / cellH * 1000.0)
        return qx * 1_000_003L + qy
    }

    /**
     * Cell offsets for a group of [n], nearest the centre first. Distance is
     * measured in laid-out units, not cells, so a wide-and-short tile produces a
     * roughly round cluster (more rows than columns) instead of a wide streak.
     */
    private fun cells(n: Int, cellW: Double, cellH: Double): List<Pair<Int, Int>> {
        if (n <= 1) return listOf(0 to 0)
        val r = min(n, 32)
        val out = ArrayList<Pair<Int, Int>>((2 * r + 1) * (2 * r + 1))
        for (i in -r..r) for (j in -r..r) out.add(i to j)
        out.sortWith(
            compareBy(
                { (it.first * cellW) * (it.first * cellW) + (it.second * cellH) * (it.second * cellH) },
                { abs(it.first) },
                { abs(it.second) },
                { it.first },
                { it.second },
            ),
        )
        return out.subList(0, n)
    }

    /**
     * Push apart any two boxes that overlap. Only pairs that actually intersect
     * move, and only by the smaller of the two shoves that would clear them, so a
     * tile sitting on its anchor with nothing near it never moves at all. Returns
     * (passes run, largest single move).
     */
    private fun separate(
        ids: List<Int>,
        xs: DoubleArray,
        ys: DoubleArray,
        boxW: Double,
        boxH: Double,
    ): Pair<Int, Double> {
        var worst = 0.0
        for (pass in 1..SEPARATION_PASSES) {
            var moved = false
            for (ii in ids.indices) {
                val a = ids[ii]
                for (jj in ii + 1 until ids.size) {
                    val b = ids[jj]
                    // CLEAR, not 0: two boxes that touch to within a rounding error
                    // are apart, and chasing that last fraction never converges.
                    val ox = boxW - abs(xs[a] - xs[b])
                    if (ox <= CLEAR) continue
                    val oy = boxH - abs(ys[a] - ys[b])
                    if (oy <= CLEAR) continue
                    moved = true
                    // Resolve along whichever axis needs the smaller shove.
                    if (ox / boxW < oy / boxH) {
                        val s = if (xs[a] <= xs[b]) -ox / 2 else ox / 2
                        xs[a] += s; xs[b] -= s
                        worst = max(worst, abs(s))
                    } else {
                        val s = if (ys[a] <= ys[b]) -oy / 2 else oy / 2
                        ys[a] += s; ys[b] -= s
                        worst = max(worst, abs(s))
                    }
                }
            }
            if (!moved) return pass to worst
        }
        return SEPARATION_PASSES to worst
    }
}
