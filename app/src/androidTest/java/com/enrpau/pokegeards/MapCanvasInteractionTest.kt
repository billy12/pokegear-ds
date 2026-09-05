package com.enrpau.pokegeards

import android.graphics.Bitmap
import android.view.InputDevice
import android.view.MotionEvent
import androidx.core.view.drawToBitmap
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.doubleClick
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.enrpau.pokegeards.map.MapActivity
import com.enrpau.pokegeards.map.MapCanvasView
import org.junit.After
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

    /**
     * The manual provider is a process-wide singleton, so a location one test picks
     * is still current when the next one's map opens — and the marker's endless
     * pulse means Espresso's MAIN_LOOPER_HAS_IDLED condition never passes, which
     * fails that test with AppNotIdleException. Clear it after each test.
     */
    @After fun clearCurrentLocation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            com.enrpau.pokegeards.detection.GameStateRepository.manual.setLocation(null)
        }
    }

    // Two areas at opposite ends of the region, looked up in whichever pack is
    // active — the row ids differ per pack (bdsp calls Sunyshore 34, lumi_plat 142),
    // so hard-coding one pack's ids only ever passed on a device carrying that pack.
    private val ROUTE_201 by lazy { locationId("Route 201") }
    private val SUNYSHORE by lazy { locationId("Sunyshore City") }

    private fun locationId(name: String): Int {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val rows = com.enrpau.pokegeards.data.db.PokegearDb.get(ctx).getLocations()
        return rows.first { it.name == name }.id
    }

    // Where region_map_bdsp.png draws Sunyshore City's block, in its own pixels.
    private val SUNYSHORE_IMAGE_X = 1016f
    private val SUNYSHORE_IMAGE_Y = 712f

    private val outDir: File by lazy {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // Internal storage: scoped storage hides the external dir from `adb shell`,
        // but `adb exec-out run-as` can read this one.
        File(ctx.filesDir, "maptest").apply { mkdirs() }
    }

    /**
     * Run [block] on the main thread against the resumed activity.
     *
     * Deliberately not `ActivityScenario.onActivity`: that calls
     * `Instrumentation.waitForIdleSync` first, and once the current-location marker
     * is pulsing the main looper has a frame callback queued at all times, so it
     * never reports idle and the call never returns. `runOnMainSync` only waits for
     * the block itself.
     */
    private fun onMain(block: (MapActivity) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val act = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MapActivity>()
                .first()
            block(act)
        }
    }

    private fun capture(name: String): Bitmap {
        var bmp: Bitmap? = null
        onMain { act ->
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
        onMain { act ->
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
     * The areas are invisible hit-boxes over a fixed picture now, so the only way to
     * see that the calibration is right is to zoom in, press the pixel a landmark is
     * drawn at, and read the name off the dialog that opens.
     *
     * Sunyshore City is the check because both shipped packs have a row for it and
     * it is drawn as its own block (the big blue square at the foot of the eastern
     * spit, at picture pixel 1016, 712).
     */
    @Test fun tappingALandmarkWhileZoomedInOpensThatArea() {
        Thread.sleep(4000)

        // Double tap *on* the block, not at the view centre: the zoom keeps the point
        // under the finger put, so the block is still there afterwards to be tapped.
        val fitted = sunyshoreOnScreen()
        onView(withId(R.id.mapCanvas)).perform(clickAt(Tap.DOUBLE, fitted.first, fitted.second))
        Thread.sleep(900)

        val zoomed = sunyshoreOnScreen()
        println("MAPTEST Sunyshore's block: fitted at $fitted, zoomed to ${zoomOf()} at $zoomed")

        onView(withId(R.id.mapCanvas)).perform(clickAt(Tap.SINGLE, zoomed.first, zoomed.second))
        // onSingleTapConfirmed waits out the double-tap window first.
        Thread.sleep(1200)
        capture("30_tapped_sunyshore")

        onView(withId(R.id.areaName)).inRoot(isDialog())
            .check(matches(withText("Sunyshore City")))

        // Close it rather than leaving the scenario to tear the activity down with a
        // dialog still attached, which leaks the window.
        onView(withId(R.id.areaClose)).inRoot(isDialog()).perform(click())
        Thread.sleep(400)
    }

    private fun zoomOf(): Float = state().first

    /** Where the picture's Sunyshore pixel currently sits in the view. */
    private fun sunyshoreOnScreen(): Pair<Float, Float> {
        var out = 0f to 0f
        onMain { act ->
            val v = act.findViewById<MapCanvasView>(R.id.mapCanvas)
            out = (v.panX + SUNYSHORE_IMAGE_X * v.zoom) to (v.panY + SUNYSHORE_IMAGE_Y * v.zoom)
        }
        return out
    }

    /** A tap at an exact point in the view, rather than at its centre. */
    private fun clickAt(tap: Tap, x: Float, y: Float) = GeneralClickAction(
        tap,
        { view ->
            val screen = IntArray(2)
            view.getLocationOnScreen(screen)
            floatArrayOf(screen[0] + x, screen[1] + y)
        },
        Press.FINGER,
        InputDevice.SOURCE_TOUCHSCREEN,
        MotionEvent.BUTTON_PRIMARY,
    )

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
        onMain {
            com.enrpau.pokegeards.detection.GameStateRepository.manual.setLocation(id)
        }
        Thread.sleep(900)
        onMain { act ->
            val v = act.findViewById<MapCanvasView>(R.id.mapCanvas)
            v.centreOnCurrent()
            highlighted = v.highlightedLocationId
        }
        Thread.sleep(400)
        capture(frame)
        return highlighted
    }
}
