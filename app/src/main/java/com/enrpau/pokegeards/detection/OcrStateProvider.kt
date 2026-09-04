package com.enrpau.pokegeards.detection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.enrpau.pokegeards.data.db.PokegearDb
import java.util.concurrent.Executors

/**
 * Default detection path (design.md §2.2 Tier 1): read the in-game area-name
 * banner via the accessibility service's OCR pass and fuzzy-match it against the
 * active pack's locations. No emulator config, no game halt; the banner is only
 * on screen ~2s per transition, so between transitions the last match holds and
 * the manual picker can override.
 */
class OcrStateProvider(private val appContext: Context) : GameStateProvider {

    private val _state = MutableLiveData(GameState())
    override val state: LiveData<GameState> = _state

    val lastText = MutableLiveData("")

    private val db = PokegearDb.get(appContext)
    private val io = Executors.newSingleThreadExecutor()

    private var locations: List<Pair<Int, String>> = emptyList()
    private var confirmedId: Int? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_LOCATION_TEXT) return
            val text = intent.getStringExtra("TEXT") ?: return
            io.execute { handle(text) }
        }
    }

    override fun start() {
        io.execute {
            locations = db.getLocations().map { it.id to it.name }
        }
        val filter = IntentFilter(ACTION_LOCATION_TEXT)
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }

    override fun stop() {
        try { appContext.unregisterReceiver(receiver) } catch (e: Exception) {}
    }

    /** Call when the pack changes so we match against the right location list. */
    fun reloadLocations() {
        io.execute {
            locations = db.getLocations().map { it.id to it.name }
            confirmedId = null
        }
    }

    private fun handle(text: String) {
        val match = FuzzyMatch.bestPhrase(text, locations, maxWords = 4)
        if (match == null) {
            Log.d(TAG, "no area match in \"${text.replace('\n', ' ').take(60)}\" (${locations.size} locs)")
            return
        }
        lastText.postValue("${match.second}  ←  \"${text.replace('\n', ' ').take(40)}\"")

        // The banner is a clean, high-contrast OCR target, and a wrong route name
        // needs a Levenshtein <= 2 hit against a real route name (rare from noise),
        // so one confirmed read is enough. The short on-screen window makes the
        // old 2-read debounce miss ~1/3 of transitions.
        if (match.first != confirmedId) {
            confirmedId = match.first
            Log.d(TAG, "area -> ${match.second} (#${match.first})")
            _state.postValue(GameState(locationId = match.first, phase = GamePhase.OVERWORLD))
        }
    }

    companion object { private const val TAG = "OcrState" }
}
