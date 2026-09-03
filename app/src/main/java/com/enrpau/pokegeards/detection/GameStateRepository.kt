package com.enrpau.pokegeards.detection

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData

/**
 * Merges every [GameStateProvider] into one observable [GameState]
 * (design.md §2.2). Priority: manual > (bridge) > OCR.
 *
 * v1: only the manual provider is wired. `OcrStateProvider` and
 * `EmulatorBridgeStateProvider` are later milestones; the existing
 * DualDexAccessibilityService still drives battle mode independently for now.
 */
object GameStateRepository {

    val manual = ManualStateProvider()

    private val _state = MediatorLiveData<GameState>().apply {
        value = GameState(phase = GamePhase.OVERWORLD)
        addSource(manual.state) { value = it }
    }
    val state: LiveData<GameState> = _state
}
