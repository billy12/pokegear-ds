package com.enrpau.pokegeards.detection

/**
 * Hand-authored "which zones touch which" map for Sinnoh. Two consumers:
 *
 *  1. [LocationResolver], to disambiguate a route banner that resolves to a split
 *     family (e.g. "Route 204" -> both "Route 204 (South)" and "Route 204 (North)");
 *  2. the region map screen, which feeds the same edge list to a force-directed
 *     graph layout so the tiles land in roughly their real geographic relation.
 *
 * Keyed by the exact `name` string in a pack's locations.csv so it stays readable
 * and a new edge is one line. Edges are declared once and mirrored automatically
 * by [EDGES].
 *
 * Modelling note — *effective* adjacency: several real connections run through a
 * town/city that has no wild encounters and therefore has no row in
 * lumi_plat/locations.csv (Jubilife City, Sandgem Town, Oreburgh City, Floaroma
 * Town, Hearthome City, Solaceon Town, Veilstone City, Snowpoint City, Fight Area,
 * Survival Area). Those connectors are treated as transparent: the two encounter
 * zones on either side are declared adjacent. Anything else would leave the graph
 * disconnected for exactly the cases this map exists to solve.
 *
 * Coverage: every one of the 158 rows in lumi_plat/locations.csv appears at least
 * once below, and the result is a single connected component (asserted by
 * SinnohAdjacencyTest). Interiors whose real room order the game randomises or
 * that lumi_plat names by raw map file (Turnback Cave's d17r01xx rooms, the
 * Solaceon Ruins letter rooms) are chained in a plausible order rather than an
 * exact one — see the comments at those blocks.
 */
object SinnohAdjacency {

    /** Undirected edges, declared one way. See [EDGES] for the mirrored lookup. */
    private val DECLARED: List<Pair<String, String>> = listOf(
        // --- Route 203 / Oreburgh corner -------------------------------------
        // Route 202 - Jubilife City - Route 203 (Jubilife is transparent).
        "Route 202" to "Route 203",
        // Route 203 runs east from Jubilife straight into the Oreburgh Gate mouth.
        "Route 203" to "Oreburgh Gate - 1F",
        // Route 204's south mouth is Jubilife's north exit (Jubilife transparent).
        "Route 202" to "Route 204 (South)",
        "Route 203" to "Route 204 (South)",
        // Oreburgh Gate interior.
        "Oreburgh Gate - 1F" to "Oreburgh Gate - B1F",
        // B1F's far exit drops out onto Route 207.
        "Oreburgh Gate - B1F" to "Route 207",
        // Oreburgh Gate - 1F - Oreburgh City - Oreburgh Mine (city transparent).
        "Oreburgh Gate - 1F" to "Oreburgh Mine B1F",
        "Oreburgh Mine B1F" to "Oreburgh Mine B2F",
        // Oreburgh City also opens onto Route 207.
        "Oreburgh Mine B1F" to "Route 207",

        // --- Route 204 / 205 / Eterna corner ---------------------------------
        // The two halves of 204 are one road split by Ravaged Path (transparent).
        "Route 204 (South)" to "Route 204 (North)",
        // Route 204's north end is Floaroma Town, which is also Route 205's south
        // end and the way to Valley Windworks (Floaroma transparent).
        "Route 204 (North)" to "Route 205 (South)",
        "Route 204 (North)" to "Valley Windworks (Outside)",
        "Route 205 (South)" to "Valley Windworks (Outside)",
        // Route 205's two halves meet at Fuego Ironworks / the river bend.
        "Route 205 (South)" to "Route 205 (North)",
        "Route 205 (South)" to "Fuego Ironworks (Outside)",
        // North 205 runs up to Eterna Forest and out into Eterna City.
        "Route 205 (North)" to "Eterna Forest",
        "Eterna Forest" to "Eterna City",
        "Eterna City" to "Route 206",
        "Eterna City" to "Route 211 (West)",
        // Route 206 (Cycling Road) - Wayward Cave sits under it, and its south end
        // is Route 207.
        "Route 206" to "Wayward Cave - Main Area",
        "Wayward Cave - Main Area" to "Wayward Cave - Secret Area",
        "Route 206" to "Route 207",
        "Route 207" to "Mt. Coronet - Route 207 Entrance",

        // --- Twinleaf / Sandgem / the south-west coast -----------------------
        "Twinleaf Town" to "Route 201",
        // Lake Verity sits just off Route 201; (Before)/(After) are the same place
        // in two story states.
        "Route 201" to "Lake Verity (Before)",
        "Route 201" to "Lake Verity (After)",
        "Lake Verity (Before)" to "Lake Verity (After)",
        // Sandgem Town is transparent: Route 201 east, Route 202 north, 219 south.
        "Route 201" to "Route 202",
        "Route 201" to "Route 219",
        "Route 202" to "Route 219",
        "Route 219" to "Route 220",
        "Route 220" to "Route 221",

        // --- Jubilife hub (transparent) and the run west to Canalave ---------
        "Route 202" to "Route 218",
        "Route 203" to "Route 218",
        "Route 204 (South)" to "Route 218",
        "Route 218" to "Canalave City",
        // Canalave's dock ferries you to Iron Island.
        "Canalave City" to "Iron Island (Overworld)",
        "Iron Island (Overworld)" to "Iron Island - 1F",
        "Iron Island - 1F" to "Iron Island - B1F Left",
        "Iron Island - 1F" to "Iron Island - B1F Right",
        "Iron Island - B1F Left" to "Iron Island - B2F Left (Riley's Room)",
        "Iron Island - B1F Right" to "Iron Island - B2F Right",
        "Iron Island - B2F Right" to "Iron Island - B3F",
        "Iron Island - B2F Left (Riley's Room)" to "Iron Island - B3F",

        // --- Ravaged Path ----------------------------------------------------
        // It really is a row in lumi_plat, so give it its own node as well as
        // keeping the direct 204 South/North edge the resolver already relies on.
        "Route 204 (South)" to "Ravaged Path",
        "Route 204 (North)" to "Ravaged Path",

        // --- Eterna Forest / Old Chateau -------------------------------------
        "Eterna Forest" to "Old Chateau - Lobby",
        "Old Chateau - Lobby" to "Old Chateau - Dining Room",
        "Old Chateau - Lobby" to "Old Chateau - 2F Hallway",
        "Old Chateau - 2F Hallway" to "Old Chateau - 2F Small Rooms",
        // The five numbered rooms open off the upstairs hallway.
        "Old Chateau - 2F Hallway" to "Old Chateau - Hallway Room 1",
        "Old Chateau - 2F Hallway" to "Old Chateau - Hallway Room 2",
        "Old Chateau - 2F Hallway" to "Old Chateau - Hallway Room 3",
        "Old Chateau - 2F Hallway" to "Old Chateau - Hallway Room 4",
        "Old Chateau - 2F Hallway" to "Old Chateau - Hallway Room 5",

        // --- Mt. Coronet -----------------------------------------------------
        // South half: in from Route 207, down to B1F, out onto Route 208 (the
        // usual Oreburgh -> Hearthome crossing).
        "Mt. Coronet - Route 207 Entrance" to "Mt. Coronet - B1F",
        "Mt. Coronet - B1F" to "Route 208",
        // Up the mountain.
        "Mt. Coronet - Route 207 Entrance" to "Mt. Coronet - 2F",
        "Mt. Coronet - 2F" to "Mt. Coronet - 3F",
        "Mt. Coronet - 3F" to "Mt. Coronet - 4F (Waterfall)",
        "Mt. Coronet - 4F (Waterfall)" to "Mt. Coronet - 4F (Towards Spear Pillar)",
        "Mt. Coronet - 4F (Towards Spear Pillar)" to "Mt. Coronet - 5F",
        "Mt. Coronet - 5F" to "Mt. Coronet - 6F",
        "Mt. Coronet - 6F" to "Mt. Coronet - Summit",
        "Mt. Coronet - Summit" to "Mt. Coronet - Snow Area",
        "Mt. Coronet - Snow Area" to "Mt. Coronet - Route 216 Entrance",
        "Mt. Coronet - Route 216 Entrance" to "Route 216",
        // Mid-mountain east/west crossing at the Route 211 line.
        "Route 211 (West)" to "Mt. Coronet - Route 211 Entrance",
        "Mt. Coronet - Route 211 Entrance" to "Mt. Coronet - Tunnel to Route 211 Entrance",
        "Mt. Coronet - Tunnel to Route 211 Entrance" to "Route 211 (East)",
        "Mt. Coronet - Route 211 Entrance" to "Mt. Coronet - 2F",

        // --- Hearthome hub (transparent): 208 / 209 / 212 North --------------
        "Route 208" to "Route 209",
        "Route 208" to "Route 212 (North)",
        "Route 209" to "Route 212 (North)",
        // Lost Tower stands beside Route 209.
        "Route 209" to "Lost Tower - 1F",
        "Lost Tower - 1F" to "Lost Tower - 2F",
        "Lost Tower - 2F" to "Lost Tower - 3F",
        "Lost Tower - 3F" to "Lost Tower - 4F",
        "Lost Tower - 4F" to "Lost Tower - 5F",

        // --- Solaceon Town (transparent) -------------------------------------
        "Route 209" to "Route 210 (South)",
        "Route 210 (South)" to "Solaceon Ruins - 2F",
        // Route 210's two halves meet at the fog gate; the north half runs west
        // into Celestic Town.
        "Route 210 (South)" to "Route 210 (North)",
        "Route 210 (North)" to "Celestic Town",
        "Celestic Town" to "Route 211 (East)",

        // Solaceon Ruins: lumi_plat names the rooms by their Unown letter, and the
        // maze's real branching order is not recoverable from the names alone. The
        // dead ends are attached to the room they are named after (that part is
        // certain); the spine below is a plausible chain, not a verified one.
        "Solaceon Ruins - 2F" to "Solaceon Ruins - 1F Dead End (NW)",
        "Solaceon Ruins - 2F" to "Solaceon Ruins - 1F Dead End (SE)",
        "Solaceon Ruins - 2F" to "Solaceon Ruins - I Room",
        "Solaceon Ruins - I Room" to "Solaceon Ruins - R Room",
        "Solaceon Ruins - R Room" to "Solaceon Ruins - E Room",
        "Solaceon Ruins - E Room" to "Solaceon Ruins - N Room",
        "Solaceon Ruins - N Room" to "Solaceon Ruins - D Room",
        "Solaceon Ruins - D Room" to "Solaceon Ruins - F Room",
        "Solaceon Ruins - I Room" to "Solaceon Ruins - I Room Dead End (SE)",
        "Solaceon Ruins - R Room" to "Solaceon Ruins - R Room Dead End (NW)",
        "Solaceon Ruins - R Room" to "Solaceon Ruins - R Room Dead End (SW)",
        "Solaceon Ruins - E Room" to "Solaceon Ruins - E Room Dead End (SW)",
        "Solaceon Ruins - E Room" to "Solaceon Ruins - E Room Dead End (SE)",
        "Solaceon Ruins - N Room" to "Solaceon Ruins - N Room Dead End (SE)",
        "Solaceon Ruins - N Room" to "Solaceon Ruins - N Room Dead End (NW)",
        "Solaceon Ruins - F Room" to "Solaceon Ruins - F Room Dead End (NE)",
        "Solaceon Ruins - F Room" to "Solaceon Ruins - F Room Dead End (SE)",

        // --- Veilstone (transparent): 210 South / 215 / 214 ------------------
        "Route 210 (South)" to "Route 215",
        "Route 215" to "Route 214",
        // Ruin Maniac Cave burrows from Route 214 towards the Solaceon Ruins.
        "Route 214" to "Ruin Maniac Cave - Small",
        "Ruin Maniac Cave - Small" to "Ruin Maniac Cave - Large",
        "Ruin Maniac Cave - Large" to "Maniac Tunnel",
        "Maniac Tunnel" to "Solaceon Ruins - 2F",
        // Spring Path (no encounters, no row) links Route 214 to Sendoff Spring.
        "Route 214" to "Sendoff Spring",
        "Sendoff Spring" to "Turnback Cave - Entrance",

        // Turnback Cave: the game shuffles which room you get, and lumi_plat names
        // them by raw map file id. Chained in file order purely so the graph is
        // connected and they cluster together — this is NOT the in-game order.
        "Turnback Cave - Entrance" to "Turnback Cave - d17r0103",
        "Turnback Cave - d17r0103" to "Turnback Cave - d17r0104",
        "Turnback Cave - d17r0104" to "Turnback Cave - d17r0105",
        "Turnback Cave - d17r0105" to "Turnback Cave - d17r0106",
        "Turnback Cave - d17r0106" to "Turnback Cave - d17r0107",
        "Turnback Cave - d17r0107" to "Turnback Cave - d17r0108",
        "Turnback Cave - d17r0108" to "Turnback Cave - d17r0109",
        "Turnback Cave - d17r0109" to "Turnback Cave - d17r0110",
        "Turnback Cave - d17r0110" to "Turnback Cave - d17r0111",
        "Turnback Cave - d17r0111" to "Turnback Cave - d17r0112",
        "Turnback Cave - d17r0112" to "Turnback Cave - d17r0113",
        "Turnback Cave - d17r0113" to "Turnback Cave - d17r0114",
        "Turnback Cave - d17r0114" to "Turnback Cave - d17r0115",
        "Turnback Cave - d17r0115" to "Turnback Cave - d17r0116",
        "Turnback Cave - d17r0116" to "Turnback Cave - d17r0117",
        "Turnback Cave - d17r0117" to "Turnback Cave - d17r0118",
        "Turnback Cave - d17r0118" to "Turnback Cave - d17r0119",
        "Turnback Cave - d17r0119" to "Turnback Cave - d17r0120",
        "Turnback Cave - d17r0120" to "Turnback Cave - d17r0121",
        "Turnback Cave - d17r0121" to "Turnback Cave - d17r0122",

        // --- Valor Lakefront / Pastoria / the south coast --------------------
        "Route 214" to "Valor Lakefront",
        "Valor Lakefront" to "Lake Valor (After)",
        "Valor Lakefront" to "Route 213",
        "Valor Lakefront" to "Route 222",
        "Route 213" to "Pastoria City",
        "Pastoria City" to "Route 212 (South)",
        "Route 212 (South)" to "Route 212 (North)",
        "Route 212 (North)" to "Trophy Garden",
        // The Great Marsh's six blocks are all entered from Pastoria's Safari gate
        // and sit side by side.
        "Pastoria City" to "Great Marsh - Area 1",
        "Pastoria City" to "Great Marsh - Area 2",
        "Pastoria City" to "Great Marsh - Area 3",
        "Pastoria City" to "Great Marsh - Area 4",
        "Pastoria City" to "Great Marsh - Area 5",
        "Pastoria City" to "Great Marsh - Area 6",
        "Great Marsh - Area 1" to "Great Marsh - Area 2",
        "Great Marsh - Area 2" to "Great Marsh - Area 3",
        "Great Marsh - Area 3" to "Great Marsh - Area 4",
        "Great Marsh - Area 4" to "Great Marsh - Area 5",
        "Great Marsh - Area 5" to "Great Marsh - Area 6",

        // --- Sunyshore / Victory Road / the League ---------------------------
        "Route 222" to "Sunyshore City",
        "Sunyshore City" to "Route 223",
        "Route 223" to "Victory Road - 1F",
        "Victory Road - 1F" to "Victory Road - 2F",
        "Victory Road - 2F" to "Victory Road - B1F",
        "Victory Road - B1F" to "Pokemon League",
        "Route 223" to "Pokemon League",
        // Post-game back half of Victory Road.
        "Victory Road - 1F" to "Victory Road - 1F Back (Entrance)",
        "Victory Road - 1F Back (Entrance)" to "Victory Road - 1F Back (Marley)",
        "Victory Road - 1F Back (Entrance)" to "Victory Road - 1F Back (214 Exit)",
        // Route 224 is behind the League building.
        "Pokemon League" to "Route 224",

        // --- Snowpoint corner ------------------------------------------------
        "Route 216" to "Route 217",
        "Route 217" to "Acuity Lakefront",
        "Acuity Lakefront" to "Lake Acuity (After)",
        // Snowpoint City itself is transparent.
        "Acuity Lakefront" to "Snowpoint Temple - 1F",
        "Snowpoint Temple - 1F" to "Snowpoint Temple - B1F",
        "Snowpoint Temple - B1F" to "Snowpoint Temple - B2F",
        "Snowpoint Temple - B2F" to "Snowpoint Temple - B3F",
        "Snowpoint Temple - B3F" to "Snowpoint Temple - B4F",
        "Snowpoint Temple - B4F" to "Snowpoint Temple - B5F",

        // --- Battle Zone -----------------------------------------------------
        // Reached by the Snowpoint ferry; both Snowpoint City and the Fight Area
        // are transparent, so the mainland link is Acuity Lakefront <-> Route 225.
        "Acuity Lakefront" to "Route 225",
        "Route 225" to "Route 226",
        // Survival Area (transparent) joins 226 / 227 / 228.
        "Route 226" to "Route 227",
        "Route 226" to "Route 228",
        "Route 227" to "Route 228",
        "Route 227" to "Stark Mountain (Overworld)",
        "Stark Mountain (Overworld)" to "Stark Mountain - Entrance",
        "Stark Mountain - Entrance" to "Stark Mountain - Interior",
        "Route 228" to "Route 229",
        "Route 229" to "Resort Area",
        "Resort Area" to "Route 230",
        // Route 230 loops back round to the Fight Area (transparent) -> Route 225.
        "Route 230" to "Route 225",
    )

    /** name (lowercased) -> set of adjacent names (lowercased), both directions. */
    val EDGES: Map<String, Set<String>> = buildMap<String, MutableSet<String>> {
        for ((a, b) in DECLARED) {
            getOrPut(a.lowercase()) { mutableSetOf() }.add(b.lowercase())
            getOrPut(b.lowercase()) { mutableSetOf() }.add(a.lowercase())
        }
    }

    fun isAdjacent(a: String, b: String): Boolean =
        EDGES[a.trim().lowercase()]?.contains(b.trim().lowercase()) == true
}
