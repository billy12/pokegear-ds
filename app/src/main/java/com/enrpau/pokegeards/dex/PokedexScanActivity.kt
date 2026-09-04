package com.enrpau.pokegeards.dex

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.enrpau.pokegeards.AppTheme
import com.enrpau.pokegeards.R
import com.enrpau.pokegeards.ThemeManager
import com.enrpau.pokegeards.data.db.PokegearDb
import com.enrpau.pokegeards.detection.DexRebuildScanner
import com.enrpau.pokegeards.detection.DexScanState
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

/**
 * "Clear and rebuild" the local Pokédex for the active pack. Reached from the
 * Habitat screen after a confirm dialog. On entry it wipes the active pack's
 * caught flags, then runs a full-screen OCR scan (via the accessibility service)
 * while the player scrolls their in-game Pokédex. Every species name it reads is
 * marked caught. Tap Done when the list has been scrolled through.
 */
class PokedexScanActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var scanner: DexRebuildScanner

    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvCaughtLabel: TextView
    private lateinit var tvRecent: TextView
    private lateinit var btnAccessibility: MaterialButton
    private lateinit var btnDone: MaterialButton

    private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pokedex_scan)
        title = getString(R.string.dex_scan_title)

        tvStatus = findViewById(R.id.tvDexStatus)
        tvCount = findViewById(R.id.tvDexCount)
        tvCaughtLabel = findViewById(R.id.tvDexCaughtLabel)
        tvRecent = findViewById(R.id.tvDexRecent)
        btnAccessibility = findViewById(R.id.btnDexAccessibility)
        btnDone = findViewById(R.id.btnDexDone)

        applyTheme()

        scanner = DexRebuildScanner(applicationContext)
        scanner.count.observe(this) { tvCount.text = it.toString() }
        scanner.recent.observe(this) { tvRecent.text = it.joinToString("\n") }

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnDone.setOnClickListener { stopScanAndFinish() }

        if (isAccessibilityServiceEnabled()) {
            btnAccessibility.visibility = View.GONE
            beginScan()
        } else {
            tvStatus.text = getString(R.string.dex_scan_need_service)
            btnAccessibility.visibility = View.VISIBLE
            btnDone.setText(R.string.dex_scan_cancel)
        }
    }

    override fun onResume() {
        super.onResume()
        // Service may have just been enabled from the settings shortcut.
        if (!scanning && isAccessibilityServiceEnabled()) {
            btnAccessibility.visibility = View.GONE
            btnDone.setText(R.string.dex_scan_done)
            beginScan()
        }
    }

    private fun beginScan() {
        if (scanning) return
        scanning = true
        tvStatus.text = getString(R.string.dex_scan_clearing)
        io.execute {
            val db = PokegearDb.get(this)
            db.clearCaughtForActivePack()
            if (!scanning) return@execute   // Done tapped during the wipe
            runOnUiThread {
                tvCount.text = "0"
                tvStatus.text = getString(R.string.dex_scan_running)
            }
            DexScanState.active = true
            scanner.start()
        }
    }

    private fun stopScanAndFinish() {
        if (scanning) {
            DexScanState.active = false
            scanner.stop()
            scanning = false
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (scanning) {
            DexScanState.active = false
            scanner.stop()
            scanning = false
        }
    }

    private fun applyTheme() {
        ThemeManager.loadTheme(this)
        val t: AppTheme = ThemeManager.currentTheme
        findViewById<View>(R.id.dexScanRoot).setBackgroundColor(t.windowBackground)
        for (tv in listOf(tvStatus, tvCaughtLabel, tvRecent)) tv.setTextColor(t.subTextColor)
        tvCount.setTextColor(t.headerTextColor)
        (findViewById<TextView>(R.id.tvDexHeader)).setTextColor(t.headerTextColor)
        btnDone.setTextColor(t.headerTextColor)
        btnAccessibility.setTextColor(t.headerTextColor)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val prefString = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return prefString?.contains("$packageName/$packageName.DualDexAccessibilityService") == true
    }
}
