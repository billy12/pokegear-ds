package com.enrpau.pokegeards.detection

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData

/**
 * Merges every [GameStateProvider] into one observable [GameState]
 * (design.md §2.2). Priority: the Eden bridge (when running with a zone) >
 * route-banner OCR > the manual picker.
 */
object GameStateRepository {

    val manual = ManualStateProvider()
    val bridge = EmulatorBridgeStateProvider()
    var ocr: OcrStateProvider? = null
        private set
    var caughtTracker: CaughtTracker? = null
        private set

    private val _state = MediatorLiveData<GameState>().apply {
        value = GameState(phase = GamePhase.OVERWORLD)
    }
    val state: LiveData<GameState> = _state

    private var wired = false

    /** Call once from the Application. Starts the OCR default path + auto-catch. */
    fun init(context: Context) {
        if (wired) return
        wired = true
        val app = context.applicationContext
        ocr = OcrStateProvider(app).also { it.start() }
        caughtTracker = CaughtTracker(app).also { it.start() }

        _state.addSource(manual.state) { recompute() }
        _state.addSource(bridge.state) { recompute() }
        _state.addSource(bridge.status) { recompute() }
        ocr?.state?.let { s -> _state.addSource(s) { recompute() } }
    }

    private fun recompute() {
        val b = bridge.state.value
        val o = ocr?.state?.value
        val next = when {
            bridge.status.value == EmulatorBridgeStateProvider.Status.RUNNING && b?.zoneId != null -> b
            o?.locationId != null -> o
            else -> manual.state.value ?: GameState(phase = GamePhase.OVERWORLD)
        }
        // Only emit on a real change. Otherwise a manual pick (which triggers a
        // recompute via manual.state) gets clobbered by the unchanged OCR value
        // re-asserting itself.
        if (next != _state.value) _state.value = next
    }

    /** Pack changed — refresh the OCR match lists. */
    fun onPackChanged() {
        ocr?.reloadLocations()
        caughtTracker?.reloadSpecies()
    }

    fun startBridge() = bridge.start()
    fun stopBridge() = bridge.stop()
    val bridgeRunning: Boolean
        get() = bridge.status.value == EmulatorBridgeStateProvider.Status.RUNNING
}
