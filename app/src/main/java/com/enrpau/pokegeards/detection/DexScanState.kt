package com.enrpau.pokegeards.detection

/**
 * Flipped on while [com.enrpau.pokegeards.dex.PokedexScanActivity] is running so
 * the accessibility service swaps its usual OCR passes (route banner, catch
 * dialogue, title) for one full-screen pass that feeds the Pokédex rebuild.
 */
object DexScanState {
    @Volatile var active = false
}
