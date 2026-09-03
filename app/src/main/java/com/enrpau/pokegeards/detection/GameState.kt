package com.enrpau.pokegeards.detection

import androidx.lifecycle.LiveData

/**
 * Detection-agnostic game state (design.md §2.2). Any provider — OCR, manual
 * picker, or a future emulator bridge — feeds this same shape.
 */
data class GameState(
    val locationId: Int? = null,
    val activeSpeciesIds: List<Int> = emptyList(),
    val phase: GamePhase = GamePhase.UNKNOWN,
)

enum class GamePhase { OVERWORLD, BATTLE, UNKNOWN }

interface GameStateProvider {
    val state: LiveData<GameState>
    fun start()
    fun stop()
}
