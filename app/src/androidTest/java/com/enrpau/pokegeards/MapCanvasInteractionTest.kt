package com.enrpau.pokegeards

import android.graphics.Bitmap
import androidx.core.view.drawToBitmap
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.doubleClick
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.enrpau.pokegeards.map.MapActivity
import com.enrpau.pokegeards.map.MapCanvasView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * adb's `input tap` costs more than the double-tap timeout to launch twice, so
 * pan/zoom cannot be driven from a shell script. Espresso injects the motion
 * events with real timings, which is the only way to actually watch the gestures
 * work rather than assume they do.
 *
 * Every step also writes the view's own bitmap to the app's external files dir so
 * the frames can be pulled off the device and looked at.
 */
@RunWith(AndroidJUnit4::class)
class MapCanvasInteractionTest {

    @get:Rule val rule = ActivityScenarioRule(MapActivity::class.java)

    // lumi_plat/locations.csv ids, chosen to sit at opposite ends of the region.
    private val ROUTE_201 = 354
    private val SUNYSHORE = 142

    private val outDir: File by lazy {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // Internal storage: scoped storage hides the external dir from `adb shell`,
        // but `adb exec-out run-as` can read this one.
        File(ctx.filesDir, "maptest").apply { mkdirs() }
    }

    private fun capture(name: String): Bitmap {
        var bmp: Bitmap? = null
        rule.scenario.onActivity { act ->
            bmp = act.findViewById<MapCanvasView>(R.id.mapCanvas).drawToBitmap()
        }
        val b = requireNotNull(bmp)
        File(outDir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return b
    }

    /** Fraction of pixels that differ — a cheap "did the picture change" check. */
    private fun diff(a: Bitmap, b: Bitmap): Double {
        if (a.width != b.width || a.height != b.height) return 1.0
        val px = IntArray(a.width * a.height)
        val qx = IntArray(b.width * b.height)
        a.getPixels(px, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(qx, 0, b.width, 0, 0, b.width, b.height)
        var n = 0
        for (i in px.indices) if (px[i] != qx[i]) n++
        return n.toDouble() / px.size
    }

    private fun state(): Triple<Float, Float, Int> {
        var out = Triple(0f, 0f, 0)
        rule.scenario.onActivity { act ->
            val v = act.findViewById<MapCanvasView>(R.id.mapCanvas)
            out = Triple(v.zoom, v.panX, v.tileCount)
        }
        return out
    }

    @Test fun doubleTapZoomsAndDragPans() {
        // The tiles are laid out on a background thread; give it room.
        Thread.sleep(4000)
        val fitted = capture("10_fitted")
        val (fitZoom, _, tiles) = state()
        println("MAPTEST fitted: tiles=$tiles zoom=$fitZoom")
        assertTrue("no tiles were placed", tiles > 50)

        onView(withId(R.id.mapCanvas)).perform(doubleClick())
        Thread.sleep(800)
        val zoomed = capture("11_zoomed")
        val (zoomIn, panAfterZoom, _) = state()
        println(
            "MAPTEST after double tap: zoom=$zoomIn (x${"%.2f".format(zoomIn / fitZoom)}) " +
                "pixels changed ${"%.4f".format(diff(fitted, zoomed))}"
        )
        assertTrue("double tap did not zoom in ($fitZoom -> $zoomIn)", zoomIn > fitZoom * 1.5f)

        onView(withId(R.id.mapCanvas)).perform(swipeLeft())
        Thread.sleep(1200)
        val panned = capture("12_panned")
        val (_, panAfterDrag, _) = state()
        println(
            "MAPTEST after swipe: panX $panAfterZoom -> $panAfterDrag, " +
                "pixels changed ${"%.4f".format(diff(zoomed, panned))}"
        )
        assertTrue("drag did not pan the map", panAfterDrag < panAfterZoom - 50f)

        // Keep double tapping until it wraps back round to the fitted view.
        onView(withId(R.id.mapCanvas)).perform(doubleClick())
        Thread.sleep(600)
        println("MAPTEST after 2nd double tap: zoom=${state().first}")
        onView(withId(R.id.mapCanvas)).perform(doubleClick())
        Thread.sleep(600)
        val back = capture("13_back_to_fit")
        val backZoom = state().first
        println("MAPTEST after 3rd double tap: zoom=$backZoom (fit was $fitZoom)")
        assertEquals("third double tap should return to the fitted view", fitZoom, backZoom, 0.001f)
        println("MAPTEST frames in ${outDir.absolutePath}, last-frame diff ${diff(fitted, back)}")
    }

    /**
     * The blink has to follow the game state while the screen stays open, so this
     * pokes the same provider the manual picker uses and watches the highlight
     * move without the activity being recreated.
     */
    @Test fun theHighlightFollowsTheGameStateLive() {
        Thread.sleep(4000)
        // Zoom in far enough that the pulse ring is actually visible in a frame.
        onView(withId(R.id.mapCanvas)).perform(doubleClick())
        Thread.sleep(600)

        val first = showLocation(ROUTE_201, "20_highlight_route201")
        println("MAPTEST highlighted after manual pick #$ROUTE_201: $first")
        assertEquals(ROUTE_201, first)

        // Same tile, a moment later: if the pulse animator is running the frame
        // must have changed on its own.
        val a = capture("20a_pulse_frame_a")
        Thread.sleep(350)
        val b = capture("20b_pulse_frame_b")
        val pulseDiff = diff(a, b)
        println("MAPTEST pulse frames differ by ${"%.5f".format(pulseDiff)} of pixels")
        assertTrue("the highlight is not animating", pulseDiff > 0.0)

        val second = showLocation(SUNYSHORE, "21_highlight_sunyshore")
        println("MAPTEST highlighted after second manual pick #$SUNYSHORE: $second")
        assertEquals(SUNYSHORE, second)
    }

    /** Drive the real provider, then park the camera on whatever lit up. */
    private fun showLocation(id: Int, frame: String): Int? {
        var highlighted: Int? = null
        rule.scenario.onActivity {
            com.enrpau.pokegeards.detection.GameStateRepository.manual.setLocation(id)
        }
        Thread.sleep(900)
        rule.scenario.onActivity { act ->
            val v = act.findViewById<MapCanvasView>(R.id.mapCanvas)
            v.centreOnCurrent()
            highlighted = v.highlightedLocationId
        }
        Thread.sleep(400)
        capture(frame)
        return highlighted
    }
}
