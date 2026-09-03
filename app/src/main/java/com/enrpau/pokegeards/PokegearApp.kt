package com.enrpau.pokegeards

import android.app.Application
import com.enrpau.pokegeards.detection.GameStateRepository

class PokegearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // start the OCR route-banner detector + auto-catch tracker
        GameStateRepository.init(this)
    }
}
