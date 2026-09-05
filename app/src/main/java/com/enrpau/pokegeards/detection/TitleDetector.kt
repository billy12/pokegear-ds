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
 * For the first few minutes after a game starts booting, work out which game it
 * is and auto-select its pack. Two signals feed the same switch:
 *
 *  - OCR of the title logo ("Brilliant Diamond", "Luminescent Platinum", …)
 *    against each pack's `title_match` keywords;
 *  - the colour of the party icons Eden draws bottom-right while it compiles
 *    shaders, classified by [TitleScreenColorClassifier] — this one fires long
 *    before any logo is on screen.
 *
 * Whichever lands first wins; the detector then stops, and so does the colour
 * pass in the accessibility service. Stops too when the window closes or a real
 * zone is read. The pack picker is still there to override.
 *
 * The window is armed at app start and re-armed every time Eden comes to the
 * foreground ([rearm], gated by [TitleRearmPolicy]) — a player who opens
 * PokéGear and only launches a game ten minutes later must still get detected.
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
    @Volatile private var registered = false
    /** Bumped per armed window so a stale close-timer can be ignored. */
    @Volatile private var generation = 0

    val detected = MutableLiveData<String>()   // pack name, for a toast/log

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (done) return
            if (System.currentTimeMillis() - startedAt > WINDOW_MS) { finish(); return }
            when (intent.action) {
                ACTION_TITLE_TEXT -> {
                    val text = intent.getStringExtra("TEXT")?.lowercase()
                        ?.replace(Regex("\\s+"), " ") ?: return
                    io.execute { match(text) }
                }
                ACTION_TITLE_COLOR -> {
                    val packId = intent.getStringExtra("PACK_ID") ?: return
                    io.execute { matchByColor(packId) }
                }
            }
        }
    }

    fun start() = arm("app start")

    /**
     * Open the window again — Eden just came to the foreground, so a game is
     * probably booting. Same path as [start]; safe to call on an already-open
     * window, where it only pushes the deadline out.
     */
    fun rearm() = arm("eden foreground")

    @Synchronized private fun arm(reason: String) {
        startedAt = System.currentTimeMillis()
        done = false
        stopped = false
        TitleScanState.colorScanActive = true
        io.execute { matchers = db.packTitleMatchers() }
        if (!registered) {
            val filter = IntentFilter(ACTION_TITLE_TEXT).apply { addAction(ACTION_TITLE_COLOR) }
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }
            registered = true
        }
        // Each armed window owns its own deadline: the timer from a window that
        // has already been superseded must not close the current one.
        val gen = ++generation
        main.postDelayed({ if (gen == generation) finish() }, WINDOW_MS)
        Log.d(TAG, "title window armed ($reason), ${WINDOW_MS / 1000}s")
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

    /**
     * Same landing point as [match], reached from the boot-screen colour pass
     * instead of OCR. Checked against the installed packs so a stale broadcast
     * can never override onto a pack that isn't there.
     */
    private fun matchByColor(packId: String) {
        if (done) return
        val known = runCatching { db.availablePacks().any { it.first == packId } }.getOrDefault(false)
        if (!known) {
            Log.d(TAG, "colour hint '$packId' is not an installed pack — ignored")
            return
        }
        done = true
        Log.d(TAG, "boot-screen colours -> pack '$packId'")
        main.post { finishAndApply(packId) }
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

    @Volatile private var stopped = false
    @Synchronized private fun finish() {
        if (stopped) return
        stopped = true
        done = true
        TitleScanState.colorScanActive = false
        if (registered) {
            registered = false
            try { appContext.unregisterReceiver(receiver) } catch (e: Exception) {}
        }
    }

    companion object {
        private const val TAG = "TitleDetector"
        private const val WINDOW_MS = 3 * 60 * 1000L
    }
}
