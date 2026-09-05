package com.enrpau.pokegeards

import com.enrpau.pokegeards.detection.TitleScreenColorClassifier
import com.enrpau.pokegeards.detection.TitleScreenColorClassifier.TitlePack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Ground truth: two real Eden boot captures pulled off the device, one per game,
 * in app/src/test/resources/titlescreens. Nothing here is synthetic except the
 * blank frames, which stand in for "the icons haven't rendered yet".
 */
class TitleScreenColorClassifierTest {

    private fun fixture(name: String): BufferedImage {
        val url = javaClass.classLoader!!.getResource("titlescreens/$name")
            ?: error("missing fixture titlescreens/$name")
        return ImageIO.read(url) ?: error("could not decode titlescreens/$name")
    }

    private fun profileOf(img: BufferedImage) =
        TitleScreenColorClassifier.profile(img.width, img.height) { x, y -> img.getRGB(x, y) }

    // ---- the two real boot screens ------------------------------------------

    @Test fun bdspBootScreenIsBdsp() {
        val img = fixture("bdsp_boot_real.png")
        val p = profileOf(img)
        println("bdsp_boot_real.png ${img.width}x${img.height} -> $p")
        assertEquals(TitlePack.BDSP, p.result)
        // the Sinnoh starters: warm, green and blue in roughly equal thirds,
        // and not a purple pixel anywhere
        assertTrue("red/orange share ${p.redOrangePct}", p.redOrangePct > 0.20f)
        assertTrue("green share ${p.greenPct}", p.greenPct > 0.20f)
        assertTrue("blue share ${p.bluePct}", p.bluePct > 0.20f)
        assertTrue("purple share ${p.purplePct}", p.purplePct < 0.01f)
    }

    @Test fun lumiPlatinumBootScreenIsLumiP() {
        val img = fixture("lumip_boot_real.png")
        val p = profileOf(img)
        println("lumip_boot_real.png ${img.width}x${img.height} -> $p")
        assertEquals(TitlePack.LUMI_P, p.result)
        // Team Lumi renders every icon in the same purple/blue gradient
        assertTrue("blue+purple ${p.bluePct + p.purplePct}", p.bluePct + p.purplePct > 0.95f)
        assertTrue("red/orange share ${p.redOrangePct}", p.redOrangePct < 0.01f)
        assertTrue("green share ${p.greenPct}", p.greenPct < 0.01f)
    }

    @Test fun theTwoVerdictsMapOntoRealPackIds() {
        assertEquals("bdsp", TitlePack.BDSP.packId)
        assertEquals("lumi_plat", TitlePack.LUMI_P.packId)
        assertEquals(null, TitlePack.UNKNOWN.packId)
    }

    // ---- no verdict without a signal ----------------------------------------

    @Test fun aBlackLoadingFrameStaysUnknown() {
        val black = BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB)
        val p = profileOf(black)
        println("all-black 1920x1080 -> $p")
        assertEquals(TitlePack.UNKNOWN, p.result)
        assertEquals(0, p.sampled)
    }

    @Test fun theTopOfEachRealFrameHasNoIconsAndStaysUnknown() {
        // Same captures, cropped to the band above the icons — i.e. what the
        // corner looks like in the seconds before Eden draws them.
        for (name in listOf("bdsp_boot_real.png", "lumip_boot_real.png")) {
            val img = fixture(name)
            val preIcons = img.getSubimage(0, 0, img.width, (img.height * 0.80f).toInt())
            val p = profileOf(preIcons)
            println("$name cropped above the icons -> $p")
            assertEquals("$name pre-icon crop", TitlePack.UNKNOWN, p.result)
        }
    }

    @Test fun aHandfulOfColouredPixelsIsNotEnoughToCommit() {
        // 120 green pixels — the right hue for BDSP, below MIN_SAMPLED (200).
        val img = BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until 120) img.setRGB(1600 + i, 1000, 0x2ECC40)
        val p = profileOf(img)
        println("120 green pixels -> $p")
        assertEquals(TitlePack.UNKNOWN, p.result)
        assertEquals(120, p.sampled)
    }

    @Test fun aFullyLitCornerIsGameplayNotABootScreen() {
        // Whole region flooded with grass green: the hue says BDSP, but nothing
        // about it looks like icons on Eden's black boot screen.
        val img = BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB)
        val r = TitleScreenColorClassifier.region(1920, 1080)
        for (y in r.y until r.y + r.height) {
            for (x in r.x until r.x + r.width) img.setRGB(x, y, 0x3FA34D)
        }
        val p = profileOf(img)
        println("flooded corner -> $p")
        assertEquals(TitlePack.UNKNOWN, p.result)
    }

    // ---- the region is proportional, not 1920x1080-shaped ---------------------

    @Test fun bothVerdictsSurviveADifferentScreenResolution() {
        for ((name, want) in listOf(
            "bdsp_boot_real.png" to TitlePack.BDSP,
            "lumip_boot_real.png" to TitlePack.LUMI_P,
        )) {
            val src = fixture(name)
            val small = BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB)
            small.createGraphics().apply {
                drawImage(src.getScaledInstance(1280, 720, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
                dispose()
            }
            val p = profileOf(small)
            println("$name rescaled to 1280x720 -> $p")
            assertEquals(name, want, p.result)
        }
    }

    // ---- the IntArray path the service uses agrees with the lambda path ------

    @Test fun theCroppedRegionPathMatchesTheWholeFramePath() {
        for (name in listOf("bdsp_boot_real.png", "lumip_boot_real.png")) {
            val img = fixture(name)
            val r = TitleScreenColorClassifier.region(img.width, img.height)
            val pixels = IntArray(r.width * r.height)
            img.getRGB(r.x, r.y, r.width, r.height, pixels, 0, r.width)
            val fromRegion = TitleScreenColorClassifier.profileRegion(pixels, r.width, r.height)
            assertEquals(name, profileOf(img).result, fromRegion.result)
            assertEquals(name, profileOf(img).sampled, fromRegion.sampled)
        }
    }
}
