package com.tropicalstream.wanderquest.game

import kotlin.math.pow
import kotlin.random.Random

/**
 * The reward psychology engine.
 *
 * Variable-ratio reinforcement (the strongest schedule there is): every
 * find is a slot-machine roll. Walking quietly loads the dice — "Wander
 * Power" rises with steps taken this session and shifts the rarity table
 * toward the good stuff. The game NEVER says "exercise"; it says your
 * Wander Power is growing.
 */
object Loot {

    const val STEPS_PER_TIER = 400
    const val MAX_TIER = 5
    const val SHINY_ONE_IN = 64

    fun wanderTier(sessionSteps: Long): Int =
        (sessionSteps / STEPS_PER_TIER).toInt().coerceAtMost(MAX_TIER)

    /** Rarity weights, shifted by wander tier. */
    fun rollRarity(tier: Int, rng: Random): Rarity {
        val t = tier.coerceIn(0, MAX_TIER)
        val wCommon = (60 - 7 * t).coerceAtLeast(18)
        val wUncommon = 25
        val wRare = 10 + 3 * t
        val wEpic = 4 + 2 * t
        val wLegendary = 1 + t
        val total = wCommon + wUncommon + wRare + wEpic + wLegendary
        var roll = rng.nextInt(total)
        roll -= wCommon; if (roll < 0) return Rarity.COMMON
        roll -= wUncommon; if (roll < 0) return Rarity.UNCOMMON
        roll -= wRare; if (roll < 0) return Rarity.RARE
        roll -= wEpic; if (roll < 0) return Rarity.EPIC
        return Rarity.LEGENDARY
    }

    fun rollShiny(rng: Random): Boolean = rng.nextInt(SHINY_ONE_IN) == 0

    fun rollItemType(rng: Random): ItemType {
        // Chests are rarer than ordinary trinkets.
        val types = ItemType.entries
        return if (rng.nextInt(100) < 12) ItemType.CHEST
        else types[rng.nextInt(types.size - 1)] // all but CHEST (last entry)
    }

    /** Coin payout for a collect, all multipliers applied. */
    fun coinPayout(
        rarity: Rarity,
        shiny: Boolean,
        tier: Int,
        goldenHour: Boolean,
        duelRelic: Boolean
    ): Long {
        var c = rarity.coins.toDouble()
        c *= 1.0 + 0.10 * tier          // wander power bonus
        if (shiny) c *= 2.0
        if (goldenHour) c *= 2.0
        if (duelRelic) c *= 3.0
        return c.toLong().coerceAtLeast(1)
    }

    /** XP needed to go from `level` to `level + 1`. */
    fun xpForLevel(level: Int): Long = (100.0 * level.toDouble().pow(1.5)).toLong()

    /** Total XP -> level. */
    fun levelForXp(xp: Long): Int {
        var lvl = 1
        var remaining = xp
        while (remaining >= xpForLevel(lvl) && lvl < 99) {
            remaining -= xpForLevel(lvl)
            lvl++
        }
        return lvl
    }

    /** Progress within current level, 0..1. */
    fun levelProgress(xp: Long): Float {
        var lvl = 1
        var remaining = xp
        while (remaining >= xpForLevel(lvl) && lvl < 99) {
            remaining -= xpForLevel(lvl)
            lvl++
        }
        return (remaining.toFloat() / xpForLevel(lvl).toFloat()).coerceIn(0f, 1f)
    }
}
