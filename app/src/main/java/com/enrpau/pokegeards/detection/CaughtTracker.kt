package com.enrpau.pokegeards.detection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.enrpau.pokegeards.data.db.PokegearDb
import java.util.concurrent.Executors

/**
 * Auto-marks a species caught when the accessibility service OCRs the catch
 * dialogue. BDSP wild-catch flow: "Gotcha!\n<SPECIES> was caught!" then
 * "<SPECIES>'s data was added to the POKéDEX." Manual toggling stays as a
 * backup / correction path.
 */
class CaughtTracker(private val appContext: Context) {

    private val db = PokegearDb.get(appContext)
    private val io = Executors.newSingleThreadExecutor()
    private var species: List<Pair<Int, String>> = emptyList()
    private var lastMarked = 0 to 0L   // (speciesId, whenMs) — de-dupe repeated dialogue frames

    val lastEvent = MutableLiveData("")

    private val CATCH_CUES = listOf("was caught", "caught!", "gotcha", "added to the")

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CATCH_TEXT) return
            val text = intent.getStringExtra("TEXT")?.lowercase() ?: return
            io.execute { handle(text) }
        }
    }

    fun start() {
        io.execute { species = db.speciesNames() }
        val filter = IntentFilter(ACTION_CATCH_TEXT)
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }

    fun stop() {
        try { appContext.unregisterReceiver(receiver) } catch (e: Exception) {}
    }

    fun reloadSpecies() = io.execute { species = db.speciesNames() }

    private fun handle(text: String) {
        if (CATCH_CUES.none { it in text }) return
        val match = FuzzyMatch.bestPhrase(text, species, maxWords = 3) ?: return
        val now = System.currentTimeMillis()
        if (match.first == lastMarked.first && now - lastMarked.second < 15_000) return  // same catch, repeated frame
        lastMarked = match.first to now

        if (!db.isCaught(match.first)) {
            db.setCaught(match.first, true)
            Log.d(TAG, "auto-caught ${match.second} (#${match.first})")
            lastEvent.postValue("Caught ${match.second}")
        }
    }

    companion object { private const val TAG = "CaughtTracker" }
}
