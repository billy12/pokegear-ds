package com.enrpau.pokegeards.detection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.enrpau.pokegeards.data.db.PokegearDb
import java.util.concurrent.Executors

/**
 * For the first few minutes after the app starts, OCR the centre of the screen
 * and match the game's title logo ("Brilliant Diamond", "Luminescent Platinum",
 * …) against each pack's `title_match` keywords, then auto-select that pack.
 * Stops on the first match or when the window closes. The pack picker is still
 * there to override.
 */
class TitleDetector(
    private val appContext: Context,
    private val onPackDetected: (String) -> Unit,
) {
    private val db = PokegearDb.get(appContext)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var matchers: List<Pair<String, List<String>>> = emptyList()
    private var startedAt = 0L
    @Volatile private var done = false

    val detected = MutableLiveData<String>()   // pack name, for a toast/log

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (done || intent.action != ACTION_TITLE_TEXT) return
            if (System.currentTimeMillis() - startedAt > WINDOW_MS) { finish(); return }
            val text = intent.getStringExtra("TEXT")?.lowercase()?.replace(Regex("\\s+"), " ") ?: return
            io.execute { match(text) }
        }
    }

    fun start() {
        startedAt = System.currentTimeMillis()
        io.execute { matchers = db.packTitleMatchers() }
        val filter = IntentFilter(ACTION_TITLE_TEXT)
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        main.postDelayed({ finish() }, WINDOW_MS)
    }

    private fun match(text: String) {
        for ((packId, keywords) in matchers) {
            if (keywords.any { it in text }) {
                done = true
                main.post { finishAndApply(packId) }
                return
            }
        }
    }

    private fun finishAndApply(packId: String) {
        finish()
        io.execute {
            if (runCatching { db.activePackId() }.getOrNull() == packId) return@execute
            db.setPackOverride(packId)
            db.syncPack()
            val name = db.availablePacks().firstOrNull { it.first == packId }?.second ?: packId
            main.post {
                Log.d(TAG, "title -> pack '$packId'")
                detected.value = name
                onPackDetected(packId)
            }
        }
    }

    /** Also called once a real zone is detected (we're past the title screen). */
    fun stop() = finish()

    private var stopped = false
    private fun finish() {
        if (stopped) return
        stopped = true
        done = true
        try { appContext.unregisterReceiver(receiver) } catch (e: Exception) {}
    }

    companion object {
        private const val TAG = "TitleDetector"
        private const val WINDOW_MS = 3 * 60 * 1000L
    }
}
