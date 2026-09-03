package com.enrpau.pokegeards.detection

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData

/**
 * Merges every [GameStateProvider] into one observable [GameState]
 * (design.md §2.2). Priority: the Eden bridge when it's running and reporting a
 * zone, otherwise the manual picker.
 */
object GameStateRepository {

    val manual = ManualStateProvider()
    val bridge = EmulatorBridgeStateProvider()

    private val _state = MediatorLiveData<GameState>().apply {
        value = GameState(phase = GamePhase.OVERWORLD)
    }
    val state: LiveData<GameState> = _state

    init {
        _state.addSource(manual.state) { recompute() }
        _state.addSource(bridge.state) { recompute() }
        _state.addSource(bridge.status) { recompute() }
    }

    private fun recompute() {
        val b = bridge.state.value
        _state.value =
            if (bridge.status.value == EmulatorBridgeStateProvider.Status.RUNNING && b?.zoneId != null) b
            else manual.state.value ?: GameState(phase = GamePhase.OVERWORLD)
    }

    fun startBridge() = bridge.start()
    fun stopBridge() = bridge.stop()
    val bridgeRunning: Boolean
        get() = bridge.status.value == EmulatorBridgeStateProvider.Status.RUNNING
}
