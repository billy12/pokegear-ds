package com.enrpau.pokegeards.detection

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tier 3 detection (design.md §2.2): read game state straight from Eden's RAM
 * over the GDB stub. Confirmed working on BDSP v1.3.0 — see [BdspAddresses].
 *
 * Eden halts the game at the loader until a client connects and continues, so
 * [start] sends `c` to boot it, then polls: every [pollMs] it interrupts the
 * guest, follows the ZoneID and SceneID pointer chains, and continues.
 */
class EmulatorBridgeStateProvider(
    private val host: String = "127.0.0.1",
    private val port: Int = 6543,
    private val pollMs: Long = 1500L,
) : GameStateProvider {

    enum class Status { IDLE, CONNECTING, RUNNING, ERROR }

    private val _state = MutableLiveData(GameState())
    override val state: LiveData<GameState> = _state

    val status = MutableLiveData(Status.IDLE)
    val detail = MutableLiveData("")
    val moduleBase = MutableLiveData(0L)   // SwitchPlayer.nss base, re-read each session (ASLR)

    private val client = GdbRspClient(host, port)
    private val io = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    override fun start() {
        if (running.getAndSet(true)) return
        status.postValue(Status.CONNECTING)
        io.execute {
            try {
                val modules = client.connect()
                val main = modules.firstOrNull { it.name.endsWith(".nss") } ?: modules.firstOrNull()
                val base = main?.base ?: 0L
                moduleBase.postValue(base)
                detail.postValue("main = 0x${base.toString(16)}")
                status.postValue(Status.RUNNING)
                pollLoop(base)
            } catch (e: Exception) {
                Log.e(TAG, "bridge start failed", e)
                detail.postValue(e.message ?: e.toString())
                status.postValue(Status.ERROR)
                running.set(false)
            }
        }
    }

    override fun stop() {
        running.set(false)
        io.execute { client.close(); status.postValue(Status.IDLE) }
    }

    private fun pollLoop(mainBase: Long) {
        if (mainBase == 0L) { status.postValue(Status.ERROR); running.set(false); return }
        var lastLog = ""
        while (running.get()) {
            try {
                val gs = client.withHalted { c ->
                    val zone = readU16(c, c.followChain(mainBase, BdspAddresses.zoneIdChain))
                    val scene = c.readMemory(c.followChain(mainBase, BdspAddresses.sceneIdChain), 1)[0].toInt() and 0xff
                    val battle = scene == BdspAddresses.SCENE_BATTLE
                    GameState(
                        zoneId = zone.takeIf { it > 0 },
                        activeSpeciesIds = if (battle) BdspAddresses.readActiveSpecies(c, mainBase) else emptyList(),
                        phase = if (battle) GamePhase.BATTLE else GamePhase.OVERWORLD,
                    )
                }
                if (gs != _state.value) _state.postValue(gs)
                val line = "zone ${gs.zoneId} · scene ${gs.phase}${if (gs.activeSpeciesIds.isNotEmpty()) " · vs ${gs.activeSpeciesIds}" else ""}"
                if (line != lastLog) { detail.postValue(line); lastLog = line }
            } catch (e: Exception) {
                Log.w(TAG, "poll error", e)
                detail.postValue("poll error: ${e.message}")
            }
            try { Thread.sleep(pollMs) } catch (e: InterruptedException) { break }
        }
    }

    private fun readU16(c: GdbRspClient, addr: Long): Int {
        val b = c.readMemory(addr, 2)
        return (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8)
    }

    companion object { private const val TAG = "EdenBridge" }
}
