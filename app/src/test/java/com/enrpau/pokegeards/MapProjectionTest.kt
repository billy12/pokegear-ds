package com.enrpau.pokegeards

import com.enrpau.pokegeards.detection.PackAdjacency
import com.enrpau.pokegeards.map.GeographicAnchors
import com.enrpau.pokegeards.map.GraphLayout
import com.enrpau.pokegeards.map.LayoutPoint
import com.enrpau.pokegeards.map.MapProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * The map screen stopped drawing anything of its own — it draws
 * `region_map_bdsp.png` and hangs invisible tap targets on it — so what used to be
 * worth checking about the rendering (tile colours, corridor blocks) no longer
 * exists. What is worth checking now is that every area ends up somewhere sensible
 * on that picture, and that tapping the picture picks the area that is drawn there.
 *
 * Runs both shipped packs through exactly the pipeline MapActivity uses:
 * `PackAdjacency.forPack` -> [GraphLayout] -> [MapProjection.project].
 */
class MapProjectionTest {

    private fun names(pack: String): List<String> {
        val f = listOf(
            "src/main/assets/packs/$pack/locations.csv",
            "app/src/main/assets/packs/$pack/locations.csv",
        ).map { File(it) }.firstOrNull { it.isFile }
            ?: error("$pack/locations.csv not found from working dir ${File(".").absolutePath}")
        return f.readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",")[1] }
    }

    /** name -> picture pixel, the way the map screen builds it. */
    private fun project(pack: String): Map<String, LayoutPoint> {
        val keys = names(pack).map { it.lowercase() }
        val layout = GraphLayout.layout(
            nodes = keys,
            edges = PackAdjacency.forPack(names(pack)),
            nodeWidth = MapProjection.NODE_SIZE,
            nodeHeight = MapProjection.NODE_SIZE,
            padding = MapProjection.NODE_PADDING,
        )
        return MapProjection.project(layout)
    }

    /**
     * The landmarks the transform was calibrated on, plus three deliberately held
     * back from the fit (Veilstone, Hearthome, Pastoria) as the honest check.
     * Pixel centres of the blocks drawn for them in the artwork.
     */
    private val landmarks = listOf(
        Triple("fullmoon island", 62f, 132f),
        Triple("newmoon island", 174f, 131f),
        Triple("iron island", 137f, 394f),
        Triple("stark mountain", 886f, 95f),
        Triple("survival area", 773f, 207f),
        Triple("fight area", 755f, 319f),
        Triple("resort area", 961f, 357f),
        Triple("sunyshore city", 1016f, 712f),
        Triple("veilstone city", 830f, 525f),
        Triple("hearthome city", 567f, 637f),
        Triple("pastoria city", 718f, 787f),
    )

    // ---- the transform itself -------------------------------------------------

    @Test fun theLandmarksLandOnTheirBlocksInThePicture() {
        var worst = 0f
        for ((name, px, py) in landmarks) {
            val ref = requireNotNull(GeographicAnchors.anchorFor(name)) { "no anchor for $name" }
            val got = MapProjection.refToImage(ref.x, ref.y)
            val d = hypot(got.x - px, got.y - py)
            println("landmark $name -> (${"%.1f".format(got.x)}, ${"%.1f".format(got.y)}) " +
                "drawn at ($px, $py), off by ${"%.1f".format(d)}px")
            worst = maxOf(worst, d)
        }
        // The artwork is stylised, not a projection, so this is a "same place on the
        // picture" bar, not a pixel bar. It currently sits near 5px.
        assertTrue("worst landmark error ${"%.1f".format(worst)}px", worst < 12f)
    }

    // ---- every area gets a position on the picture ----------------------------

    @Test fun everyAreaInBothPacksIsPlacedInsideThePicture() {
        for (pack in listOf("bdsp", "lumi_plat")) {
            val points = project(pack)
            val rows = names(pack).map { it.lowercase() }
            assertEquals("$pack: not every row was projected", rows.toSet(), points.keys)
            for ((name, p) in points) {
                assertTrue(
                    "$pack: $name projected off the picture at (${p.x}, ${p.y})",
                    p.x >= 0f && p.x <= MapProjection.IMAGE_WIDTH &&
                        p.y >= 0f && p.y <= MapProjection.IMAGE_HEIGHT,
                )
            }
            println("$pack: ${points.size} tap targets, all inside " +
                "${MapProjection.IMAGE_WIDTH.toInt()}x${MapProjection.IMAGE_HEIGHT.toInt()}")
        }
    }

    /**
     * The layout is fitted back onto the reference frame from the anchored rows, so
     * an anchored row has to come out where its own anchor says — otherwise the
     * whole picture is offset and nothing else can be right either.
     */
    @Test fun anchoredAreasProjectOntoTheirOwnAnchor() {
        for (pack in listOf("bdsp", "lumi_plat")) {
            val points = project(pack)
            var worst = 0f
            var worstName = ""
            var checked = 0
            for ((name, p) in points) {
                if (GeographicAnchors.baseKey(name) in GeographicAnchors.SPLITS) continue
                val ref = GeographicAnchors.anchorFor(name) ?: continue
                // Exact-name anchors only: a pack row like "Lake Verity (Before)"
                // resolves through baseName and shares a point with its sibling, so
                // the de-overlap pass legitimately moves one of them.
                if (GeographicAnchors.ANCHORS[name] == null) continue
                val want = MapProjection.refToImage(ref.x, ref.y)
                val d = hypot(p.x - want.x, p.y - want.y)
                checked++
                if (d > worst) { worst = d; worstName = name }
            }
            println("$pack: $checked anchored areas, worst drift ${"%.1f".format(worst)}px ($worstName)")
            assertTrue("$pack: $worstName drifted ${"%.1f".format(worst)}px off its anchor", worst < 60f)
        }
    }

    // ---- hit-testing -----------------------------------------------------------

    @Test fun tappingALandmarkBlockPicksThatLandmark() {
        // The view's own radius, in picture pixels, at roughly the fitted zoom.
        val radius = 40f
        for (pack in listOf("bdsp", "lumi_plat")) {
            val points = project(pack)
            for ((name, px, py) in landmarks) {
                // Compare on the base name: bdsp calls it "Iron Island", lumi_plat
                // "Iron Island (Overworld)" plus six floors, and either is a correct
                // answer for a tap on that block. Packs ship encounter areas only, so
                // skip a landmark a pack has no row for at all.
                if (points.keys.none { GeographicAnchors.baseKey(it) == name }) continue
                val hit = MapProjection.nearest(points, px, py, radius)
                println("$pack: tap at ($px, $py) -> $hit (wanted $name)")
                assertEquals(
                    "$pack: tap on the $name block",
                    name,
                    hit?.let { GeographicAnchors.baseKey(it) },
                )
            }
        }
    }

    @Test fun tappingEmptyOceanPicksNothing() {
        val points = project("bdsp")
        // Bottom-right corner of the picture: open water in the artwork, and the
        // nearest anchored place (Sunyshore) is a long way off.
        assertNull(MapProjection.nearest(points, 1120f, 918f, 40f))
    }

    @Test fun nearestIsTheNearest() {
        val points = project("bdsp")
        val here = requireNotNull(points["twinleaf town"])
        val hit = MapProjection.nearest(points, here.x, here.y, 5f)
        assertNotNull(hit)
        val got = requireNotNull(points[hit])
        val best = points.values.minOf { hypot(it.x - here.x, it.y - here.y) }
        assertEquals(best, hypot(got.x - here.x, got.y - here.y), 0.001f)
    }
}
