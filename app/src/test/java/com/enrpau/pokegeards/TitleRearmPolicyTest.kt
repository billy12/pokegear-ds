package com.enrpau.pokegeards

import com.enrpau.pokegeards.detection.TitleRearmPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug this policy exists for: the title window used to be armed once, for
 * three minutes, when PokéGear's process started. Anyone who opened PokéGear and
 * then spent longer than that getting the emulator ready had both detection
 * paths already shut off before a game ever booted.
 */
class TitleRearmPolicyTest {

    private val eden = "dev.eden.eden_emulator"
    private val self = "com.enrpau.pokegeards"
    private val min = 60_000L

    @Test fun edenAfterTheOldWindowWouldHaveExpiredStillRearms() {
        val p = TitleRearmPolicy()
        var t = 0L
        // user opens PokéGear, pokes around for four minutes (old window: 3)
        assertFalse(p.onForeground(self, t))
        t += 4 * min
        // then switches to Eden and starts a game
        assertTrue("re-arm must fire on the switch into Eden", p.onForeground(eden, t))
    }

    @Test fun stayingInEdenDoesNotRearmAgain() {
        val p = TitleRearmPolicy()
        var t = 0L
        assertFalse(p.onForeground(self, t))
        t += 1_000
        assertTrue(p.onForeground(eden, t))
        // the service sees a window event every second or two while playing
        t += 10_000
        assertFalse("still in Eden is not a transition", p.onForeground(eden, t))
        t += 10_000
        assertFalse(p.onForeground(eden, t))
    }

    @Test fun ourOwnWindowOverEdenIsNotALeave() {
        val p = TitleRearmPolicy()
        var t = 0L
        assertTrue(p.onForeground(eden, t))
        t += 30 * min                       // long past every gate
        assertFalse(p.onForeground(self, t))
        assertFalse("PokéGear's own UI must not count as leaving Eden",
            p.onForeground(eden, t + 1_000))
    }

    @Test fun altTabbingBackSoonAfterAPackWasIdentifiedDoesNotRearm() {
        val p = TitleRearmPolicy()
        var t = 0L
        assertTrue(p.onForeground(eden, t))
        p.onPackDetected(t + 20_000)        // colours settled the pack at boot
        t += 5 * min
        assertFalse(p.onForeground("com.android.chrome", t))
        assertFalse("mid-session alt-tab must not restart scanning",
            p.onForeground(eden, t + min))
    }

    @Test fun aLaterSessionRearmsOnceTheQuietSpanHasPassed() {
        val p = TitleRearmPolicy()
        var t = 0L
        assertTrue(p.onForeground(eden, t))
        p.onPackDetected(t)
        t += 11 * min                        // suppress span is 10 minutes
        assertFalse(p.onForeground("com.android.launcher", t))
        assertTrue("a fresh boot after the quiet span must be caught",
            p.onForeground(eden, t + 1_000))
    }

    @Test fun foregroundFlappingCannotThrashTheWindow() {
        val p = TitleRearmPolicy()
        var t = 0L
        assertTrue(p.onForeground(eden, t))
        // notification shade / IME bouncing the foreground once a second
        repeat(20) {
            t += 1_000
            assertFalse(p.onForeground("com.android.systemui", t))
            t += 1_000
            assertFalse("min gap is 60s", p.onForeground(eden, t))
        }
        t += 30_000                          // now past the gap
        assertFalse(p.onForeground("com.android.systemui", t))
        assertTrue(p.onForeground(eden, t + 1_000))
    }

    @Test fun everyEdenFlavourCounts() {
        for (pkg in listOf(
            "dev.eden.eden_emulator",
            "dev.eden.eden_emulator.nightly",
            "dev.eden.eden_emulator.dualscreen.debug",
        )) {
            val p = TitleRearmPolicy()
            assertFalse(p.onForeground("com.android.launcher", 0))
            assertTrue(pkg, p.onForeground(pkg, 1_000))
        }
        assertTrue(TitleRearmPolicy.isEden("dev.eden.eden_emulator"))
        assertFalse(TitleRearmPolicy.isEden("org.citra.citra_emu"))
        assertFalse(TitleRearmPolicy.isEden(null))
    }

    @Test fun blankPackagesAreIgnoredEntirely() {
        val p = TitleRearmPolicy()
        assertFalse(p.onForeground(null, 0))
        assertFalse(p.onForeground("", 0))
        // and a null in the middle must not be read as "left Eden"
        assertTrue(p.onForeground(eden, 1_000))
        assertFalse(p.onForeground(null, 2 * min))
        assertFalse(p.onForeground(eden, 3 * min))
    }
}
