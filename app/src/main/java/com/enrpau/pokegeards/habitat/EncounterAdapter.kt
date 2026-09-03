package com.enrpau.pokegeards.habitat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.enrpau.pokegeards.R
import com.enrpau.pokegeards.data.db.EncounterRow

/**
 * Encounter grid (design.md §2.4). Tap toggles caught; long-press opens the
 * detail card.
 */
class EncounterAdapter(
    private val onToggleCaught: (EncounterRow) -> Unit,
    private val onOpenCard: (EncounterRow) -> Unit,
) : RecyclerView.Adapter<EncounterAdapter.VH>() {

    private val items = ArrayList<EncounterRow>()

    fun submit(rows: List<EncounterRow>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = rows.size
            override fun areItemsTheSame(o: Int, n: Int) =
                items[o].encounterId == rows[n].encounterId
            override fun areContentsTheSame(o: Int, n: Int) = items[o] == rows[n]
        })
        items.clear()
        items.addAll(rows)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_encounter, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val sprite: ImageView = itemView.findViewById(R.id.encSprite)
        private val name: TextView = itemView.findViewById(R.id.encName)
        private val level: TextView = itemView.findViewById(R.id.encLevel)
        private val rate: TextView = itemView.findViewById(R.id.encRate)
        private val tag: TextView = itemView.findViewById(R.id.encMethodTag)
        private val caughtDot: View = itemView.findViewById(R.id.encCaughtDot)

        fun bind(row: EncounterRow) {
            SpriteLoader.bind(sprite, row.species.spriteKey, row.isCaught)
            name.text = row.species.name
            level.text = row.levelRange
            rate.text = row.rateText
            tag.text = methodLabel(row.method) + timeSuffix(row.timeOfDay)
            caughtDot.visibility = if (row.isCaught) View.VISIBLE else View.INVISIBLE
            name.alpha = if (row.isCaught) 1f else 0.6f

            itemView.setOnClickListener { onToggleCaught(row) }
            itemView.setOnLongClickListener { onOpenCard(row); true }
        }
    }
}

fun methodLabel(method: String): String = when (method) {
    "WALK" -> "Grass"
    "SURF" -> "Surf"
    "OLD_ROD" -> "Old Rod"
    "GOOD_ROD" -> "Good Rod"
    "SUPER_ROD" -> "Super Rod"
    "ROCK_SMASH" -> "Rock Smash"
    "HONEY_TREE" -> "Honey Tree"
    "RADAR" -> "PokéRadar"
    "SWARM" -> "Swarm"
    "GRAND_UNDERGROUND" -> "Underground"
    "STATIC" -> "Gift/Static"
    else -> method.lowercase().replaceFirstChar { it.uppercase() }
}

private fun timeSuffix(time: String): String = when (time) {
    "MORNING" -> " · AM"
    "DAY" -> " · Day"
    "NIGHT" -> " · Night"
    else -> ""
}
