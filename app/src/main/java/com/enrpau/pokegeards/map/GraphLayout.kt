package com.enrpau.pokegeards.map

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
    /** Iterations actually run before the displacement cap was reached. */
    val iterations: Int,
    /** Largest single-node move on the final iteration. */
    val lastMaxDisplacement: Float,
    /** Nodes with no edge at all; parked in a column on the right (see below). */
    val isolated: List<String>,
)

/**
 * Plain-Kotlin Fruchterman-Reingold force-directed layout: every pair of nodes
 * repels, every edge pulls, the maximum move per step cools towards zero. Nodes
 * that are neighbours in the graph end up near each other, so feeding it a real
 * region's connectivity produces something that reads as a map without anyone
 * having authored a single coordinate.
 *
 * Deterministic by construction — the starting positions come from a fixed
 * circular sweep plus a fixed-seed LCG jitter (only there to break the perfect
 * symmetry the circle would otherwise keep), and nothing else consults a random
 * source. Same input, same output, every launch.
 *
 * No Android types, so it is unit-testable on the JVM.
 */
object GraphLayout {

    /** Fixed so re-running never reshuffles the map between app launches. */
    const val DEFAULT_SEED = 20260904L

    private const val MAX_ITERATIONS = 900

    /** Below this largest-single-move the layout has settled; stop early. */
    private const val SETTLED = 0.05

    /**
     * Lay [nodes] out using [edges] (name -> neighbour names, already mirrored,
     * e.g. `SinnohAdjacency.EDGES`). Node keys and edge keys must use the same
     * casing; callers normally lowercase both.
     *
     * @param nodeWidth  laid-out box width, used by the final de-overlap pass and
     *                   to pick the target edge length.
     * @param nodeHeight laid-out box height, same.
     * @param padding    extra clear space kept around each box.
     */
    fun layout(
        nodes: List<String>,
        edges: Map<String, Set<String>>,
        nodeWidth: Double = 40.0,
        nodeHeight: Double = 18.0,
        padding: Double = 6.0,
        seed: Long = DEFAULT_SEED,
    ): LayoutResult {
        if (nodes.isEmpty()) return LayoutResult(emptyMap(), 0f, 0f, 0, 0f, emptyList())

        val index = HashMap<String, Int>(nodes.size * 2)
        nodes.forEachIndexed { i, n -> index.putIfAbsent(n, i) }

        // Edge list restricted to nodes we were actually given, de-duplicated so a
        // mirrored map does not double every spring.
        val edgeA = ArrayList<Int>()
        val edgeB = ArrayList<Int>()
        for ((from, tos) in edges) {
            val a = index[from] ?: continue
            for (to in tos) {
                val b = index[to] ?: continue
                if (a < b) { edgeA.add(a); edgeB.add(b) }
            }
        }

        val degree = IntArray(nodes.size)
        for (i in edgeA.indices) { degree[edgeA[i]]++; degree[edgeB[i]]++ }

        val connected = nodes.indices.filter { degree[it] > 0 }
        val isolated = nodes.indices.filter { degree[it] == 0 }

        val xs = DoubleArray(nodes.size)
        val ys = DoubleArray(nodes.size)

        // ---- deterministic seeding ------------------------------------------
        val n = max(1, connected.size)
        val field = sqrt(n.toDouble()) * (nodeWidth + padding) * 1.6
        val radius = field * 0.45
        var lcg = seed
        fun jitter(): Double {
            // 64-bit LCG (Knuth's constants); only used for symmetry breaking.
            lcg = lcg * 6364136223846793005L + 1442695040888963407L
            return ((lcg ushr 11).toDouble() / (1L shl 53).toDouble()) - 0.5
        }
        connected.forEachIndexed { i, node ->
            val a = 2.0 * Math.PI * i / n
            xs[node] = field / 2 + radius * Math.cos(a) + jitter() * nodeWidth
            ys[node] = field / 2 + radius * Math.sin(a) + jitter() * nodeWidth
        }

        // ---- Fruchterman-Reingold -------------------------------------------
        val k = sqrt(field * field / n)          // ideal node separation
        val k2 = k * k
        val centreX = field / 2
        val centreY = field / 2
        var temperature = field / 8.0
        val cooling = 0.975
        val dx = DoubleArray(nodes.size)
        val dy = DoubleArray(nodes.size)

        var iterations = 0
        var lastMax = 0.0
        while (iterations < MAX_ITERATIONS) {
            iterations++
            java.util.Arrays.fill(dx, 0.0)
            java.util.Arrays.fill(dy, 0.0)

            // repulsion, every pair
            for (ii in connected.indices) {
                val a = connected[ii]
                for (jj in ii + 1 until connected.size) {
                    val b = connected[jj]
                    var vx = xs[a] - xs[b]
                    var vy = ys[a] - ys[b]
                    var d2 = vx * vx + vy * vy
                    if (d2 < 1e-6) {
                        // Exactly coincident: nudge along a stable, index-derived
                        // direction so the run stays deterministic.
                        vx = 1e-3 * (1 + (a % 7)); vy = 1e-3 * (1 + (b % 5))
                        d2 = vx * vx + vy * vy
                    }
                    val d = sqrt(d2)
                    val f = k2 / d
                    val ux = vx / d
                    val uy = vy / d
                    dx[a] += ux * f; dy[a] += uy * f
                    dx[b] -= ux * f; dy[b] -= uy * f
                }
            }

            // attraction, along edges
            for (e in edgeA.indices) {
                val a = edgeA[e]
                val b = edgeB[e]
                val vx = xs[a] - xs[b]
                val vy = ys[a] - ys[b]
                val d = sqrt(vx * vx + vy * vy).coerceAtLeast(1e-3)
                val f = d * d / k
                val ux = vx / d
                val uy = vy / d
                dx[a] -= ux * f; dy[a] -= uy * f
                dx[b] += ux * f; dy[b] += uy * f
            }

            // Weak pull to the centre. Without it a long, thin region like Sinnoh's
            // route chain drifts apart faster than the springs can hold it.
            for (a in connected) {
                dx[a] += (centreX - xs[a]) * 0.012
                dy[a] += (centreY - ys[a]) * 0.012
            }

            var maxMove = 0.0
            for (a in connected) {
                val d = sqrt(dx[a] * dx[a] + dy[a] * dy[a])
                if (d < 1e-9) continue
                val move = min(d, temperature)
                xs[a] += dx[a] / d * move
                ys[a] += dy[a] / d * move
                if (move > maxMove) maxMove = move
            }
            lastMax = maxMove
            temperature *= cooling
            if (maxMove < SETTLED) break
        }

        // ---- scale so a typical edge is about one tile wide ------------------
        if (edgeA.isNotEmpty()) {
            val lengths = DoubleArray(edgeA.size) {
                val a = edgeA[it]; val b = edgeB[it]
                sqrt((xs[a] - xs[b]) * (xs[a] - xs[b]) + (ys[a] - ys[b]) * (ys[a] - ys[b]))
            }
            lengths.sort()
            val median = lengths[lengths.size / 2].coerceAtLeast(1e-3)
            val target = (nodeWidth + padding) * 1.15
            val scale = target / median
            for (a in connected) { xs[a] *= scale; ys[a] *= scale }
        }

        // ---- de-overlap: separate any two boxes that still intersect ---------
        separate(connected, xs, ys, nodeWidth + padding, nodeHeight + padding)

        // ---- park the isolated nodes in a column on the right ----------------
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (a in connected) {
            minX = min(minX, xs[a]); maxX = max(maxX, xs[a])
            minY = min(minY, ys[a]); maxY = max(maxY, ys[a])
        }
        if (connected.isEmpty()) { minX = 0.0; minY = 0.0; maxX = 0.0; maxY = 0.0 }
        if (isolated.isNotEmpty()) {
            val step = nodeHeight + padding
            val colX = maxX + (nodeWidth + padding) * 1.5
            isolated.forEachIndexed { i, node ->
                xs[node] = colX + (nodeWidth + padding) * (i / 24)
                ys[node] = minY + step * (i % 24)
            }
            for (a in isolated) {
                minX = min(minX, xs[a]); maxX = max(maxX, xs[a])
                minY = min(minY, ys[a]); maxY = max(maxY, ys[a])
            }
        }

        // ---- translate to origin --------------------------------------------
        val out = HashMap<String, LayoutPoint>(nodes.size * 2)
        for ((node, i) in index) {
            out[node] = LayoutPoint((xs[i] - minX).toFloat(), (ys[i] - minY).toFloat())
        }
        return LayoutResult(
            positions = out,
            width = (maxX - minX).toFloat(),
            height = (maxY - minY).toFloat(),
            iterations = iterations,
            lastMaxDisplacement = lastMax.toFloat(),
            isolated = isolated.map { nodes[it] },
        )
    }

    /**
     * Push apart any two boxes that still overlap after the force pass. Force
     * layouts treat nodes as points, so two tiles can settle close enough that
     * their labels collide even though the graph is happy. Runs a fixed number of
     * passes and always moves the pair symmetrically, so it stays deterministic.
     */
    private fun separate(
        ids: List<Int>,
        xs: DoubleArray,
        ys: DoubleArray,
        boxW: Double,
        boxH: Double,
    ) {
        repeat(SEPARATION_PASSES) {
            var moved = false
            for (ii in ids.indices) {
                val a = ids[ii]
                for (jj in ii + 1 until ids.size) {
                    val b = ids[jj]
                    val ox = boxW - abs(xs[a] - xs[b])
                    if (ox <= 0) continue
                    val oy = boxH - abs(ys[a] - ys[b])
                    if (oy <= 0) continue
                    moved = true
                    // Resolve along whichever axis needs the smaller shove.
                    if (ox / boxW < oy / boxH) {
                        val s = if (xs[a] <= xs[b]) -ox / 2 else ox / 2
                        xs[a] += s; xs[b] -= s
                    } else {
                        val s = if (ys[a] <= ys[b]) -oy / 2 else oy / 2
                        ys[a] += s; ys[b] -= s
                    }
                }
            }
            if (!moved) return
        }
    }

    private const val SEPARATION_PASSES = 200
}
