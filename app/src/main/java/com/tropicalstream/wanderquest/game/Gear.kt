package com.tropicalstream.wanderquest.game

/**
 * Gear that DOES something — the D&D layer.
 * Weapons swing with your arm (or a temple tap); swipe the pad to switch.
 */
enum class Weapon(
    val label: String,
    val sprite: String,
    val damage: Int,
    val manaCost: Int,
    val price: Long,
    val slowsFoe: Boolean,
    val flavor: String
) {
    SPARK("Spark Wand", "wand", 1, 0, 0, false, "A humble wand. It never runs dry."),
    EMBER("Ember Rod", "rod", 2, 2, 300, false, "Burns twice as bright — costs mana."),
    FROST("Frost Staff", "staff", 1, 2, 800, true, "Chills foes; they strike far slower.")
}

enum class ArmorKind(
    val label: String,
    val absorbPct: Int,
    val price: Long,
    val flavor: String
) {
    CLOAK("Traveler's Cloak", 0, 0, "Keeps the rain off, not the teeth."),
    LEATHER("Leather Jerkin", 30, 250, "Turns 3 bites in 10."),
    MOON("Moonsilver Mail", 55, 700, "Gloom-fangs slide right off.")
}

/** A guild-shop row. */
class ShopEntry(
    val key: String,
    val label: String,
    val price: Long,
    val desc: String
)

object Gear {
    fun shopFor(stats: StatsStore): List<ShopEntry> {
        val rows = ArrayList<ShopEntry>()
        for (w in Weapon.entries) {
            if (!stats.ownedWeapons.contains(w.name)) {
                rows.add(ShopEntry("W:${w.name}", w.label, w.price, w.flavor))
            }
        }
        val nextArmor = when (stats.armor) {
            ArmorKind.CLOAK.name -> ArmorKind.LEATHER
            ArmorKind.LEATHER.name -> ArmorKind.MOON
            else -> null
        }
        if (nextArmor != null) {
            rows.add(ShopEntry("A:${nextArmor.name}", nextArmor.label, nextArmor.price, nextArmor.flavor))
        }
        if (!stats.bootsOwned) {
            rows.add(ShopEntry("B:BOOTS", "Striding Boots", 200, "Monsters lag behind; dodging is easier."))
        }
        if (stats.maxHearts < 6) {
            rows.add(ShopEntry("H:HEART", "Heart Crystal", 400L + 400L * (stats.maxHearts - 3), "+1 heart, forever."))
        }
        return rows
    }

    /** Returns a result message, or null if it couldn't be bought. */
    fun buy(stats: StatsStore, entry: ShopEntry): String? {
        if (stats.bankCoins < entry.price) return null
        val parts = entry.key.split(":")
        if (parts[0] !in setOf("W", "A", "B", "H")) return null  // validate BEFORE deducting
        stats.bankCoins -= entry.price
        return when (parts[0]) {
            "W" -> {
                stats.ownedWeapons.add(parts[1])
                stats.equippedWeapon = parts[1]
                "${entry.label} readied!"
            }
            "A" -> {
                stats.armor = parts[1]
                "${entry.label} donned!"
            }
            "B" -> {
                stats.bootsOwned = true
                "You feel lighter on your feet!"
            }
            "H" -> {
                stats.maxHearts += 1
                stats.hearts = stats.maxHearts
                "Your heart grows stronger!"
            }
            else -> null
        }
    }

    fun armorOf(stats: StatsStore): ArmorKind =
        runCatching { ArmorKind.valueOf(stats.armor) }.getOrDefault(ArmorKind.CLOAK)

    fun weaponOf(stats: StatsStore): Weapon =
        runCatching { Weapon.valueOf(stats.equippedWeapon) }.getOrDefault(Weapon.SPARK)
}
