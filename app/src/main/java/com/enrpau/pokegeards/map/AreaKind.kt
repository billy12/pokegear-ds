package com.enrpau.pokegeards.map

import com.enrpau.pokegeards.data.db.LocationRow

/** Rough area kind guessed from the location name (falling back to map_group). */
enum class AreaKind(val label: String, val color: Int) {
    ROUTE("Route", 0xFF6BA84F.toInt()),
    CAVE("Cave", 0xFF8D6E63.toInt()),
    TOWN("Town / City", 0xFFD4A017.toInt()),
    WATER("Water", 0xFF3F8FD1.toInt()),
    BUILDING("Indoor", 0xFF9575CD.toInt()),
    OTHER("Area", 0xFF7A7A7A.toInt());

    companion object {
        fun of(loc: LocationRow): AreaKind {
            val n = loc.name.lowercase()
            return when {
                n.contains("route") -> ROUTE
                n.contains("cave") || n.contains("mine") || n.contains("cavern") ||
                    n.contains("tunnel") || n.contains("gate") || n.contains("mt.") ||
                    n.contains("mount") -> CAVE
                n.contains("lake") || n.contains("sea") || n.contains("ocean") ||
                    n.contains("bay") || n.contains("lakefront") || n.contains("spring") ||
                    n.contains("marsh") || n.contains("swamp") || n.contains("falls") ||
                    n.contains("island") -> WATER
                n.contains("city") || n.contains("town") || n.contains("village") -> TOWN
                n.contains("tower") || n.contains("mansion") || n.contains("building") ||
                    n.contains("hq") || n.contains("hideout") || n.contains("chateau") ||
                    n.contains("league") -> BUILDING
                loc.mapGroup.equals("Cave", true) -> CAVE
                else -> OTHER
            }
        }
    }
}
