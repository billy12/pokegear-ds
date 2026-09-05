package com.enrpau.pokegeards.detection

/**
 * On only while [TitleDetector]'s boot window is open, so the accessibility
 * service runs the bottom-right colour pass during a game's shader-compile boot
 * and stops the moment the pack is settled (first match, a confirmed zone, or
 * the window closing). Same gating idea as [DexScanState].
 */
object TitleScanState {
    @Volatile var colorScanActive = false
}
