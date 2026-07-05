package com.tropicalstream.wanderquest.game

import com.tropicalstream.wanderquest.platform.Geo

enum class Rarity(val label: String, val coins: Int, val xp: Int, val colorIdx: Int) {
    COMMON("Common", 5, 8, 0),
    UNCOMMON("Uncommon", 15, 18, 1),
    RARE("Rare", 40, 40, 2),
    EPIC("Epic", 100, 90, 3),
    LEGENDARY("Legendary", 250, 200, 4)
}

enum class ItemType(val sprite: String, val label: String) {
    GEM("gem", "Star Gem"),
    POTION("potion", "Glow Potion"),
    SCROLL("scroll", "Lost Scroll"),
    KEY("key", "Wander Key"),
    CROWN("crown", "Sun Crown"),
    SHROOM("shroom", "Moon Shroom"),
    RING("ring", "Echo Ring"),
    CHEST("chest", "Mystery Chest")
}

enum class MonsterType(val sprite: String, val scareSprite: String, val label: String) {
    GHOUL("ghoul", "scare_ghoul", "Gloom Ghoul"),
    BAT("bat", "scare_bat", "Dread Bat"),
    WOLF("wolf", "scare_wolf", "Hollow Wolf")
}

enum class GameTheme(val dir: String, val label: String, val unlockLevel: Int) {
    FANTASY("fantasy", "16-bit Fantasy", 1),
    ANIME("anime", "Anime Pastel", 5),
    WESTERN("western", "Western RPG", 10)
}

class WorldItem(
    val id: Long,
    val type: ItemType,
    val rarity: Rarity,
    val shiny: Boolean,
    val duelRelic: Boolean,
    var lat: Double,
    var lon: Double,
    val floor: Int = 0,
    /** >0 = a dropped satchel of stolen coins (thief drop / "a friend may find it"). */
    val droppedCoins: Long = 0,
    /** The climax relic of a journey-leg — claiming it ends the leg. */
    val missionGoal: Boolean = false
) {
    var distanceM = Double.MAX_VALUE
    var bearingDeg = 0.0
    fun updateFrom(lat0: Double, lon0: Double) {
        distanceM = Geo.distanceM(lat0, lon0, lat, lon)
        bearingDeg = Geo.bearingDeg(lat0, lon0, lat, lon)
    }
}

enum class MonsterState { LURK, HUNT, STRIKE, FLEE }

class Monster(
    val id: Long,
    val type: MonsterType,
    var lat: Double,
    var lon: Double,
    val floor: Int = 0,
    /** Shardkeepers guard Crown Shards: more hearts, bigger, guaranteed shard. */
    val isBoss: Boolean = false
) {
    var state = MonsterState.LURK
    var distanceM = Double.MAX_VALUE
    var bearingDeg = 0.0
    var growled = false
    var lurkHeading = Math.random() * 360.0
    /** Loot carried after a successful strike — chase it down to retrieve! */
    var stolenCoins = 0L
    var fleeDeadlineMs = 0L
    /** meters fled since the last footprint was left */
    var traceAccumM = 0.0
    fun updateFrom(lat0: Double, lon0: Double) {
        distanceM = Geo.distanceM(lat0, lon0, lat, lon)
        bearingDeg = Geo.bearingDeg(lat0, lon0, lat, lon)
    }
}

class Shrine(var lat: Double, var lon: Double, val floor: Int = 0) {
    var distanceM = Double.MAX_VALUE
    var bearingDeg = 0.0
    fun updateFrom(lat0: Double, lon0: Double) {
        distanceM = Geo.distanceM(lat0, lon0, lat, lon)
        bearingDeg = Geo.bearingDeg(lat0, lon0, lat, lon)
    }
}

/**
 * Evidence a fleeing thief leaves behind — footprints in the world and on
 * the radar, fading with age. Following the trail finds the monster (and
 * sometimes the loot it fumbled along the way).
 */
class Trace(
    var lat: Double,
    var lon: Double,
    val bornMs: Long,
    val floor: Int,
    val headingDeg: Double
) {
    var distanceM = Double.MAX_VALUE
    var bearingDeg = 0.0
    fun updateFrom(lat0: Double, lon0: Double) {
        distanceM = Geo.distanceM(lat0, lon0, lat, lon)
        bearingDeg = Geo.bearingDeg(lat0, lon0, lat, lon)
    }
}

/** A rival entered via duel result code — feeds the leaderboard. */
data class RivalEntry(
    val rivalName: String,
    val score: Long,
    val won: Boolean,   // did WE win against them
    val dateMs: Long
)

enum class PlaceType(val label: String, val sprite: String) {
    POOL("Healing Pool", "pool"),
    GUILD("Lantern Guild", "npc"),
    BEACON("Beacon", "beacon")
}

/** A persistent, anchored location in the real world — saved forever. */
class Place(
    val type: PlaceType,
    val lat: Double,
    val lon: Double,
    var lit: Boolean = false   // beacons only
) {
    var distanceM = Double.MAX_VALUE
    var bearingDeg = 0.0
    fun updateFrom(lat0: Double, lon0: Double) {
        distanceM = Geo.distanceM(lat0, lon0, lat, lon)
        bearingDeg = Geo.bearingDeg(lat0, lon0, lat, lon)
    }
}

/** Keeper Finn — the friend with quests. */
class Npc(var lat: Double, var lon: Double, val floor: Int, val bornMs: Long) {
    var distanceM = Double.MAX_VALUE
    var bearingDeg = 0.0
    fun updateFrom(lat0: Double, lon0: Double) {
        distanceM = Geo.distanceM(lat0, lon0, lat, lon)
        bearingDeg = Geo.bearingDeg(lat0, lon0, lat, lon)
    }
}

/**
 * The VAULT LEVER — every lever has an apparent purpose: pulling it raises
 * a buried treasure vault out of the ground nearby. Cause, then effect.
 */
class Lever(var lat: Double, var lon: Double, val floor: Int) {
    var distanceM = Double.MAX_VALUE
    var bearingDeg = 0.0
    fun updateFrom(lat0: Double, lon0: Double) {
        distanceM = Geo.distanceM(lat0, lon0, lat, lon)
        bearingDeg = Geo.bearingDeg(lat0, lon0, lat, lon)
    }
}

/**
 * A teleportation portal — step through and the veil drags the world's
 * scattered treasure TO you. Brief, blinking, gone if ignored.
 */
class Portal(var lat: Double, var lon: Double, val floor: Int) {
    val bornMs = System.currentTimeMillis()
    var distanceM = Double.MAX_VALUE
    var bearingDeg = 0.0
    fun updateFrom(lat0: Double, lon0: Double) {
        distanceM = Geo.distanceM(lat0, lon0, lat, lon)
        bearingDeg = Geo.bearingDeg(lat0, lon0, lat, lon)
    }
}

/** A roadside signpost — read it for worldbuilding and gentle direction. */
class Signpost(var lat: Double, var lon: Double, val floor: Int, val text: String) {
    var distanceM = Double.MAX_VALUE
    var bearingDeg = 0.0
    fun updateFrom(lat0: Double, lon0: Double) {
        distanceM = Geo.distanceM(lat0, lon0, lat, lon)
        bearingDeg = Geo.bearingDeg(lat0, lon0, lat, lon)
    }
}

/**
 * COMBAT STRATEGY per antagonist (classic enemy-AI archetypes):
 *
 *  - CHARGER  : aggressive melee hunter. Wide awareness, sprints straight
 *               in and bites at close range. The bread-and-butter predator.
 *  - AMBUSHER : patient melee. Short awareness (it waits), then BURSTS in
 *               fast once you stray close — punishes dawdling.
 *  - KITER    : ranged skirmisher. Keeps an optimal stand-off distance,
 *               pelts you from afar, and backs off if you crowd it.
 *  - BRUTE    : slow heavy melee. Relentless but plodding, easy to outpace
 *               yet it never stops coming — the tank.
 *
 * Params: detectM = aggro radius, speedMps = move speed once engaged,
 * standoffM = distance it tries to hold, cadenceMs = time between attacks,
 * graceMs = wind-up before its first attack after waking. Tuned to stay
 * KID-ESCAPABLE: a steady walk outpaces every melee type; only loitering
 * or turning to fight gets you hit.
 */
enum class CritterAI(
    val detectM: Double, val speedMps: Double, val standoffM: Double,
    val cadenceMs: Long, val graceMs: Long
) {
    CHARGER (detectM = 22.0, speedMps = 1.30, standoffM = 1.2, cadenceMs = 2600, graceMs = 1100),
    AMBUSHER(detectM =  9.0, speedMps = 1.95, standoffM = 1.2, cadenceMs = 2800, graceMs =  700),
    KITER   (detectM = 28.0, speedMps = 1.60, standoffM = 9.0, cadenceMs = 3200, graceMs = 1400),
    BRUTE   (detectM = 16.0, speedMps = 0.85, standoffM = 1.2, cadenceMs = 3600, graceMs = 1500);

    /** Ranged foes fight from the stand-off; melee foes must close in. */
    val ranged: Boolean get() = this == KITER
}

/** Field foes from the old tales — each hunts you its own way. */
/**
 * The bestiary — 30 creatures drawn from world mythology, sorted into six
 * difficulty TIERS. Missions surface creatures of appropriate difficulty:
 * gentle sprites early, dragons and hydras on the long roads. Every entry
 * carries a combat STRATEGY (see CritterAI); the `ranged` flag is derived
 * from that strategy so the two can never drift apart.
 */
enum class CritterType(
    val sprite: String, val label: String, val tier: Int,
    val hp: Int, val xp: Long, val coins: Long,
    val ai: CritterAI, val rangedVerb: String
) {
    // ---- Tier 1 — the small folk ----
    SLIME("slime", "Gloom Slime", 1, 4, 15, 8, CritterAI.KITER, "spits acid"),
    WISP("wisp", "Will-o'-Wisp", 1, 3, 14, 9, CritterAI.KITER, "throws a flare"),
    PIXIE("pixie", "Thornpixie", 1, 4, 16, 10, CritterAI.AMBUSHER, ""),
    KAPPA("kappa", "Kappa", 1, 5, 16, 11, CritterAI.AMBUSHER, ""),
    IMP("imp", "Ember Imp", 1, 4, 15, 10, CritterAI.KITER, "hurls an ember"),
    // ---- Tier 2 — hedge-creepers ----
    GOBLIN("goblin", "Hedge Goblin", 2, 6, 20, 14, CritterAI.CHARGER, ""),
    KOBOLD("kobold", "Kobold", 2, 6, 20, 14, CritterAI.KITER, "slings a stone"),
    GREMLIN("gremlin", "Gremlin", 2, 7, 22, 15, CritterAI.AMBUSHER, ""),
    BAKENEKO("bakeneko", "Bakeneko", 2, 6, 22, 16, CritterAI.AMBUSHER, ""),
    HARPY("harpy", "Harpy", 2, 6, 24, 16, CritterAI.KITER, "looses a feather"),
    // ---- Tier 3 — the restless dead & stone ----
    SKELETON("skeleton", "Rattlebones", 3, 8, 26, 18, CritterAI.KITER, "flings a bone"),
    REDCAP("redcap", "Redcap", 3, 9, 28, 20, CritterAI.CHARGER, ""),
    GARGOYLE("gargoyle", "Gargoyle", 3, 11, 30, 22, CritterAI.BRUTE, ""),
    JACKAL("jackal", "Anubian Jackal", 3, 9, 28, 20, CritterAI.CHARGER, ""),
    DRAUGR("draugr", "Draugr", 3, 10, 30, 22, CritterAI.BRUTE, ""),
    // ---- Tier 4 — the howling night ----
    NAGA("naga", "Naga", 4, 12, 34, 26, CritterAI.KITER, "spits venom"),
    BANSHEE("banshee", "Banshee", 4, 11, 34, 26, CritterAI.KITER, "wails"),
    GHOUL("ghoul", "Gloom Ghoul", 4, 12, 32, 24, CritterAI.CHARGER, ""),
    DIREBAT("bat", "Dread Bat", 4, 10, 30, 22, CritterAI.CHARGER, ""),
    DIREWOLF("wolf", "Hollow Wolf", 4, 12, 34, 26, CritterAI.CHARGER, ""),
    // ---- Tier 5 — giants & horrors ----
    MINOTAUR("minotaur", "Minotaur", 5, 15, 42, 34, CritterAI.CHARGER, ""),
    ONI("oni", "Oni", 5, 16, 44, 36, CritterAI.BRUTE, ""),
    GOLEM("golem", "Clay Golem", 5, 18, 46, 38, CritterAI.BRUTE, ""),
    CYCLOPS("cyclops", "Cyclops", 5, 16, 44, 36, CritterAI.KITER, "lobs a boulder"),
    WENDIGO("wendigo", "Wendigo", 5, 15, 42, 34, CritterAI.CHARGER, ""),
    // ---- Tier 6 — the legends ----
    CERBERUS("cerberus", "Cerberus", 6, 20, 60, 55, CritterAI.CHARGER, ""),
    MANTICORE("manticore", "Manticore", 6, 18, 58, 52, CritterAI.KITER, "fires a tail-spike"),
    DJINN("djinn", "Djinn", 6, 18, 58, 52, CritterAI.KITER, "casts a bolt"),
    DRAGON("dragon", "Wyrm", 6, 22, 70, 65, CritterAI.KITER, "breathes fire"),
    HYDRA("hydra", "Hydra", 6, 24, 72, 68, CritterAI.KITER, "spits venom");

    /** Derived from strategy — kept in lock-step with the AI archetype. */
    val ranged: Boolean get() = ai.ranged

    companion object {
        fun ofTier(range: IntRange, rng: kotlin.random.Random): CritterType {
            val pool = entries.filter { it.tier in range }
            return pool[rng.nextInt(pool.size)]
        }
        /** Difficulty band a given journey-leg may draw from. */
        fun bandFor(mission: Int): IntRange = when (mission) {
            1 -> 1..2
            2 -> 1..3
            3 -> 2..4
            4 -> 2..5
            5 -> 3..5
            else -> 3..6
        }
    }
}

class Critter(
    val id: Long,
    val type: CritterType,
    var lat: Double,
    var lon: Double,
    val floor: Int,
    /** A leg-WARDEN: a boss that must fall to finish the journey-leg. */
    val isBoss: Boolean = false
) {
    var hp = if (isBoss) type.hp * 3 else type.hp
    var hpMax = hp
    var engagedMs = 0L        // first hit — it starts nipping back
    var lastNipMs = 0L
    var lurkHeading = Math.random() * 360.0
    val bornMs = System.currentTimeMillis()
    var distanceM = Double.MAX_VALUE
    var bearingDeg = 0.0
    fun updateFrom(lat0: Double, lon0: Double) {
        distanceM = Geo.distanceM(lat0, lon0, lat, lon)
        bearingDeg = Geo.bearingDeg(lat0, lon0, lat, lon)
    }
}

/** Herbs, flowers, ingredients — the alchemy layer underfoot. */
enum class HerbType(val sprite: String, val label: String, val rare: Boolean) {
    SUNPETAL("sunpetal", "Sunpetal", false),
    MOONLEAF("moonleaf", "Moonleaf", false),
    GLOWCAP("glowcap", "Glowcap", false),
    DEWROOT("dewroot", "Dewroot", false),
    STARBLOOM("starbloom", "Starbloom", true)
}

class Herb(
    val id: Long,
    val type: HerbType,
    var lat: Double,
    var lon: Double,
    val floor: Int
) {
    var distanceM = Double.MAX_VALUE
    var bearingDeg = 0.0
    fun updateFrom(lat0: Double, lon0: Double) {
        distanceM = Geo.distanceM(lat0, lon0, lat, lon)
        bearingDeg = Geo.bearingDeg(lat0, lon0, lat, lon)
    }
}

/**
 * Ambient scenery: a wireframe building anchored in the world. Purely
 * decorative — never blocks movement or interaction — it just gives the
 * streets a skyline so the world doesn't feel empty. Rendered as a faint
 * outlined box; kept SPARSE and DIM so it never crowds the play layer.
 */
class Building(
    var lat: Double,
    var lon: Double,
    val floor: Int,
    /** facade width in metres */
    val widthM: Double,
    /** height in metres */
    val heightM: Double,
    /** 0..1 style seed — drives how many wireframe storey/mullion lines */
    val style: Double
) {
    var distanceM = Double.MAX_VALUE
    var bearingDeg = 0.0
    fun updateFrom(lat0: Double, lon0: Double) {
        distanceM = Geo.distanceM(lat0, lon0, lat, lon)
        bearingDeg = Geo.bearingDeg(lat0, lon0, lat, lon)
    }
}

enum class Screen {
    INTRO, TITLE, ROAMING, MENU, JOURNAL, GRIMOIRE, SETTINGS, DUEL, SCARE,
    BATTLE, DIALOG, GUILD, QUESTS, CALIBRATE, GUIDE
}
