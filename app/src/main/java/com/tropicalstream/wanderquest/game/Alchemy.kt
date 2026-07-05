package com.tropicalstream.wanderquest.game

/**
 * Alchemy — "when enough is collected, it creates something magical."
 *
 * Herbs are gathered underfoot as you walk; the moment a recipe's
 * ingredients are all in the pouch, the craft happens BY ITSELF with a
 * shimmer (no menus — the magic is the surprise). Crafted wonders are
 * SPECIAL ITEMS: hold one (swipe ⇄) and tap the LEFT temple to use it.
 */
enum class SpecialItem(val label: String, val desc: String) {
    HEART_TONIC("Heart Tonic", "Restores all hearts"),
    MANA_ELIXIR("Mana Elixir", "Restores all mana"),
    SMOKE_BOMB("Smoke Bomb", "Escape a battle — nothing stolen"),
    PHOENIX_FEATHER("Phoenix Feather", "Auto-revives you once when you fall"),
    LUCKY_CHARM("Lucky Charm", "Double loot for 10 minutes")
}

class Recipe(val result: SpecialItem, val needs: Map<HerbType, Int>) {
    fun describe(): String =
        needs.entries.joinToString(" + ") { "${it.value} ${it.key.label}" } + " → ${result.label}"
}

object Alchemy {

    val recipes = listOf(
        Recipe(SpecialItem.HEART_TONIC, mapOf(HerbType.SUNPETAL to 4, HerbType.MOONLEAF to 2)),
        Recipe(SpecialItem.MANA_ELIXIR, mapOf(HerbType.GLOWCAP to 3, HerbType.DEWROOT to 2)),
        Recipe(SpecialItem.SMOKE_BOMB, mapOf(HerbType.MOONLEAF to 2, HerbType.DEWROOT to 2)),
        Recipe(SpecialItem.PHOENIX_FEATHER, mapOf(HerbType.STARBLOOM to 1, HerbType.SUNPETAL to 2)),
        Recipe(SpecialItem.LUCKY_CHARM, mapOf(HerbType.STARBLOOM to 1, HerbType.GLOWCAP to 2))
    )

    /**
     * Try to craft. Consumes ingredients and returns the crafted item, or
     * null. Called after every herb picked.
     */
    fun tryCraft(herbs: MutableMap<String, Long>): SpecialItem? {
        for (recipe in recipes) {
            val canMake = recipe.needs.all { (herb, n) -> (herbs[herb.name] ?: 0L) >= n }
            if (canMake) {
                for ((herb, n) in recipe.needs) {
                    herbs[herb.name] = (herbs[herb.name] ?: 0L) - n
                }
                return recipe.result
            }
        }
        return null
    }
}
