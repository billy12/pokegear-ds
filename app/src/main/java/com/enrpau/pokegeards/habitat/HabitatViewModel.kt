package com.enrpau.pokegeards.habitat

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.enrpau.pokegeards.data.db.AreaProgress
import com.enrpau.pokegeards.data.db.EncounterRow
import com.enrpau.pokegeards.data.db.LocationRow
import com.enrpau.pokegeards.data.db.PokegearDb
import com.enrpau.pokegeards.detection.GameStateRepository
import java.util.concurrent.Executors

class HabitatViewModel(app: Application) : AndroidViewModel(app) {

    private val db = PokegearDb.get(app)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    val locations = MutableLiveData<List<LocationRow>>(emptyList())
    val selectedLocationId = MutableLiveData<Int?>(null)
    val methodFilter = MutableLiveData<Set<String>>(emptySet())
    val timeFilter = MutableLiveData<Set<String>>(emptySet())

    val availableMethods = MutableLiveData<List<String>>(emptyList())
    val availableTimes = MutableLiveData<List<String>>(emptyList())

    val encounters = MediatorLiveData<List<EncounterRow>>().apply {
        addSource(selectedLocationId) { reload() }
        addSource(methodFilter) { reload() }
        addSource(timeFilter) { reload() }
    }
    val progress = MutableLiveData<AreaProgress>(AreaProgress(0, 0))
    val loading = MutableLiveData(false)

    init {
        io.execute {
            val locs = db.getLocations()
            main.post {
                locations.value = locs
                // follow the shared game state if it already has a location, else first
                val fromState = GameStateRepository.state.value?.locationId
                selectedLocationId.value = fromState ?: locs.firstOrNull()?.id
            }
        }
        // keep in sync if OCR / another provider changes the location later
        encounters.addSource(GameStateRepository.state) { gs ->
            if (gs?.locationId != null && gs.locationId != selectedLocationId.value) {
                selectLocation(gs.locationId)
            }
        }
    }

    fun selectLocation(id: Int?) {
        if (selectedLocationId.value == id) return
        selectedLocationId.value = id
        methodFilter.value = emptySet()
        timeFilter.value = emptySet()
        GameStateRepository.manual.setLocation(id)
        if (id != null) io.execute {
            val m = db.methodsAt(id)
            val t = db.timesAt(id)
            main.post { availableMethods.value = m; availableTimes.value = t }
        }
    }

    fun toggleMethod(method: String) {
        methodFilter.value = methodFilter.value.orEmpty().toggle(method)
    }

    fun toggleTime(time: String) {
        timeFilter.value = timeFilter.value.orEmpty().toggle(time)
    }

    fun setCaught(speciesId: Int, caught: Boolean) {
        io.execute {
            db.setCaught(speciesId, caught)
            reload()
        }
    }

    private fun reload() {
        val loc = selectedLocationId.value ?: run {
            main.post { encounters.value = emptyList(); progress.value = AreaProgress(0, 0) }
            return
        }
        val methods = methodFilter.value.orEmpty()
        val times = timeFilter.value.orEmpty()
        main.post { loading.value = true }
        io.execute {
            val rows = db.getEncounters(loc, methods, times)
            val prog = db.progressAt(loc)
            main.post {
                encounters.value = rows
                progress.value = prog
                loading.value = false
            }
        }
    }

    override fun onCleared() {
        io.shutdown()
        super.onCleared()
    }
}

private fun <T> Set<T>.toggle(value: T): Set<T> =
    if (value in this) this - value else this + value
