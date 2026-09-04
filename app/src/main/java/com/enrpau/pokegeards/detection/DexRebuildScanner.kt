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
 * Drives the "clear and rebuild Pokédex" scan. While it's running the
 * accessibility service sends one full-screen OCR blob per frame as
 * [ACTION_DEX_TEXT]; this fuzzy-matches every line against the active pack's
 * species names and marks each hit caught. The player scrolls their in-game
 * Pokédex top to bottom; matches accumulate until they tap Done.
 *
 * The caller is responsible for [PokegearDb.clearCaughtForActivePack] first and
 * for flipping [DexScanState.active].
 */
class DexRebuildScanner(private val appContext: Context) {

    private val db = PokegearDb.get(appContext)
    private val io = Executors.newSingleThreadExecutor()

    private var species: List<Pair<Int, String>> = emptyList()
    private val matched = HashSet<Int>()
    private val recentNames = ArrayDeque<String>()   // most-recent-first, capped

    /** Count of distinct species marked caught by this scan so far. */
    val count = MutableLiveData(0)
    /** Rolling "most recent first" list of matched names, for the on-screen log. */
    val recent: LiveData<List<String>> get() = _recent
    private val _recent = MutableLiveData<List<String>>(emptyList())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_DEX_TEXT) return
            val text = intent.getStringExtra("TEXT") ?: return
            io.execute { handle(text) }
        }
    }

    fun start() {
        io.execute { species = db.speciesNames() }
        val filter = IntentFilter(ACTION_DEX_TEXT)
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

    private fun handle(text: String) {
        if (species.isEmpty()) return
        var added = false
        try {
            // The in-game dex is one entry per line ("025  Pikachu"). Match each
            // line on its own so a garbled neighbour can't drag a good row off.
            for (rawLine in text.split('\n')) {
                val line = rawLine.trim()
                if (line.length < 3) continue

                val id = matchLine(line) ?: continue
                if (matched.add(id)) {
                    db.setCaught(id, true)
                    added = true
                    val name = species.firstOrNull { it.first == id }?.second ?: "#$id"
                    recentNames.addFirst(name)
                    while (recentNames.size > 12) recentNames.removeLast()
                    Log.d(TAG, "dex rebuild +$name (#$id)  [${matched.size}]")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "dex line parse failed", e)
        }
        if (added) {
            count.postValue(matched.size)
            _recent.postValue(recentNames.toList())
        }
    }

    /**
     * One dex line -> a species id. Nidoran is special: ♂ and ♀ share the "Nidoran"
     * stem and OCR routinely drops the tiny gender glyph, so a plain "Nidoran"
     * always fuzzy-matches the lower id (the ♀) and the ♂ can never be added.
     * Read the glyph when it's there; otherwise take whichever Nidoran isn't
     * marked yet (a full dex scroll passes both entries).
     */
    private fun matchLine(line: String): Int? {
        if (line.contains("nidoran", ignoreCase = true)) {
            val male = species.firstOrNull { it.second.equals("Nidoran-M", true) }?.first
            val female = species.firstOrNull { it.second.equals("Nidoran-F", true) }?.first
            val l = line.lowercase()
            return when {
                "♂" in line || Regex("nidoran\\s*\\(?m").containsMatchIn(l) -> male
                "♀" in line || Regex("nidoran\\s*\\(?f").containsMatchIn(l) -> female
                female != null && female !in matched -> female
                male != null && male !in matched -> male
                else -> null
            }
        }
        return FuzzyMatch.bestPhrase(line, species, maxWords = 3)?.first
    }

    companion object { private const val TAG = "DexRebuild" }
}
