package com.enrpau.pokegeards.habitat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.LruCache
import android.widget.ImageView

/**
 * Decodes `assets/sprites/NNN.png` with a small in-memory cache. No image
 * library — the sprites are ~2 KB each. Uncaught species render as a dark
 * silhouette (alpha preserved, fill flattened).
 */
object SpriteLoader {

    private val cache = LruCache<String, Bitmap>(512)
    private val silhouette = PorterDuffColorFilter(0xFF20242B.toInt(), PorterDuff.Mode.SRC_IN)

    fun bind(view: ImageView, spriteKey: String, caught: Boolean) {
        val bmp = load(view.context, spriteKey)
        view.setImageBitmap(bmp)
        if (bmp == null) return
        if (caught) {
            view.colorFilter = null
            view.alpha = 1f
        } else {
            view.colorFilter = silhouette
            view.alpha = 0.85f
        }
    }

    private fun load(context: Context, spriteKey: String): Bitmap? {
        cache.get(spriteKey)?.let { return it }
        val key = spriteKey.ifBlank { return null }
        return try {
            context.assets.open("sprites/$key.png").use { BitmapFactory.decodeStream(it) }
                ?.also { cache.put(spriteKey, it) }
        } catch (e: Exception) {
            null
        }
    }
}
