package com.enrpau.pokegeards.detection

/**
 * Tells BDSP apart from Luminescent Platinum by the colour of the party icons
 * Eden draws in the bottom-right corner while it compiles shaders at boot
 * ("Building N Shader(s)", ~15-30s after launching a game).
 *
 * BDSP shows the three Sinnoh starters, so the icon pixels split roughly evenly
 * across red/orange, green and blue and carry no purple at all. Team Lumi draws
 * every icon in the same purple-to-blue gradient, so those frames carry no
 * red/orange and no green whatsoever. That is a much wider gap than OCR on the
 * title logo ever gave us, and it needs no text at all.
 *
 * Deliberately free of any `android.*` import so it can be unit-tested against
 * real screenshots on the JVM. Both an Android `Bitmap` (via `getPixels`) and a
 * `BufferedImage` (via `getRGB`) hand it packed ARGB ints; the bucketing itself
 * lives in one place either way.
 */
object TitleScreenColorClassifier {

    /** Which pack a boot frame looks like. [packId] matches assets/packs/<id>. */
    enum class TitlePack(val packId: String?) {
        BDSP("bdsp"),
        LUMI_P("lumi_plat"),
        UNKNOWN(null),
    }

    /** Bottom-right slice of a frame, in absolute pixels. */
    data class Region(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * What [classify] saw, kept around so tests and logs can show the actual
     * split rather than just the verdict. The four percentages are shares of
     * [sampled], i.e. of the non-black, non-grey pixels in the region.
     */
    data class Profile(
        val sampled: Int,
        val regionPixels: Int,
        val redOrangePct: Float,
        val greenPct: Float,
        val bluePct: Float,
        val purplePct: Float,
        val result: TitlePack,
    ) {
        val nonBlackFraction: Float
            get() = if (regionPixels <= 0) 0f else sampled.toFloat() / regionPixels

        override fun toString(): String = "TitleColor[$result sampled=$sampled/$regionPixels " +
            "red/orange=%.1f%% green=%.1f%% blue=%.1f%% purple=%.1f%%]"
                .format(redOrangePct * 100, greenPct * 100, bluePct * 100, purplePct * 100)
    }

    /** The slice we look at, proportional so it survives any device resolution. */
    fun region(width: Int, height: Int): Region {
        val x = (width * REGION_LEFT).toInt()
        val y = (height * REGION_TOP).toInt()
        return Region(x, y, width - x, height - y)
    }

    /** Verdict for a whole frame, reading pixels through [getPixel] (x, y -> ARGB). */
    fun classify(width: Int, height: Int, getPixel: (Int, Int) -> Int): TitlePack =
        profile(width, height, getPixel).result

    /** As [classify], but keeps the measured split for logging/tests. */
    fun profile(width: Int, height: Int, getPixel: (Int, Int) -> Int): Profile {
        if (width <= 0 || height <= 0) return empty()
        val r = region(width, height)
        if (r.width <= 0 || r.height <= 0) return empty()
        val counts = Counts()
        for (y in r.y until r.y + r.height) {
            for (x in r.x until r.x + r.width) {
                counts.add(getPixel(x, y))
            }
        }
        return counts.toProfile(r.width * r.height)
    }

    /**
     * Verdict for an already-cropped region — the shape the accessibility
     * service uses, since one `Bitmap.getPixels` of [region] is far cheaper than
     * a per-pixel JNI hop. [pixels] must be row-major ARGB of exactly the
     * rectangle [region] returned for the full frame.
     */
    fun profileRegion(pixels: IntArray, regionWidth: Int, regionHeight: Int): Profile {
        if (regionWidth <= 0 || regionHeight <= 0) return empty()
        val n = minOf(pixels.size, regionWidth * regionHeight)
        val counts = Counts()
        for (i in 0 until n) counts.add(pixels[i])
        return counts.toProfile(regionWidth * regionHeight)
    }

    // ---- bucketing -----------------------------------------------------------

    private class Counts {
        var sampled = 0
        var redOrange = 0
        var green = 0
        var blue = 0
        var purple = 0

        fun add(argb: Int) {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            // the icons sit on Eden's black boot screen; drop the backdrop
            if (r + g + b <= BLACK_SUM) return
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            // greys and near-whites have no meaningful hue — an anti-aliased
            // edge must not be allowed to vote "red" just because max == r
            if (max == 0 || (max - min).toFloat() / max < MIN_SATURATION) return
            sampled++
            val hue = hueDegrees(r, g, b, max, min)
            when {
                hue < RED_ORANGE_END || hue >= PURPLE_END -> redOrange++
                hue >= GREEN_START && hue < GREEN_END -> green++
                hue >= BLUE_START && hue < BLUE_END -> blue++
                hue >= BLUE_END && hue < PURPLE_END -> purple++
                else -> Unit   // yellow and cyan gaps: counted, unbucketed
            }
        }

        fun toProfile(regionPixels: Int): Profile {
            if (sampled < MIN_SAMPLED) {
                // icons have not rendered yet (or this is not a boot frame) —
                // the caller should keep polling rather than commit to a guess
                return Profile(sampled, regionPixels, 0f, 0f, 0f, 0f, TitlePack.UNKNOWN)
            }
            val n = sampled.toFloat()
            val redOrangePct = redOrange / n
            val greenPct = green / n
            val bluePct = blue / n
            val purplePct = purple / n
            val tooBusy = regionPixels > 0 && sampled.toFloat() / regionPixels > MAX_NON_BLACK
            val verdict = when {
                // a lit-up corner is gameplay, not a boot screen
                tooBusy -> TitlePack.UNKNOWN
                redOrangePct > WARM_MIN || greenPct > WARM_MIN -> TitlePack.BDSP
                bluePct + purplePct >= LUMI_COOL_MIN &&
                    redOrangePct <= LUMI_WARM_MAX && greenPct <= LUMI_WARM_MAX -> TitlePack.LUMI_P
                else -> TitlePack.UNKNOWN
            }
            return Profile(sampled, regionPixels, redOrangePct, greenPct, bluePct, purplePct, verdict)
        }
    }

    private fun empty() = Profile(0, 0, 0f, 0f, 0f, 0f, TitlePack.UNKNOWN)

    private fun hueDegrees(r: Int, g: Int, b: Int, max: Int, min: Int): Float {
        val d = (max - min).toFloat()
        if (d == 0f) return 0f
        val h = when (max) {
            r -> (g - b) / d
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        var deg = h * 60f
        if (deg < 0f) deg += 360f
        if (deg >= 360f) deg -= 360f
        return deg
    }

    // ---- thresholds ----------------------------------------------------------
    // Measured on the two reference boot frames in
    // app/src/test/resources/titlescreens (real 1920x1080 captures):
    //   BDSP  -> red/orange 33.7%, green 33.1%, blue 33.2%, purple 0.0%
    //   Lumi P-> red/orange  0.0%, green  0.0%, blue+purple 100.0%
    // so WARM_MIN at 2% sits ~16x below BDSP's warm share and above Lumi's zero.

    /** Region: right-most 22% of the width, bottom-most 15% of the height. */
    private const val REGION_LEFT = 0.78f
    private const val REGION_TOP = 0.85f

    /** r+g+b above this counts as "not the black backdrop". */
    private const val BLACK_SUM = 60

    /** (max-min)/max below this is grey; hue would be noise. */
    private const val MIN_SATURATION = 0.18f

    /** Fewer coloured pixels than this and the icons simply are not up yet. */
    private const val MIN_SAMPLED = 200

    /** Above this share of the region lit up, we are looking at gameplay. */
    private const val MAX_NON_BLACK = 0.60f

    /** Any real red/orange or green presence means the Sinnoh starters. */
    private const val WARM_MIN = 0.02f

    /** Lumi's icons are wholly blue/purple, with nothing warm at all. */
    private const val LUMI_COOL_MIN = 0.90f
    private const val LUMI_WARM_MAX = 0.01f

    private const val RED_ORANGE_END = 45f
    private const val GREEN_START = 75f
    private const val GREEN_END = 165f
    private const val BLUE_START = 195f
    private const val BLUE_END = 255f
    private const val PURPLE_END = 330f
}
