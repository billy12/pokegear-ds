package com.enrpau.pokegeards.habitat

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.enrpau.pokegeards.MainActivity
import com.enrpau.pokegeards.R
import com.enrpau.pokegeards.data.db.LocationRow
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Lower-screen Habitat / Route tracker (design.md §2.4). Pick an area, see every
 * species that can appear there, tap to toggle caught, filter by method / time,
 * long-press for the encounter card. This is the app's home screen; the Pokédex
 * / battle / settings screen is `MainActivity`, reached from the button here.
 */
class HabitatActivity : AppCompatActivity() {

    private val vm: HabitatViewModel by viewModels()

    private lateinit var btnLocation: MaterialButton
    private lateinit var chipsMethod: ChipGroup
    private lateinit var chipsTime: ChipGroup
    private lateinit var rv: RecyclerView
    private lateinit var tvProgress: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var chipUncaught: Chip
    private lateinit var chipPack: Chip

    private var packs: List<Pair<String, String>> = emptyList()
    private var locationList: List<LocationRow> = emptyList()

    private val adapter = EncounterAdapter(
        onToggleCaught = { row -> vm.setCaught(row.species.id, !row.isCaught) },
        onOpenCard = { row -> EncounterCardDialog.show(this, row) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_habitat)
        title = getString(R.string.habitat_title)

        btnLocation = findViewById(R.id.btnLocation)
        chipsMethod = findViewById(R.id.chipsMethod)
        chipsTime = findViewById(R.id.chipsTime)
        rv = findViewById(R.id.rvEncounters)
        tvProgress = findViewById(R.id.tvProgress)
        tvEmpty = findViewById(R.id.tvEmpty)
        chipUncaught = findViewById(R.id.chipUncaught)
        chipPack = findViewById(R.id.chipPack)

        btnLocation.setOnClickListener { showLocationChooser() }
        chipPack.setOnClickListener { showPackChooser() }
        chipUncaught.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            vm.setUncaughtOnly(checked)
        }
        findViewById<MaterialButton>(R.id.btnMain).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).putExtra("stay", true))
        }

        val span = (resources.configuration.screenWidthDp / 110).coerceIn(2, 6)
        rv.layoutManager = GridLayoutManager(this, span)
        rv.adapter = adapter

        observe()
    }

    private fun observe() {
        vm.locations.observe(this) { locs ->
            locationList = locs
            updateLocationButton(vm.selectedLocationId.value)
        }
        vm.selectedLocationId.observe(this) { updateLocationButton(it) }

        vm.availableMethods.observe(this) { methods ->
            rebuildChips(chipsMethod, methods.map { it to methodLabel(it) }) { vm.toggleMethod(it) }
        }
        vm.availableTimes.observe(this) { times ->
            rebuildChips(chipsTime, times.map { it to it.lowercase().replaceFirstChar { c -> c.uppercase() } }) { vm.toggleTime(it) }
        }

        vm.encounters.observe(this) { rows ->
            adapter.submit(rows)
            tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
        vm.progress.observe(this) { tvProgress.text = it.text }

        vm.packName.observe(this) { chipPack.text = it }
        vm.availablePacks.observe(this) { packs = it }
    }

    private fun updateLocationButton(locationId: Int?) {
        val loc = locationList.firstOrNull { it.id == locationId }
        btnLocation.text = loc?.let { labelFor(it) } ?: getString(R.string.habitat_location_label)
    }

    private fun showLocationChooser() {
        if (locationList.isEmpty()) return
        val labels = locationList.map { labelFor(it) }.toTypedArray()
        val current = locationList.indexOfFirst { it.id == vm.selectedLocationId.value }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.habitat_location_dialog)
            .setSingleChoiceItems(labels, current) { d, which ->
                vm.selectLocation(locationList[which].id)
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPackChooser() {
        if (packs.size < 2) return
        val labels = packs.map { it.second }.toTypedArray()
        val current = packs.indexOfFirst { it.second == chipPack.text }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.habitat_pack_title)
            .setSingleChoiceItems(labels, current) { d, which ->
                vm.selectPack(packs[which].first)
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun labelFor(loc: LocationRow): String =
        if (loc.mapGroup.isNullOrBlank() || loc.mapGroup == "Overworld") loc.name
        else "${loc.name}  ·  ${loc.mapGroup}"

    private fun rebuildChips(
        group: ChipGroup,
        entries: List<Pair<String, String>>,
        onToggle: (String) -> Unit,
    ) {
        group.removeAllViews()
        group.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        for ((value, label) in entries) {
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isCheckedIconVisible = true
                setOnClickListener { onToggle(value) }
            }
            group.addView(chip)
        }
    }
}
