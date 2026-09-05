package com.enrpau.pokegeards.map

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.OverScroller
import androidx.core.graphics.ColorUtils
import com.enrpau.pokegeards.AppTheme
import com.enrpau.pokegeards.data.db.LocationRow
import kotlin.math.min

/**
 * The region map surface: every area in the pack drawn as a tile at the position
 * [GraphLayout] worked out from [com.enrpau.pokegeards.detection.SinnohAdjacency],
 * with pinch-zoom, drag-pan and fling.
 *
 * Everything is drawn straight onto the canvas through one [Matrix] rather than
 * being 150 child views, because a RecyclerView cannot place items at arbitrary
 * coordinates and 150 absolutely-positioned CardViews would measure badly.
 *
 * The tile for the location the app currently thinks the player is in pulses —
 * see [setCurrentLocation].
 */
class MapCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** One area, in world (pre-transform) coordinates. */
    private class Tile(
        val loc: LocationRow,
        val kind: AreaKind,
        val cx: Float,
        val cy: Float,
        val lines: List<String>,
    ) {
        val rect = RectF()
    }

    var onTileTap: ((LocationRow) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val tileW = 132f * density
    private val tileH = 40f * density
    private val corner = 6f * density

    /**
     * The view draws in the layout's own units, so [GraphLayout] has to be told
     * the tile size in those same units or its de-overlap pass separates by the
     * wrong amount on anything but a 1x-density screen.
     */
    val tileWorldWidth: Float get() = tileW
    val tileWorldHeight: Float get() = tileH

    /** Current zoom factor and pan offset. Read-only; exposed so the gestures can
     *  be asserted on rather than eyeballed. */
    val zoom: Float get() = scale
    val panX: Float get() = transX
    val panY: Float get() = transY
    /** Number of tiles currently placed — 0 until [submit] has run. */
    val tileCount: Int get() = tiles.size
    /** The location whose tile is pulsing, if that tile exists in this pack. */
    val highlightedLocationId: Int? get() = currentId?.takeIf { byId.containsKey(it) }

    private val tiles = ArrayList<Tile>()
    private val byId = HashMap<Int, Tile>()
    private val links = ArrayList<FloatArray>()   // [x0, y0, x1, y1] in world coords

    private var worldW = 0f
    private var worldH = 0f

    private var theme: AppTheme? = null
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
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10.5f * density
        isFakeBoldText = true
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** 0..1, driven by [pulseAnimator]; only advances while a tile is current. */
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

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                tileAt(e.x, e.y)?.let { onTileTap?.invoke(it.loc) }
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
     * Place [locations] using [result] (keys are lowercased location names) and
     * draw a hairline for each edge in [edges].
     */
    fun submit(
        locations: List<LocationRow>,
        result: LayoutResult,
        edges: Map<String, Set<String>>,
    ) {
        tiles.clear()
        byId.clear()
        links.clear()

        // GraphLayout translated its centres to (0, 0); shift by half a tile so the
        // tiles at the edge of the map sit fully inside the world box.
        for (loc in locations) {
            val p = result.positions[loc.name.lowercase()] ?: continue
            val cx = p.x + tileW / 2
            val cy = p.y + tileH / 2
            val t = Tile(loc, AreaKind.of(loc), cx, cy, wrapLabel(loc.name))
            t.rect.set(cx - tileW / 2, cy - tileH / 2, cx + tileW / 2, cy + tileH / 2)
            tiles.add(t)
            byId[loc.id] = t
        }

        val byName = HashMap<String, Tile>(tiles.size * 2)
        for (t in tiles) byName[t.loc.name.lowercase()] = t
        for ((from, tos) in edges) {
            val a = byName[from] ?: continue
            for (to in tos) {
                if (from >= to) continue          // draw each undirected edge once
                val b = byName[to] ?: continue
                links.add(floatArrayOf(a.cx, a.cy, b.cx, b.cy))
            }
        }

        worldW = result.width + tileW
        worldH = result.height + tileH

        didInitialFit = false
        fitToScreen()
        refreshPulse()
        invalidate()
    }

    fun applyTheme(t: AppTheme) {
        theme = t
        invalidate()
    }

    /**
     * Highlight and pulse the tile for [id]. Safe to call repeatedly with the same
     * value (the animation is not restarted), and with an id this pack does not
     * have (the pulse just stops).
     */
    fun setCurrentLocation(id: Int?) {
        if (currentId == id) return
        currentId = id
        refreshPulse()
        invalidate()
    }

    /**
     * The activity's location observer and its tile load race each other, so the
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

    /**
     * Double tap cycles whole-region -> mid -> readable close-up -> whole region.
     * 158 tiles cannot all be legible at once, so the overview is deliberately a
     * shape-only view and the steps above it are where the labels are read.
     */
    private fun nextZoomStep(): Float {
        val steps = floatArrayOf(fitScale, ZOOM_MID, ZOOM_CLOSE)
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

    private fun tileAt(screenX: Float, screenY: Float): Tile? {
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(transX, transY)
        if (!matrix.invert(inverse)) return null
        touchPoint[0] = screenX
        touchPoint[1] = screenY
        inverse.mapPoints(touchPoint)
        // Reverse order so the tile drawn last (on top) wins.
        for (i in tiles.indices.reversed()) {
            if (tiles[i].rect.contains(touchPoint[0], touchPoint[1])) return tiles[i]
        }
        return null
    }

    // --- drawing -------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (tiles.isEmpty()) return
        val t = theme
        val base = t?.gridBackgroundColor ?: Color.WHITE
        val textColor = t?.headerTextColor ?: Color.BLACK

        canvas.save()
        canvas.translate(transX, transY)
        canvas.scale(scale, scale)

        // Connections first, so tiles sit on top of them.
        linkPaint.color = ColorUtils.setAlphaComponent(textColor, 46)
        for (l in links) canvas.drawLine(l[0], l[1], l[2], l[3], linkPaint)

        val current = currentId?.let { byId[it] }
        strokePaint.strokeWidth = 1.5f * density
        for (tile in tiles) {
            val isCurrent = tile === current
            fillPaint.color = ColorUtils.blendARGB(base, tile.kind.color, if (isCurrent) 0.55f else 0.24f)
            canvas.drawRoundRect(tile.rect, corner, corner, fillPaint)
            strokePaint.color = ColorUtils.setAlphaComponent(tile.kind.color, if (isCurrent) 255 else 150)
            canvas.drawRoundRect(tile.rect, corner, corner, strokePaint)

            textPaint.color = textColor
            val lineH = textPaint.textSize * 1.05f
            var y = tile.cy - (tile.lines.size - 1) * lineH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            for (line in tile.lines) {
                canvas.drawText(line, tile.cx, y, textPaint)
                y += lineH
            }
        }

        // The pulse ring goes last so it is never covered by a neighbouring tile.
        if (current != null) {
            val grow = (2f + 7f * pulse) * density
            pulseRect.set(current.rect)
            pulseRect.inset(-grow, -grow)
            pulsePaint.color = ColorUtils.setAlphaComponent(
                PULSE_COLOR,
                (235 - 150 * pulse).toInt().coerceIn(0, 255),
            )
            pulsePaint.strokeWidth = (3.4f - 1.4f * pulse) * density
            canvas.drawRoundRect(pulseRect, corner + grow, corner + grow, pulsePaint)
        }

        canvas.restore()
    }

    private val pulseRect = RectF()

    /** Split a long area name over at most [MAX_LINES] lines that fit the tile. */
    private fun wrapLabel(name: String): List<String> {
        val maxW = tileW - 8f * density
        if (textPaint.measureText(name) <= maxW) return listOf(name)
        val out = ArrayList<String>()
        var line = ""
        for (w in name.split(' ')) {
            val probe = if (line.isEmpty()) w else "$line $w"
            val onLastLine = out.size == MAX_LINES - 1
            line = if (onLastLine || line.isEmpty() || textPaint.measureText(probe) <= maxW) {
                probe
            } else {
                out.add(line)
                w
            }
        }
        out.add(line)
        // Anything still too wide gets clipped with an ellipsis.
        return out.map { l ->
            if (textPaint.measureText(l) <= maxW) l else {
                var s = l
                while (s.length > 1 && textPaint.measureText("$s…") > maxW) s = s.dropLast(1)
                "$s…"
            }
        }
    }

    private companion object {
        const val MAX_SCALE = 4f
        /** Roughly a quarter of the region on screen. */
        const val ZOOM_MID = 0.42f
        /** Labels comfortably readable. */
        const val ZOOM_CLOSE = 0.9f
        const val MAX_LINES = 2
        /** Warm amber ring — reads on both the paper and OLED themes. */
        val PULSE_COLOR = 0xFFFFC107.toInt()
    }
}
