package com.enrpau.pokegeards.map

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.OverScroller
import androidx.core.graphics.ColorUtils
import com.enrpau.pokegeards.R
import com.enrpau.pokegeards.data.db.LocationRow
import kotlin.math.min

/**
 * The region map surface: the hand-drawn region picture
 * (`R.drawable.region_map_bdsp`), pannable and zoomable, with an invisible tap
 * target over every area in the pack.
 *
 * Nothing is drawn procedurally any more. An earlier version painted a tile per
 * area plus corridor blocks between adjacent ones and the area name on top; it
 * never read as a map, so the picture is now the map and the areas are only
 * hit-boxes on it. Positions still come from the same place they did —
 * [GraphLayout] over [GeographicAnchors] — converted to picture pixels by
 * [MapProjection].
 *
 * The only thing painted over the picture is a plain ring on wherever the app
 * currently thinks the player is; see [setCurrentLocation].
 */
class MapCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** One area's hit-box centre, in picture pixels. */
    private class Target(val loc: LocationRow, val cx: Float, val cy: Float)

    var onTileTap: ((LocationRow) -> Unit)? = null

    private val density = resources.displayMetrics.density

    /** Current zoom factor and pan offset. Read-only; exposed so the gestures can
     *  be asserted on rather than eyeballed. */
    val zoom: Float get() = scale
    val panX: Float get() = transX
    val panY: Float get() = transY
    /** Number of tap targets currently placed — 0 until [submit] has run. */
    val tileCount: Int get() = targets.size
    /** The location whose marker is pulsing, if that area exists in this pack. */
    val highlightedLocationId: Int? get() = currentId?.takeIf { byId.containsKey(it) }

    private val targets = ArrayList<Target>()
    private val byId = HashMap<Int, Target>()

    private val mapBitmap: Bitmap? = runCatching {
        BitmapFactory.decodeResource(resources, R.drawable.region_map_bdsp)
    }.getOrNull()

    private val worldW = (mapBitmap?.width ?: 0).toFloat()
    private val worldH = (mapBitmap?.height ?: 0).toFloat()

    private var currentId: Int? = null

    // --- transform -----------------------------------------------------------
    private var scale = 1f
    private var transX = 0f
    private var transY = 0f
    private var minScale = 0.2f
    private var fitScale = 1f
    private var didInitialFit = false

    private val matrix = Matrix()
    private val inverse = Matrix()
    private val touchPoint = FloatArray(2)

    // --- paints --------------------------------------------------------------
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** 0..1, driven by [pulseAnimator]; only advances while an area is current. */
    private var pulse = 0f
    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 700L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
    }

    private val scroller = OverScroller(context)

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomBy(d.scaleFactor, d.focusX, d.focusY)
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                dx: Float,
                dy: Float,
            ): Boolean {
                transX -= dx
                transY -= dy
                clampTranslation()
                invalidate()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                vx: Float,
                vy: Float,
            ): Boolean {
                scroller.forceFinished(true)
                scroller.fling(
                    transX.toInt(), transY.toInt(), vx.toInt(), vy.toInt(),
                    (width - worldW * scale).toInt().coerceAtMost(0), 0,
                    (height - worldH * scale).toInt().coerceAtMost(0), 0,
                )
                postInvalidateOnAnimation()
                return true
            }

            // Confirmed, not onSingleTapUp: a target is now a radius rather than a
            // drawn box, so nearly anywhere on the landmass hits one, and the first
            // half of a double-tap-to-zoom would otherwise open a dialog every time.
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                targetAt(e.x, e.y)?.let { onTileTap?.invoke(it.loc) }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoomBy(nextZoomStep() / scale, e.x, e.y)
                return true
            }
        },
    )

    // --- data ----------------------------------------------------------------

    /**
     * Hang a tap target for each of [locations] over the picture, at the pixel
     * [MapProjection] puts its [result] position at.
     */
    fun submit(locations: List<LocationRow>, result: LayoutResult) {
        targets.clear()
        byId.clear()

        val points = MapProjection.project(result)
        for (loc in locations) {
            val p = points[loc.name.lowercase()] ?: continue
            val t = Target(loc, p.x, p.y)
            targets.add(t)
            byId[loc.id] = t
        }

        didInitialFit = false
        fitToScreen()
        refreshPulse()
        invalidate()
    }

    /**
     * Mark and pulse the area for [id]. Safe to call repeatedly with the same value
     * (the animation is not restarted), and with an id this pack does not have (the
     * pulse just stops).
     */
    fun setCurrentLocation(id: Int?) {
        if (currentId == id) return
        currentId = id
        refreshPulse()
        invalidate()
    }

    /**
     * The activity's location observer and its area load race each other, so the
     * pulse decision is re-taken whenever either side changes.
     */
    private fun refreshPulse() {
        if (currentId?.let { byId.containsKey(it) } == true) {
            if (!pulseAnimator.isStarted) pulseAnimator.start()
        } else {
            pulseAnimator.cancel()
            pulse = 0f
        }
    }

    /** Centre the view on the current location. False if there isn't one yet. */
    fun centreOnCurrent(): Boolean {
        val t = currentId?.let { byId[it] } ?: return false
        if (width == 0 || height == 0) return false
        transX = width / 2f - t.cx * scale
        transY = height / 2f - t.cy * scale
        clampTranslation()
        invalidate()
        return true
    }

    // --- view plumbing -------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fitToScreen()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }

    private fun fitToScreen() {
        if (width == 0 || height == 0 || worldW <= 0f || worldH <= 0f) return
        // min, not max: the whole picture has to be on screen at the opening view,
        // and the aspect ratio has to survive, so one axis gets letterboxed.
        fitScale = min(width / worldW, height / worldH)
        minScale = fitScale * 0.8f
        if (!didInitialFit) {
            didInitialFit = true
            scale = fitScale
            transX = (width - worldW * scale) / 2f
            transY = (height - worldH * scale) / 2f
        }
        clampTranslation()
    }

    /** Double tap cycles whole-region -> mid -> close-up -> whole region. */
    private fun nextZoomStep(): Float {
        val steps = floatArrayOf(fitScale, fitScale * 2.2f, fitScale * 4.5f)
        for (s in steps) if (s > scale * 1.05f) return s.coerceAtMost(MAX_SCALE)
        return fitScale
    }

    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val next = (scale * factor).coerceIn(minScale, MAX_SCALE)
        val applied = next / scale
        // Keep the point under the fingers put.
        transX = focusX - (focusX - transX) * applied
        transY = focusY - (focusY - transY) * applied
        scale = next
        clampTranslation()
        invalidate()
    }

    /** Never let the content leave the window entirely. */
    private fun clampTranslation() {
        val contentW = worldW * scale
        val contentH = worldH * scale
        transX = if (contentW <= width) (width - contentW) / 2f
        else transX.coerceIn(width - contentW, 0f)
        transY = if (contentH <= height) (height - contentH) / 2f
        else transY.coerceIn(height - contentH, 0f)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            transX = scroller.currX.toFloat()
            transY = scroller.currY.toFloat()
            clampTranslation()
            postInvalidateOnAnimation()
        }
    }

    @Suppress("ClickableViewAccessibility") // handled via onSingleTapUp -> onTileTap
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    /**
     * Touch slop in picture pixels. A fixed finger-sized radius on screen, so the
     * targets stay equally easy to hit at any zoom; bounded because at the fitted
     * view that would otherwise cover a third of the region, and because zoomed
     * right in it would otherwise shrink below a target's own spacing.
     */
    private fun touchRadiusWorld(): Float =
        (TOUCH_RADIUS_DP * density / scale).coerceIn(MIN_TOUCH_WORLD, MAX_TOUCH_WORLD)

    private fun targetAt(screenX: Float, screenY: Float): Target? {
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(transX, transY)
        if (!matrix.invert(inverse)) return null
        touchPoint[0] = screenX
        touchPoint[1] = screenY
        inverse.mapPoints(touchPoint)
        val r = touchRadiusWorld()
        var best: Target? = null
        var bestD2 = r * r
        for (t in targets) {
            val dx = t.cx - touchPoint[0]
            val dy = t.cy - touchPoint[1]
            val d2 = dx * dx + dy * dy
            if (d2 <= bestD2) {
                best = t
                bestD2 = d2
            }
        }
        return best
    }

    // --- drawing -------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = mapBitmap ?: return

        canvas.save()
        canvas.translate(transX, transY)
        canvas.scale(scale, scale)

        canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)

        // The tap targets are deliberately not drawn — the picture already shows
        // the player where things are, and the user asked for no boxes and no text.
        val current = currentId?.let { byId[it] }
        if (current != null) {
            // Sized in screen terms and divided back out, so the marker is the same
            // size however far in the map is zoomed.
            val unit = density / scale
            val r = (7f + 9f * pulse) * unit
            pulsePaint.color = ColorUtils.setAlphaComponent(
                PULSE_COLOR,
                (235 - 165 * pulse).toInt().coerceIn(0, 255),
            )
            pulsePaint.strokeWidth = (3.4f - 1.4f * pulse) * unit
            canvas.drawCircle(current.cx, current.cy, r, pulsePaint)
            dotPaint.color = PULSE_COLOR
            canvas.drawCircle(current.cx, current.cy, 3.5f * unit, dotPaint)
        }

        canvas.restore()
    }

    private companion object {
        const val MAX_SCALE = 6f
        /** Finger-sized, in dp on screen. */
        const val TOUCH_RADIUS_DP = 22f
        const val MIN_TOUCH_WORLD = 14f
        const val MAX_TOUCH_WORLD = 110f
        /** Warm amber — reads on both the paper and OLED themes, and on the ocean. */
        val PULSE_COLOR = 0xFFFFC107.toInt()
    }
}
