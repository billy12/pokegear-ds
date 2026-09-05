package com.enrpau.pokegeards.detection

/** Package-scoped broadcast actions from the accessibility service's OCR passes. */
const val ACTION_LOCATION_TEXT = "com.enrpau.pokegeards.LOCATION_TEXT"
const val ACTION_CATCH_TEXT = "com.enrpau.pokegeards.CATCH_TEXT"
const val ACTION_TITLE_TEXT = "com.enrpau.pokegeards.TITLE_TEXT"

/**
 * Not OCR: the verdict of [TitleScreenColorClassifier] on the boot-screen party
 * icons, carried as a pack id in the "PACK_ID" extra.
 */
const val ACTION_TITLE_COLOR = "com.enrpau.pokegeards.TITLE_COLOR"
const val ACTION_POKEMON_DETECTED = "com.enrpau.pokegeards.POKEMON_DETECTED"

/** Full-screen OCR text while a Pokédex rebuild scan is running. */
const val ACTION_DEX_TEXT = "com.enrpau.pokegeards.DEX_TEXT"
