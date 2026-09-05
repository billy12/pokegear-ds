package com.enrpau.pokegeards.map

import kotlin.math.max
import kotlin.math.min

/**
 * Puts [GraphLayout]'s output onto the hand-drawn region picture.
 *
 * The map screen no longer draws the region — it draws `region_map_bdsp.png` and
 * hangs an invisible tap target over each area. So the only geometry left to do is
 * a change of units: layout coordinates -> pixels of that picture.
 *
 * Two hops:
 *
 *  1. **reference frame -> picture pixels.** A fixed scale+offset per axis, fitted
 *     by least squares against nine landmarks that are unambiguous in the drawing
 *     (the three lone islands, the four Battle Zone blocks in the detached loop,
 *     and the League/Sunyshore pair on the eastern spit). See [SCALE_X] below.
 *  2. **layout units -> reference frame.** [GraphLayout] translates its result to
 *     (0, 0) and scales it by a factor the caller chose, so the offset cannot be
 *     read back off [LayoutResult]. [project] recovers it the same way step 1 was
 *     fitted: least squares over every node that sat on a [GeographicAnchors]
 *     entry, whose reference coordinate is therefore known.
 *
 * Pure Kotlin, no Android types, so the whole pipeline is unit-testable.
 */
object MapProjection {

    /** Pixel size of `region_map_bdsp.png`. */
    const val IMAGE_WIDTH = 1130f
    const val IMAGE_HEIGHT = 928f

    /**
     * Reference frame -> picture pixels, `px = ref * SCALE + OFFSET`.
     *
     * Fitted against these nine landmark pairs (reference coord -> pixel centre of
     * the drawn block), all of which are identifiable in the artwork without
     * guessing:
     *
     * ```
     * Fullmoon Island  ( 48,  88) -> (  62, 132)   lone NW island, west of the pair
     * Newmoon Island   (133,  88) -> ( 174, 131)   lone NW island, east of the pair
     * Iron Island      (105, 284) -> ( 137, 394)   lone island, west, mid-latitude
     * Stark Mountain   (663,  55) -> ( 886,  95)   purple at the top of the NE loop
     * Survival Area    (579, 140) -> ( 773, 207)   NW block inside the NE loop
     * Fight Area       (566, 225) -> ( 755, 319)   SW block inside the NE loop
     * Resort Area      (719, 252) -> ( 961, 357)   purple at the SE of the NE loop
     * Sinnoh League    (748, 335) -> ( 998, 469)   purple on the eastern spit
     * Sunyshore City   (762, 517) -> (1016, 712)   big blue at the foot of that spit
     * ```
     *
     * Worst residual on those nine is 4.6 px. Three landmarks deliberately held
     * back from the fit land on their drawn block anyway — Veilstone City is out by
     * (1.3, 0.2) px, Hearthome City by (1.8, 0.8), Pastoria City by (1.9, 1.3) —
     * which is the check that the fit is a real correspondence and not overfitting.
     *
     * The drawing is stylised, so a handful of places sit beside their block rather
     * than on it (Snowpoint City and Lake Acuity look swapped, Lake Valor has no
     * water block drawn for it). That is expected and fine: these are tap targets,
     * not labels.
     */
    const val SCALE_X = 1.3395f
    const val OFFSET_X = -3.20f
    const val SCALE_Y = 1.3449f
    const val OFFSET_Y = 16.41f

    /**
     * Layout box passed to [GraphLayout]. Square, so a group of rows sharing one
     * anchor (Mt. Coronet's floors, Old Chateau's rooms) fans out into a roughly
     * round cluster instead of a tall stack — [GraphLayout] measures its cell order
     * in layout units, not cells.
     */
    const val NODE_SIZE = 40.0
    const val NODE_PADDING = 6.0

    /** Keep every target this far inside the picture, so none is un-tappable. */
    private const val EDGE_MARGIN = 8f

    /** Reference-frame point -> picture pixel. */
    fun refToImage(x: Float, y: Float) = LayoutPoint(x * SCALE_X + OFFSET_X, y * SCALE_Y + OFFSET_Y)

    /**
     * Every node in [result], in picture pixels, clamped inside the image.
     *
     * Nodes that sat on an anchor are used to recover the layout -> reference
     * transform; split zones are skipped for that purpose, because [GraphLayout]
     * puts their rows on the split ends while [GeographicAnchors.anchorFor] would
     * hand back the midpoint the two ends share.
     */
    fun project(result: LayoutResult): Map<String, LayoutPoint> {
        val samples = ArrayList<Pair<LayoutPoint, LayoutPoint>>()   // layout, reference
        for ((name, p) in result.positions) {
            if (GeographicAnchors.baseKey(name) in GeographicAnchors.SPLITS) continue
            val ref = GeographicAnchors.anchorFor(name) ?: continue
            samples.add(p to ref)
        }

        val fx = fitAxis(samples.map { it.first.x to it.second.x })
        val fy = fitAxis(samples.map { it.first.y to it.second.y })

        val out = HashMap<String, LayoutPoint>(result.positions.size * 2)
        if (fx == null || fy == null) {
            // No usable anchors (an empty or entirely unnamed pack). Stretch the
            // laid-out box over the picture so the targets are at least on it.
            val sx = if (result.width > 0f) (IMAGE_WIDTH - 2 * EDGE_MARGIN) / result.width else 0f
            val sy = if (result.height > 0f) (IMAGE_HEIGHT - 2 * EDGE_MARGIN) / result.height else 0f
            for ((name, p) in result.positions) {
                out[name] = clamp(EDGE_MARGIN + p.x * sx, EDGE_MARGIN + p.y * sy)
            }
            return out
        }

        for ((name, p) in result.positions) {
            val refX = fx.first * p.x + fx.second
            val refY = fy.first * p.y + fy.second
            val img = refToImage(refX, refY)
            out[name] = clamp(img.x, img.y)
        }
        return out
    }

    /**
     * The nearest entry of [points] to ([x], [y]) within [radius], or null.
     *
     * An exact tie goes to the lower key rather than to whatever the map happens to
     * iterate first, so a tap landing between two co-located rows always opens the
     * same one.
     */
    fun nearest(
        points: Map<String, LayoutPoint>,
        x: Float,
        y: Float,
        radius: Float,
    ): String? {
        var best: String? = null
        var bestD2 = radius * radius
        for ((name, p) in points) {
            val dx = p.x - x
            val dy = p.y - y
            val d2 = dx * dx + dy * dy
            if (d2 > bestD2) continue
            if (best == null || d2 < bestD2 || name < best) {
                best = name
                bestD2 = d2
            }
        }
        return best
    }

    private fun clamp(x: Float, y: Float) = LayoutPoint(
        min(max(x, EDGE_MARGIN), IMAGE_WIDTH - EDGE_MARGIN),
        min(max(y, EDGE_MARGIN), IMAGE_HEIGHT - EDGE_MARGIN),
    )

    /** Least-squares `to = slope * from + intercept`. Null if [pairs] pins nothing down. */
    private fun fitAxis(pairs: List<Pair<Float, Float>>): Pair<Float, Float>? {
        if (pairs.size < 2) return null
        var mf = 0.0
        var mt = 0.0
        for ((f, t) in pairs) { mf += f; mt += t }
        mf /= pairs.size
        mt /= pairs.size
        var sft = 0.0
        var sff = 0.0
        for ((f, t) in pairs) {
            val d = f - mf
            sft += d * (t - mt)
            sff += d * d
        }
        if (sff < 1e-6) return null
        val slope = sft / sff
        return slope.toFloat() to (mt - slope * mf).toFloat()
    }
}
