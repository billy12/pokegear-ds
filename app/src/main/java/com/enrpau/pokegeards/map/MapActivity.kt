package com.enrpau.pokegeards.map

import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.enrpau.pokegeards.AppTheme
import com.enrpau.pokegeards.R
import com.enrpau.pokegeards.ThemeManager
import com.enrpau.pokegeards.data.db.LocationRow
import com.enrpau.pokegeards.data.db.PokegearDb
import com.enrpau.pokegeards.detection.GameStateRepository
import com.enrpau.pokegeards.detection.PackAdjacency
import com.enrpau.pokegeards.detection.SinnohAdjacency
import com.enrpau.pokegeards.habitat.EncounterAdapter
import com.enrpau.pokegeards.habitat.EncounterCardDialog
import java.util.concurrent.Executors

/**
 * Region map. Every area in the active pack's `location` table gets a tile, laid
 * out by [GraphLayout] from the [SinnohAdjacency] connectivity graph, so
 * neighbouring areas end up next to each other and the whole thing reads as a
 * map instead of an alphabetical grid. Pinch to zoom, drag to pan.
 *
 * Tapping a tile shows the unfiltered encounter list for that area, straight from
 * [PokegearDb.getEncounters] — unchanged from the grid version of this screen.
 *
 * The tile for wherever detection currently thinks the player is pulses, and
 * follows [GameStateRepository.state] live.
 */
class MapActivity : AppCompatActivity() {

    private val db by lazy { PokegearDb.get(this) }
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var canvas: MapCanvasView
    private lateinit var tvPack: TextView
    private lateinit var tvEmpty: TextView

    private var theme: AppTheme? = null

    /** Set once the tiles are on screen, so we can centre on the player once. */
    private var centredOnPlayer = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)
        title = getString(R.string.map_title)

        canvas = findViewById(R.id.mapCanvas)
        tvPack = findViewById(R.id.tvMapPack)
        tvEmpty = findViewById(R.id.tvMapEmpty)

        canvas.onTileTap = { loc -> showArea(loc) }

        applyTheme()
        load()
        followCurrentLocation()
    }

    override fun onResume() {
        super.onResume()
        // Settings screen may have changed the theme while we were backgrounded.
        ThemeManager.loadTheme(this)
        if (ThemeManager.currentTheme.id != theme?.id) applyTheme()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun load() {
        val nodeW = canvas.tileWorldWidth.toDouble()
        val nodeH = canvas.tileWorldHeight.toDouble()
        io.execute {
            val locs = db.getLocations()
            val active = db.activePackId()
            val name = db.availablePacks().firstOrNull { it.first == active }?.second ?: active
            // Packs that use unsplit names (bdsp: "Route 204", not "Route 204
            // (South)") match no SinnohAdjacency key, so resolve through the
            // base-name fallback rather than the raw edge list.
            val edges = PackAdjacency.forPack(locs.map { it.name })
            // ~150 nodes x ~300 force iterations — cheap, but not on the main thread.
            val started = System.currentTimeMillis()
            val layout = GraphLayout.layout(
                nodes = locs.map { it.name.lowercase() },
                edges = edges,
                nodeWidth = nodeW,
                nodeHeight = nodeH,
                padding = nodeH * 0.35,
            )
            android.util.Log.d(
                TAG,
                "layout ${locs.size} areas in ${System.currentTimeMillis() - started}ms, " +
                    "${layout.iterations} iterations, ${layout.isolated.size} unconnected",
            )
            main.post {
                if (isFinishing || isDestroyed) return@post
                tvPack.text = getString(R.string.map_title) + "  ·  " + name
                canvas.submit(locs, layout, edges)
                theme?.let { canvas.applyTheme(it) }
                tvEmpty.visibility = if (locs.isEmpty()) View.VISIBLE else View.GONE
                canvas.visibility = if (locs.isEmpty()) View.GONE else View.VISIBLE
                // The location observer may well have fired before the tiles
                // existed; now they do, so take the opening view again.
                centreOnPlayerOnce()
            }
        }
    }

    /**
     * Same source the Habitat screen follows, so the pulsing tile moves the moment
     * OCR, the bridge or the manual picker changes the location — no reopening.
     * The zone-id branch mirrors HabitatViewModel's.
     */
    private fun followCurrentLocation() {
        GameStateRepository.state.observe(this) { gs ->
            gs ?: return@observe
            val direct = gs.locationId
            if (direct != null) {
                onCurrentLocation(direct)
            } else {
                val zone = gs.zoneId ?: return@observe
                io.execute {
                    val id = db.locationForZone(zone) ?: return@execute
                    main.post { if (!isFinishing && !isDestroyed) onCurrentLocation(id) }
                }
            }
        }
    }

    private fun onCurrentLocation(id: Int) {
        canvas.setCurrentLocation(id)
        centreOnPlayerOnce()
    }

    /** Opening shot only — after that the map stays wherever the user left it. */
    private fun centreOnPlayerOnce() {
        if (centredOnPlayer) return
        canvas.post { if (canvas.centreOnCurrent()) centredOnPlayer = true }
    }

    /** Carry the DualScreenDex settings theme (OLED, etc.) into the map screen. */
    private fun applyTheme() {
        ThemeManager.loadTheme(this)
        val t = ThemeManager.currentTheme
        theme = t

        findViewById<View>(R.id.mapRoot).setBackgroundColor(t.windowBackground)
        tvPack.setTextColor(t.headerTextColor)
        tvEmpty.setTextColor(t.subTextColor)
        findViewById<TextView>(R.id.tvMapHint).setTextColor(t.subTextColor)

        canvas.applyTheme(t)
    }

    /**
     * Unfiltered encounter list for one area — no method/time chips here on
     * purpose, so the numbers are the averaged "show all" rates.
     */
    private fun showArea(loc: LocationRow) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_map_area)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        // Without this the window wraps its (initially empty) list and the card
        // collapses to a sliver.
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        val tvName = dialog.findViewById<TextView>(R.id.areaName)
        val tvKind = dialog.findViewById<TextView>(R.id.areaKind)
        val tvEmptyArea = dialog.findViewById<TextView>(R.id.areaEmpty)
        val list = dialog.findViewById<RecyclerView>(R.id.areaEncounters)
        val close = dialog.findViewById<TextView>(R.id.areaClose)

        tvName.text = loc.name
        tvKind.text = AreaKind.of(loc).label +
            (loc.mapGroup?.takeIf { it.isNotBlank() && it != "Overworld" }?.let { "  ·  $it" } ?: "")

        // Read-only here: tap or long-press opens the detail card, catching stays
        // on the Habitat screen.
        val encAdapter = EncounterAdapter(
            onToggleCaught = { row -> EncounterCardDialog.show(this, row) },
            onOpenCard = { row -> EncounterCardDialog.show(this, row) },
        )
        list.layoutManager = GridLayoutManager(this, 3)
        list.adapter = encAdapter

        theme?.let { t ->
            dialog.findViewById<CardView>(R.id.areaRoot).setCardBackgroundColor(t.gridBackgroundColor)
            tvName.setTextColor(t.headerTextColor)
            tvKind.setTextColor(t.subTextColor)
            tvEmptyArea.setTextColor(t.subTextColor)
            close.setTextColor(t.headerTextColor)
            encAdapter.applyTheme(t)
        }

        close.setOnClickListener { dialog.dismiss() }
        dialog.show()

        io.execute {
            val rows = db.getEncounters(loc.id)
            main.post {
                if (!dialog.isShowing) return@post
                encAdapter.submit(rows)
                tvEmptyArea.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                list.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
                capListHeight(list)
            }
        }
    }

    /** RecyclerView wrap_content would let a big area run off-screen; cap it. */
    private fun capListHeight(list: RecyclerView) {
        val max = (380 * resources.displayMetrics.density).toInt()
        list.post {
            if (list.height > max) {
                list.layoutParams = list.layoutParams.also { it.height = max }
                list.requestLayout()
            }
        }
    }

    private companion object {
        const val TAG = "RegionMap"
    }
}
