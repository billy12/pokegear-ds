package com.enrpau.pokegeards

import com.enrpau.pokegeards.data.db.EncounterRow
import com.enrpau.pokegeards.data.db.SpeciesRow
import com.enrpau.pokegeards.habitat.methodLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM checks for the Habitat display helpers (no Android runtime). */
class HabitatLogicTest {

    private fun species() = SpeciesRow(
        id = 396, name = "Starly", type1 = "NORMAL", type2 = "FLYING",
        baseHp = 40, baseAtk = 55, baseDef = 30, baseSpa = 30, baseSpd = 30, baseSpe = 60,
        spriteKey = "396",
    )

    private fun row(rate: Int?, min: Int, max: Int) = EncounterRow(
        encounterId = 1, species = species(), method = "WALK", timeOfDay = "ANY",
        rate = rate, minLevel = min, maxLevel = max, conditionNote = null, isCaught = false,
    )

    @Test fun levelRange_collapsesWhenEqual() {
        assertEquals("Lv 5", row(50, 5, 5).levelRange)
        assertEquals("Lv 2–3", row(50, 2, 3).levelRange)
    }

    @Test fun rateText_handlesGuaranteed() {
        assertEquals("50%", row(50, 2, 3).rateText)
        assertEquals("—", row(null, 5, 5).rateText)
    }

    @Test fun methodLabel_mapsKnownMethods() {
        assertEquals("Grass", methodLabel("WALK"))
        assertEquals("Super Rod", methodLabel("SUPER_ROD"))
        assertEquals("PokéRadar", methodLabel("RADAR"))
        assertEquals("Underground", methodLabel("GRAND_UNDERGROUND"))
        assertEquals("Foo", methodLabel("FOO"))
    }
}
