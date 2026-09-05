package com.enrpau.pokegeards.detection

/**
 * Turns [SinnohAdjacency] — which is keyed by lumi_plat's exact row names — into an
 * edge map keyed by *whatever the active pack calls its locations*.
 *
 * Why this exists: lumi_plat splits zones across rows ("Route 204 (South)" /
 * "Route 204 (North)", "Oreburgh Gate - 1F" / "- B1F") and [SinnohAdjacency] is
 * written against those exact strings. bdsp/locations.csv uses the unsplit base
 * names ("Route 204", "Oreburgh Gate"), which match no adjacency key at all, so the
 * map screen used to hand ~half the bdsp rows to [com.enrpau.pokegeards.map.GraphLayout]
 * with degree 0 and they were parked in the isolated column.
 *
 * Resolution order per location, deliberately exact-first so lumi_plat is untouched:
 *
 *  1. exact name hit in [SinnohAdjacency.EDGES], restricted to rows the pack has;
 *  2. only if that came back empty, a hit in [BASE_EDGES] — the same graph collapsed
 *     onto [LocationResolver.baseName]s, so "Route 204" inherits the *union* of both
 *     halves' neighbours — mapped back onto pack rows sharing that base name.
 *
 * A neighbour base name with no row in this pack (e.g. a lumi_plat-only interior) is
 * dropped rather than invented. The returned map is mirrored, like [SinnohAdjacency.EDGES].
 */
object PackAdjacency {

    /**
     * base name -> adjacent base names, lowercased and mirrored. Edges between two
     * halves of the same zone ("Route 204 (South)" <-> "(North)") collapse to a
     * self-edge and are dropped.
     */
    val BASE_EDGES: Map<String, Set<String>> = buildMap<String, MutableSet<String>> {
        for ((from, tos) in SinnohAdjacency.EDGES) {
            val a = LocationResolver.baseName(from)
            for (to in tos) {
                val b = LocationResolver.baseName(to)
                if (a == b) continue
                getOrPut(a) { mutableSetOf() }.add(b)
                getOrPut(b) { mutableSetOf() }.add(a)
            }
        }
    }

    /**
     * Edge map for one pack's location [names], keyed by those names lowercased —
     * the form [com.enrpau.pokegeards.map.GraphLayout] and MapCanvasView already use.
     * Locations that resolve to nothing simply have no key.
     */
    fun forPack(names: List<String>): Map<String, Set<String>> {
        val keys = names.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val present = keys.toSet()

        val out = LinkedHashMap<String, MutableSet<String>>()

        // Pass 1 — exact hits. Walked in [SinnohAdjacency.EDGES]'s own order, not
        // the pack's, because the force layout sums floats in edge order: a pack
        // that matches the adjacency map exactly (lumi_plat) has to get an
        // identically *ordered* map back, not merely an equal one, or every tile
        // moves a pixel. EDGES is already mirrored, so both directions fall out.
        for ((from, tos) in SinnohAdjacency.EDGES) {
            if (from !in present) continue
            for (to in tos) {
                if (to in present) out.getOrPut(from) { mutableSetOf() }.add(to)
            }
        }

        // Pass 2 — base-name fallback, for rows pass 1 could not place. Keyed off
        // pass 1's result rather than `out`, so a row that only got mirrored links
        // from someone else's fallback still runs its own and the answer does not
        // depend on iteration order.
        val resolved = out.keys.toSet()
        val unresolved = keys.filterNot { it in resolved }
        if (unresolved.isEmpty()) return out

        // base name -> the pack rows carrying it (2+ only when the pack splits, in
        // which case pass 1 normally answered anyway).
        val rowsByBase = HashMap<String, MutableList<String>>()
        for (k in keys) rowsByBase.getOrPut(LocationResolver.baseName(k)) { mutableListOf() }.add(k)

        fun link(a: String, b: String) {
            if (a == b) return
            out.getOrPut(a) { mutableSetOf() }.add(b)
            out.getOrPut(b) { mutableSetOf() }.add(a)
        }
        for (k in unresolved) {
            for (nb in BASE_EDGES[LocationResolver.baseName(k)].orEmpty()) {
                for (row in rowsByBase[nb].orEmpty()) link(k, row)
            }
        }
        return out
    }
}
