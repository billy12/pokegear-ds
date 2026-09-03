package com.enrpau.pokegeards.detection

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tier 3 detection (design.md §2.2): read game state straight from Eden's RAM
 * over the GDB stub.
 *
 * Skeleton. The protocol + connection are proven ([GdbRspClient]); what's still
 * missing is the per-game address map (zone id, wild encounter, battle flag).
 * Until [BdspAddresses] / an LP map are filled in, this connects, boots the
 * game, and reports status, but emits no real GameState. Use BridgeDebugActivity
 * to hunt the addresses.
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

    /** Address map for the active pack; empty until reverse-engineered. */
    var addresses: GameAddresses? = null

    override fun start() {
        if (running.getAndSet(true)) return
        status.postValue(Status.CONNECTING)
        io.execute {
            try {
                val modules = client.connect()
                val main = modules.firstOrNull { it.name.endsWith(".nss") } ?: modules.firstOrNull()
                moduleBase.postValue(main?.base ?: 0L)
                detail.postValue(modules.joinToString("\n") { "${it.name}  0x${it.base.toString(16)}" })
                status.postValue(Status.RUNNING)
                pollLoop(main?.base ?: 0L)
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
        val addr = addresses
        while (running.get()) {
            try {
                if (addr != null) {
                    val gs = client.withHalted { c ->
                        GameState(
                            locationId = c.readU32(mainBase + addr.zoneId).toInt().takeIf { it > 0 },
                            activeSpeciesIds = addr.readActiveSpecies(c, mainBase),
                            phase = if (c.readU32(mainBase + addr.battleFlag) != 0L) GamePhase.BATTLE else GamePhase.OVERWORLD,
                        )
                    }
                    _state.postValue(gs)
                }
            } catch (e: Exception) {
                Log.w(TAG, "poll error", e)
            }
            Thread.sleep(pollMs)
        }
    }

    companion object { private const val TAG = "EdenBridge" }
}

/** Per-game RAM layout, all offsets from the main module (SwitchPlayer.nss) base. */
interface GameAddresses {
    val zoneId: Long
    val battleFlag: Long
    fun readActiveSpecies(c: GdbRspClient, mainBase: Long): List<Int>
}
