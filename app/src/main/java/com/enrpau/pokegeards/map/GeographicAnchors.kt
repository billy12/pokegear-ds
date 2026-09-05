package com.enrpau.pokegeards.map

import com.enrpau.pokegeards.detection.LocationResolver

/**
 * Where Sinnoh's named places actually are.
 *
 * Transcribed off a community-made overworld reference map of the region into a
 * fixed [REFERENCE_WIDTH] x [REFERENCE_HEIGHT] frame, x growing east and y growing
 * south — the same convention the canvas draws in, so no axis flip is needed
 * anywhere downstream. These are approximate positions of real game geography,
 * not a copy of anyone's artwork.
 *
 * Coverage is deliberately partial: towns, cities, lakes, named outdoor areas and
 * every numbered route — the things a real overworld map prints. Cave and building
 * interiors, floor rows ("Mt. Coronet - 4F (Waterfall)") and other sub-locations
 * have no entry here on purpose; [GraphLayout] walks
 * [com.enrpau.pokegeards.detection.SinnohAdjacency] to park those next to whichever
 * anchored place they connect to.
 *
 * Lookup goes exact name first, then [LocationResolver.baseName], so a pack that
 * splits a zone across rows ("Route 212 (North)" / "(South)") and one that does not
 * ("Route 212") both land on the same entry.
 */
object GeographicAnchors {

    /** The frame the coordinates below were read in. */
    const val REFERENCE_WIDTH = 863f
    const val REFERENCE_HEIGHT = 810f

    /** Lowercased place name -> point in the reference frame. */
    val ANCHORS: Map<String, LayoutPoint> = buildMap {
        fun at(name: String, x: Float, y: Float) = put(name.lowercase(), LayoutPoint(x, y))

        // --- towns and cities -------------------------------------------------
        at("Twinleaf Town", 70f, 585f)
        at("Sandgem Town", 119f, 588f)
        at("Jubilife City", 145f, 519f)
        at("Oreburgh City", 231f, 517f)
        at("Floaroma Town", 90f, 504f)
        at("Eterna City", 284f, 320f)
        at("Hearthome City", 427f, 462f)
        at("Solaceon Town", 537f, 459f)
        at("Veilstone City", 623f, 378f)
        at("Pastoria City", 537f, 574f)
        at("Celestic Town", 416f, 309f)
        at("Canalave City", 47f, 505f)
        at("Snowpoint City", 328f, 42f)
        at("Sunyshore City", 762f, 517f)
        at("Fight Area", 566f, 225f)
        at("Survival Area", 579f, 140f)
        at("Resort Area", 719f, 252f)

        // The League sits behind Victory Road. lumi_plat spells its row "Pokemon
        // League" and bdsp "Pokémon League"; both are the same building.
        at("Sinnoh League", 748f, 335f)
        at("Pokemon League", 748f, 335f)
        at("Pokémon League", 748f, 335f)

        // --- named outdoor areas, caves and landmarks -------------------------
        at("Lake Verity", 69f, 585f)
        at("Lake Valor", 617f, 517f)
        at("Lake Acuity", 285f, 50f)
        at("Oreburgh Gate", 213f, 505f)
        at("Valley Windworks", 159f, 406f)
        at("Eterna Forest", 201f, 322f)
        at("Wayward Cave", 271f, 376f)
        at("Mt. Coronet", 326f, 392f)
        at("Maniac Tunnel", 621f, 406f)
        at("Pal Park", 272f, 644f)
        at("Iron Island", 105f, 284f)
        at("Fuego Ironworks", 159f, 362f)
        at("Spear Pillar", 356f, 392f)
        at("Turnback Cave", 677f, 464f)
        at("Victory Road", 748f, 364f)
        at("Fullmoon Island", 48f, 88f)
        at("Newmoon Island", 133f, 88f)
        at("Stark Mountain", 663f, 55f)
        at("Flower Paradise", 803f, 25f)

        // --- routes -----------------------------------------------------------
        at("Route 201", 119f, 560f)
        at("Route 202", 159f, 560f)
        at("Route 203", 159f, 505f)
        // 204, 205 and 211 are split zones — see SPLITS. The point here is the
        // midpoint of the two halves, used when a pack has only one row for them.
        at("Route 204", 271f, 462f)
        at("Route 205", 217f, 350f)
        at("Route 206", 271f, 408f)
        at("Route 207", 297f, 476f)
        at("Route 208", 370f, 476f)
        at("Route 209", 483f, 476f)
        at("Route 210", 494f, 308f)
        at("Route 211", 356f, 309f)
        at("Route 212", 411f, 574f)
        at("Route 213", 608f, 574f)
        at("Route 214", 635f, 464f)
        at("Route 215", 549f, 367f)
        at("Route 216", 313f, 196f)
        at("Route 217", 272f, 125f)
        at("Route 218", 89f, 505f)
        at("Route 219", 89f, 616f)
        at("Route 220", 159f, 644f)
        at("Route 221", 215f, 644f)
        at("Route 222", 690f, 532f)
        at("Route 223", 748f, 432f)
        at("Route 224", 803f, 165f)
        at("Route 225", 551f, 166f)
        at("Route 226", 624f, 140f)
        at("Route 227", 663f, 100f)
        at("Route 228", 692f, 166f)
        at("Route 229", 718f, 224f)
        at("Route 230", 636f, 225f)
    }

    /**
     * One end of a zone a pack splits across two rows.
     *
     * [towards] holds the *base names* of places that end of the zone really
     * touches, so [splitPoints] can decide which row is which from the adjacency
     * graph instead of trusting the "(North)" / "(South)" in the string.
     */
    class Split(val point: LayoutPoint, val towards: Set<String>)

    /**
     * base name -> the two ends, in no meaningful order. Only the three zones the
     * reference map gives separate coordinates for; every other split zone
     * (Route 210, Route 212, Lake Verity's before/after rows, ...) shares one
     * anchor and gets spread locally by [GraphLayout].
     */
    val SPLITS: Map<String, List<Split>> = mapOf(
        // North half runs up to Floaroma; south half is the Jubilife end.
        "route 204" to listOf(
            Split(LayoutPoint(271f, 440f), setOf("route 205", "valley windworks")),
            Split(LayoutPoint(271f, 484f), setOf("route 202", "route 203", "route 218")),
        ),
        // South half is the Windworks/Fuego riverside; north half runs into Eterna
        // Forest.
        "route 205" to listOf(
            Split(LayoutPoint(188f, 391f), setOf("route 204", "valley windworks", "fuego ironworks")),
            Split(LayoutPoint(245f, 308f), setOf("eterna forest", "eterna city")),
        ),
        // West half is the Eterna side of Mt. Coronet, east half the Celestic side.
        "route 211" to listOf(
            Split(LayoutPoint(326f, 308f), setOf("eterna city", "eterna forest")),
            Split(LayoutPoint(386f, 309f), setOf("celestic town", "route 210")),
        ),
    )

    /** Exact name, then base name. Null for anything the reference map omits. */
    fun anchorFor(name: String): LayoutPoint? {
        val key = name.trim().lowercase()
        return ANCHORS[key] ?: ANCHORS[LocationResolver.baseName(key).lowercase()]
    }

    /** The base name [anchorFor] would key off, lowercased. */
    fun baseKey(name: String): String = LocationResolver.baseName(name.trim().lowercase()).lowercase()

    /**
     * Hand out the split ends of [base] to [rows] by asking [edges] which end each
     * row actually borders — "Route 204 (North)" is the north half because it
     * touches Route 205 and the Windworks, not because of the word in brackets.
     *
     * Returns an empty map when [base] is not split, or when the pack has fewer
     * than two rows for it (then the caller uses the single midpoint anchor).
     * Every row gets exactly one end: highest-scoring row/end pair wins first, and
     * anything still unmatched takes whatever end is left, in row order.
     */
    fun splitPoints(
        base: String,
        rows: List<String>,
        edges: Map<String, Set<String>>,
    ): Map<String, LayoutPoint> {
        val options = SPLITS[base] ?: return emptyMap()
        if (rows.size < 2) return emptyMap()

        fun score(row: String, o: Split): Int =
            edges[row].orEmpty().count { baseKey(it) in o.towards }

        // (row index, option index, score), best first; ties settled by position so
        // the answer never depends on map iteration order.
        val ranked = ArrayList<Triple<Int, Int, Int>>(rows.size * options.size)
        for (r in rows.indices) for (o in options.indices) ranked.add(Triple(r, o, score(rows[r], options[o])))
        ranked.sortWith(
            compareByDescending<Triple<Int, Int, Int>> { it.third }
                .thenBy { it.first }
                .thenBy { it.second },
        )

        val takenRow = BooleanArray(rows.size)
        val takenOpt = BooleanArray(options.size)
        val out = LinkedHashMap<String, LayoutPoint>()
        for ((r, o, s) in ranked) {
            if (s <= 0 || takenRow[r] || takenOpt[o]) continue
            takenRow[r] = true; takenOpt[o] = true
            out[rows[r]] = options[o].point
        }
        // Leftovers (a row that borders neither end, or a third row) fall through in
        // order rather than being dropped.
        var next = 0
        for (r in rows.indices) {
            if (takenRow[r]) continue
            while (next < options.size && takenOpt[next]) next++
            if (next >= options.size) break
            takenOpt[next] = true
            out[rows[r]] = options[next].point
        }
        return out
    }
}
