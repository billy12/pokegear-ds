package com.enrpau.pokegeards.habitat

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.enrpau.pokegeards.R
import com.enrpau.pokegeards.data.db.LocationRow
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Lower-screen Habitat / Route tracker (design.md §2.4). Pick a location, see
 * every species that can appear there, tap to toggle caught, filter by
 * method / time, long-press for the encounter card.
 */
class HabitatActivity : AppCompatActivity() {

    private val vm: HabitatViewModel by viewModels()

    private lateinit var spinner: Spinner
    private lateinit var chipsMethod: ChipGroup
    private lateinit var chipsTime: ChipGroup
    private lateinit var rv: RecyclerView
    private lateinit var tvProgress: TextView
    private lateinit var tvEmpty: TextView

    private val adapter = EncounterAdapter(
        onToggleCaught = { row -> vm.setCaught(row.species.id, !row.isCaught) },
        onOpenCard = { row -> EncounterCardDialog.show(this, row) },
    )

    private var locationList: List<LocationRow> = emptyList()
    private var suppressSpinnerCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_habitat)
        title = getString(R.string.habitat_title)

        spinner = findViewById(R.id.spinnerLocation)
        chipsMethod = findViewById(R.id.chipsMethod)
        chipsTime = findViewById(R.id.chipsTime)
        rv = findViewById(R.id.rvEncounters)
        tvProgress = findViewById(R.id.tvProgress)
        tvEmpty = findViewById(R.id.tvEmpty)

        val span = (resources.configuration.screenWidthDp / 110).coerceIn(2, 6)
        rv.layoutManager = GridLayoutManager(this, span)
        rv.adapter = adapter

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppressSpinnerCallback) return
                locationList.getOrNull(pos)?.let { vm.selectLocation(it.id) }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        observe()
    }

    private fun observe() {
        vm.locations.observe(this) { locs ->
            locationList = locs
            spinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                locs.map { labelFor(it) },
            )
            syncSpinnerSelection(vm.selectedLocationId.value)
        }
        vm.selectedLocationId.observe(this) { syncSpinnerSelection(it) }

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
    }

    private fun labelFor(loc: LocationRow): String =
        if (loc.mapGroup.isNullOrBlank() || loc.mapGroup == "Overworld") loc.name
        else "${loc.name}  ·  ${loc.mapGroup}"

    private fun syncSpinnerSelection(locationId: Int?) {
        val idx = locationList.indexOfFirst { it.id == locationId }
        if (idx >= 0 && idx != spinner.selectedItemPosition) {
            suppressSpinnerCallback = true
            spinner.setSelection(idx)
            spinner.post { suppressSpinnerCallback = false }
        }
    }

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
