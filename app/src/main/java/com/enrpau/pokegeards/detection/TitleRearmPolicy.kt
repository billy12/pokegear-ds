package com.enrpau.pokegeards.detection

/**
 * Decides when [TitleDetector]'s boot window should be (re)opened.
 *
 * The window used to be armed once, for three minutes, from PokéGear's own
 * process start — so anyone who opened PokéGear and then spent a few minutes
 * setting the emulator up had both title signals (OCR and colour) already shut
 * off by the time a game actually booted. The real cue is Eden coming to the
 * foreground, which is what this tracks.
 *
 * Rules, in order:
 *  - rising edge only: fire when the foreground app becomes Eden having been
 *    something else, never while Eden simply stays in front (the service polls
 *    every 1.5s and gets a window event far more often than that);
 *  - our own windows are invisible to it, so a PokéGear overlay popping over
 *    Eden and going away again is not a "left and came back";
 *  - nothing fires within [suppressAfterDetectMs] of a successful pack ID —
 *    within that span an Eden re-entry is an alt-tab back into the game we
 *    already recognised, not a new boot;
 *  - and at most one re-arm per [minGapMs] regardless, so a notification shade
 *    or IME flapping the foreground cannot restart the window over and over.
 *
 * Deliberately free of any `android.*` import: it is a small state machine and
 * gets unit-tested as one.
 */
class TitleRearmPolicy(
    private val selfPackage: String = "com.enrpau.pokegeards",
    private val suppressAfterDetectMs: Long = SUPPRESS_AFTER_DETECT_MS,
    private val minGapMs: Long = MIN_GAP_MS,
) {
    private var lastForegroundWasEden = false
    private var lastRearmAt: Long? = null
    private var lastDetectAt: Long? = null

    /** Call when the title detector settles on a pack, to start the quiet span. */
    @Synchronized fun onPackDetected(now: Long) {
        lastDetectAt = now
    }

    /**
     * Feed every foreground package the accessibility service sees.
     * @return true when the caller should re-arm the title window.
     */
    @Synchronized fun onForeground(pkg: String?, now: Long): Boolean {
        if (pkg.isNullOrBlank()) return false
        if (pkg == selfPackage) return false        // our own UI is not a switch away from Eden
        val eden = isEden(pkg)
        val wasEden = lastForegroundWasEden
        lastForegroundWasEden = eden
        if (!eden || wasEden) return false          // only the transition into Eden
        lastDetectAt?.let { if (now - it < suppressAfterDetectMs) return false }
        lastRearmAt?.let { if (now - it < minGapMs) return false }
        lastRearmAt = now
        return true
    }

    companion object {
        /**
         * Eden ships under several ids (`dev.eden.eden_emulator`, `.nightly`,
         * `.dualscreen.debug`, …), so match the whole family by prefix.
         */
        const val EDEN_PREFIX = "dev.eden."

        fun isEden(pkg: CharSequence?): Boolean =
            pkg != null && pkg.startsWith(EDEN_PREFIX)

        /** Quiet span after a confirmed pack — an Eden re-entry inside it is an alt-tab. */
        const val SUPPRESS_AFTER_DETECT_MS = 10 * 60 * 1000L

        /** Floor between two re-arms, so foreground flapping cannot thrash the window. */
        const val MIN_GAP_MS = 60 * 1000L
    }
}
