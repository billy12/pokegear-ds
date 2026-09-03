package com.enrpau.pokegeards.detection

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * The "sticky picker" provider (PRD pillar C): the player taps a location and it
 * stays until they change it. Always available, and in [GameStateRepository] it
 * overrides OCR. For the v1 basic example this is the only live provider.
 */
class ManualStateProvider : GameStateProvider {

    private val _state = MutableLiveData(GameState(phase = GamePhase.OVERWORLD))
    override val state: LiveData<GameState> = _state

    override fun start() { /* nothing to poll */ }
    override fun stop() { /* nothing to release */ }

    fun setLocation(locationId: Int?) {
        _state.value = (_state.value ?: GameState()).copy(
            locationId = locationId,
            phase = GamePhase.OVERWORLD,
        )
    }

    fun setBattleSpecies(speciesIds: List<Int>) {
        _state.value = (_state.value ?: GameState()).copy(
            activeSpeciesIds = speciesIds,
            phase = if (speciesIds.isEmpty()) GamePhase.OVERWORLD else GamePhase.BATTLE,
        )
    }
}
