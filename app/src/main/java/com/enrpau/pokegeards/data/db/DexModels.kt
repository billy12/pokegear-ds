package com.enrpau.pokegeards.data.db

/** Plain row models returned by [PokegearDb]. No ORM — mirrors ../../../schema.sql. */

data class LocationRow(
    val id: Int,
    val name: String,
    val region: String?,
    val mapGroup: String?,
    val sortOrder: Int,
)

data class SpeciesRow(
    val id: Int,
    val name: String,
    val type1: String,
    val type2: String?,
    val baseHp: Int,
    val baseAtk: Int,
    val baseDef: Int,
    val baseSpa: Int,
    val baseSpd: Int,
    val baseSpe: Int,
    val spriteKey: String,
)

/** One encounter row already joined to its species and the player's catch state. */
data class EncounterRow(
    val encounterId: Long,
    val species: SpeciesRow,
    val method: String,       // WALK | SURF | OLD_ROD | GOOD_ROD | SUPER_ROD | ROCK_SMASH | HONEY_TREE | RADAR | SWARM | STATIC | GRAND_UNDERGROUND
    val timeOfDay: String,     // MORNING | DAY | NIGHT | ANY
    val rate: Int?,            // percent; null = guaranteed/static
    val minLevel: Int,
    val maxLevel: Int,
    val conditionNote: String?,
    val isCaught: Boolean,
) {
    val levelRange: String get() = if (minLevel == maxLevel) "Lv $minLevel" else "Lv $minLevel–$maxLevel"
    val rateText: String get() = rate?.let { "$it%" } ?: "—"
}

data class AreaProgress(val caught: Int, val total: Int) {
    val text: String get() = "$caught / $total caught here"
}
