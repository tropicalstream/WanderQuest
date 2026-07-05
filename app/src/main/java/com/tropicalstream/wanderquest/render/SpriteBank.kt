package com.tropicalstream.wanderquest.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Loads the 16-bit sprite set for the active theme from
 * assets/sprites/<theme>/<name>.png. Bitmaps stay at native pixel size;
 * the renderer scales them with nearest-neighbor (no filtering) so the
 * pixels stay crisp on the waveguide.
 */
class SpriteBank(private val context: Context) {

    private val cache = HashMap<String, Bitmap>()
    private var loadedDir = ""

    fun ensureTheme(themeDir: String) {
        if (themeDir == loadedDir) return
        cache.values.forEach { it.recycle() }
        cache.clear()
        loadedDir = themeDir
        val names = listOf(
            "gem", "potion", "scroll", "key", "crown", "shroom", "ring", "chest",
            "satchel", "shrine", "ghoul", "bat", "wolf",
            "scare_ghoul", "scare_bat", "scare_wolf",
            "heart", "ghost", "npc", "beacon", "lever", "pool",
            "wand", "rod", "staff", "armor", "boots",
            "slime", "goblin", "imp", "skeleton", "mimic",
            "sunpetal", "moonleaf", "glowcap", "dewroot", "starbloom", "portal",
            "sign", "well",
            // mythological bestiary
            "wisp", "pixie", "kappa", "kobold", "gremlin", "bakeneko", "harpy",
            "redcap", "gargoyle", "jackal", "draugr", "naga", "banshee",
            "minotaur", "oni", "golem", "cyclops", "wendigo", "cerberus",
            "manticore", "djinn", "dragon", "hydra"
        )
        for (name in names) loadInto(name, themeDir)
    }

    private val opts = BitmapFactory.Options().apply { inScaled = false }

    private fun loadInto(name: String, themeDir: String): Bitmap? {
        runCatching {
            context.assets.open("sprites/$themeDir/$name.png").use { stream ->
                val bmp = BitmapFactory.decodeStream(stream, null, opts)
                if (bmp != null) { cache[name] = bmp; return bmp }
            }
        }
        return null
    }

    /**
     * Returns the themed sprite, or — if it is somehow missing for this
     * theme (e.g. a new sprite added to code but not yet generated in every
     * theme) — falls back to the always-present "fantasy" version, so a
     * world object is NEVER silently invisible. Cached either way.
     */
    fun get(name: String): Bitmap? {
        cache[name]?.let { return it }
        loadInto(name, loadedDir)?.let { return it }
        if (loadedDir != "fantasy") loadInto(name, "fantasy")?.let { return it }
        return null
    }
}
