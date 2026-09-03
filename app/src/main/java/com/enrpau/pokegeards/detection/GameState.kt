package com.enrpau.pokegeards.detection

import androidx.lifecycle.LiveData

/**
 * Detection-agnostic game state (design.md §2.2). Any provider — OCR, manual
 * picker, or a future emulator bridge — feeds this same shape.
 */
data class GameState(
    /** App-canonical pack location id (set by the manual picker). */
    val locationId: Int? = null,
    /** Raw in-game ZoneID (set by the emulator bridge); the consumer maps it to a pack location. */
    val zoneId: Int? = null,
    val activeSpeciesIds: List<Int> = emptyList(),
    val phase: GamePhase = GamePhase.UNKNOWN,
)

enum class GamePhase { OVERWORLD, BATTLE, UNKNOWN }

interface GameStateProvider {
    val state: LiveData<GameState>
    fun start()
    fun stop()
}
