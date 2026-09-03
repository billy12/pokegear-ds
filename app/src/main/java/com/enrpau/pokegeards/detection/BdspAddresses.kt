package com.enrpau.pokegeards.detection

/**
 * BDSP v1.3.0 RAM layout — offsets from the main module (`SwitchPlayer.nss`)
 * base. Pointer chains from SysBot.NET / Sys-EncounterBot (LiveHeX), confirmed
 * live on an AYN Thor against Brilliant Diamond.
 *
 * A SysBot chain {B, o1..oN}: deref every element except the last, e.g.
 * addr = [[[main+B]+o1]+..]+oN  — which is exactly [GdbRspClient.followChain].
 *
 * Luminescent Platinum is BDSP 1.3.0 + ExLaunch (the `main` NSO is unchanged),
 * so the first-hop static-field offsets are expected to hold; PB8 / SaveData
 * sub-offsets may shift and are unverified for LP.
 */
object BdspAddresses {

    // save / "PlayerWork" managed object: [[main + 0x4C64DC0] + 0xB8] + 0x10
    private val SAVE_ROOT = longArrayOf(0x4C64DC0, 0xB8, 0x10)

    /** u16 field ZoneID (current route/area). */
    val zoneIdChain: LongArray = SAVE_ROOT + 0x40L

    /** u8 SceneID via the flow manager: [[main + 0x4C59B50] + 0xB8] + 0x18. 0=Field, 2=Battle. */
    val sceneIdChain = longArrayOf(0x4C59B50, 0xB8, 0x18)
    const val SCENE_BATTLE = 2

    /** TID/SID at SaveRoot + 0xE8 (u16 TID, u16 SID) — a stable connection anchor. */
    val tidSidChain: LongArray = SAVE_ROOT + 0xE8L

    /** Opponent PB8 record; species = u16 at PB8 + 0x08. Unverified until tested in-battle. */
    private val OPPONENT_PB8 = longArrayOf(
        0x4C64DC0, 0xB8, 0x10, 0x800, 0x58, 0x28, 0x10, 0x20, 0x20, 0x18, 0x20
    )

    fun readActiveSpecies(c: GdbRspClient, mainBase: Long): List<Int> {
        return try {
            val pb8 = c.followChain(mainBase, OPPONENT_PB8)
            val species = c.readMemory(pb8 + 0x08, 2).let { (it[0].toInt() and 0xff) or ((it[1].toInt() and 0xff) shl 8) }
            if (species in 1..1200) listOf(species) else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
