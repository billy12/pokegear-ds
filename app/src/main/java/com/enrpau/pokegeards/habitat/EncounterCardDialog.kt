package com.enrpau.pokegeards.habitat

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.enrpau.pokegeards.PokemonType
import com.enrpau.pokegeards.R
import com.enrpau.pokegeards.ThemeManager
import com.enrpau.pokegeards.data.db.EncounterRow

/**
 * Encounter probability card (design.md §2.4): rate, level range, method/time,
 * condition, base stats, types. Opened on long-press in the grid.
 */
object EncounterCardDialog {

    fun show(context: Context, row: EncounterRow) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_encounter_card)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val s = row.species
        dialog.findViewById<ImageView>(R.id.cardSprite).let {
            SpriteLoader.bind(it, s.spriteKey, caught = true)
        }
        dialog.findViewById<TextView>(R.id.cardName).text = s.name
        dialog.findViewById<TextView>(R.id.cardDexId).text = "#%03d".format(s.id)

        val typeRow = dialog.findViewById<LinearLayout>(R.id.cardTypes)
        typeRow.removeAllViews()
        addTypeBadge(typeRow, s.type1)
        s.type2?.takeIf { it.isNotBlank() && it != "UNKNOWN" }?.let { addTypeBadge(typeRow, it) }

        dialog.findViewById<TextView>(R.id.cardMeta).text = buildString {
            append(methodLabel(row.method))
            if (row.timeOfDay != "ANY") append(" · ${row.timeOfDay.lowercase().replaceFirstChar { it.uppercase() }}")
            append("\n").append(row.levelRange).append("  ·  ").append(row.rateText).append(" chance")
            row.conditionNote
                ?.takeIf { it.isNotBlank() && !it.equals(methodLabel(row.method), true) && !it.equals(row.method, true) }
                ?.let { append("\n").append(it) }
        }

        dialog.findViewById<TextView>(R.id.cardStats).text =
            "HP ${s.baseHp}   ATK ${s.baseAtk}   DEF ${s.baseDef}\n" +
            "SpA ${s.baseSpa}   SpD ${s.baseSpd}   SPE ${s.baseSpe}   ·   BST ${s.baseHp + s.baseAtk + s.baseDef + s.baseSpa + s.baseSpd + s.baseSpe}"

        dialog.findViewById<TextView>(R.id.cardClose).setOnClickListener { dialog.dismiss() }

        // Match the settings theme (OLED, etc.). Type badges keep their own colors.
        val t = ThemeManager.currentTheme
        if (t.id != "dynamic") {
            dialog.findViewById<CardView>(R.id.cardRoot).setCardBackgroundColor(t.gridBackgroundColor)
            dialog.findViewById<TextView>(R.id.cardName).setTextColor(t.headerTextColor)
            dialog.findViewById<TextView>(R.id.cardDexId).setTextColor(t.subTextColor)
            dialog.findViewById<TextView>(R.id.cardMeta).setTextColor(t.headerTextColor)
            dialog.findViewById<TextView>(R.id.cardStats).setTextColor(t.subTextColor)
            dialog.findViewById<TextView>(R.id.cardClose).setTextColor(t.headerTextColor)
        }

        dialog.show()
    }

    private fun addTypeBadge(container: LinearLayout, typeName: String) {
        val type = PokemonType.fromString(typeName)
        val tv = TextView(container.context).apply {
            text = type.displayName.uppercase()
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(24, 8, 24, 8)
            background = GradientDrawable().apply {
                setColor(type.colorHex)
                cornerRadius = 12f
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 0, 12, 0) }
        container.addView(tv, lp)
    }
}
