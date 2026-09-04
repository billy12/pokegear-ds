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
    val uncaughtOnly = MutableLiveData(false)

    val availableMethods = MutableLiveData<List<String>>(emptyList())
    val availableTimes = MutableLiveData<List<String>>(emptyList())

    val encounters = MediatorLiveData<List<EncounterRow>>().apply {
        addSource(selectedLocationId) { reload() }
        addSource(methodFilter) { reload() }
        addSource(timeFilter) { reload() }
        addSource(uncaughtOnly) { reload() }
    }
    val progress = MutableLiveData<AreaProgress>(AreaProgress(0, 0))
    val loading = MutableLiveData(false)

    val packName = MutableLiveData("")
    val availablePacks = MutableLiveData<List<Pair<String, String>>>(emptyList())

    /** bumps when auto-catch marks something, so the grid re-queries */
    val caughtPing = GameStateRepository.caughtTracker?.lastEvent

    init {
        loadPackAndLocations()
        caughtPing?.let { encounters.addSource(it) { reload() } }
        // title-screen detection already switched the DB pack; just re-load this screen
        encounters.addSource(GameStateRepository.externalPackSwitch) { loadPackAndLocations() }
        // follow detection: OCR gives a pack location id directly; the Eden
        // bridge gives a raw ZoneID that we translate. Move only if the pack
        // actually has that area.
        encounters.addSource(GameStateRepository.state) { gs ->
            gs ?: return@addSource
            io.execute {
                val locId = gs.locationId ?: gs.zoneId?.let { db.locationForZone(it) } ?: return@execute
                val known = locations.value.orEmpty().any { it.id == locId }
                android.util.Log.d(
                    "HabitatFollow",
                    "state loc=$locId selected=${selectedLocationId.value} known=$known (${locations.value.orEmpty().size} locs)"
                )
                main.post {
                    if (locId != selectedLocationId.value && known) {
                        selectLocation(locId)
                    }
                }
            }
        }
    }

    private fun loadPackAndLocations() {
        io.execute {
            db.syncPack()
            val locs = db.getLocations()
            val active = db.activePackId()
            val name = db.availablePacks().firstOrNull { it.first == active }?.second ?: active
            val packs = db.availablePacks()
            main.post {
                packName.value = name
                availablePacks.value = packs
                locations.value = locs
                val fromState = GameStateRepository.state.value?.locationId
                val initial = fromState?.takeIf { id -> locs.any { it.id == id } } ?: locs.firstOrNull()?.id
                selectedLocationId.value = initial
                loadFiltersFor(initial)
            }
        }
    }

    /** Switch data packs (e.g. BDSP <-> Luminescent Platinum) and rebuild. */
    fun selectPack(packId: String) {
        io.execute {
            db.setPackOverride(packId)
            db.syncPack()
            GameStateRepository.onPackChanged()
            val locs = db.getLocations()
            val active = db.activePackId()
            val name = db.availablePacks().firstOrNull { it.first == active }?.second ?: active
            main.post {
                packName.value = name
                methodFilter.value = emptySet()
                timeFilter.value = emptySet()
                uncaughtOnly.value = false
                locations.value = locs
                selectedLocationId.value = locs.firstOrNull()?.id
                loadFiltersFor(locs.firstOrNull()?.id)
                reload() // force refresh even if the location id is unchanged across packs
            }
        }
    }

    fun selectLocation(id: Int?) {
        if (selectedLocationId.value == id) return
        selectedLocationId.value = id
        methodFilter.value = emptySet()
        timeFilter.value = emptySet()
        GameStateRepository.manual.setLocation(id)
        loadFiltersFor(id)
    }

    private fun loadFiltersFor(id: Int?) {
        if (id == null) {
            availableMethods.value = emptyList()
            availableTimes.value = emptyList()
            return
        }
        io.execute {
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

    fun setUncaughtOnly(on: Boolean) {
        if (uncaughtOnly.value != on) uncaughtOnly.value = on
    }

    fun setCaught(speciesId: Int, caught: Boolean) {
        io.execute {
            db.setCaught(speciesId, caught)
            reload()
        }
    }

    /** Re-query the current area (e.g. after the Pokédex rebuild scan changed catch state). */
    fun reload() {
        val loc = selectedLocationId.value ?: run {
            main.post { encounters.value = emptyList(); progress.value = AreaProgress(0, 0) }
            return
        }
        val methods = methodFilter.value.orEmpty()
        val times = timeFilter.value.orEmpty()
        val uncaught = uncaughtOnly.value == true
        main.post { loading.value = true }
        io.execute {
            var rows = db.getEncounters(loc, methods, times)
            if (uncaught) rows = rows.filter { !it.isCaught }
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
