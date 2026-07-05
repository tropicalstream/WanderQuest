package com.tropicalstream.wanderquest.game

import com.tropicalstream.wanderquest.platform.Geo
import kotlin.random.Random

/**
 * Keeps the world stocked: trinkets in a ring around the player, one shrine
 * to bank loot at, and the things that hunt you.
 *
 * Two scales:
 *  - OUTDOOR (GPS): city-block distances.
 *  - INDOOR / Hearth (`indoorHalfM != null`): the space starts as a
 *    50x50 ft hall (half-size ~7.6 m) so the first trinket is seconds away
 *    — instant hook — and the engine grows the bounds as the player roams
 *    past them. Floors (stairs) tag every spawn; things on another storey
 *    don't interact with you.
 *
 * Items spawn just out of reach (the near-miss principle — visible on the
 * radar before they're collectable) and despawn when left far behind.
 */
class SpawnManager(private val rng: Random = Random(System.nanoTime())) {

    companion object {
        // outdoor scale. SCARCITY IS THE POINT: a find must be earned —
        // few treasures, spawned far, hunted by geiger and radar.
        const val ITEM_MIN_SPAWN_M = 50.0
        const val ITEM_MAX_SPAWN_M = 190.0
        const val ITEM_DESPAWN_M = 300.0
        const val TARGET_ITEM_COUNT = 3
        const val COLLECT_RADIUS_M = 1.5     // items: must be ~1 m away to grab
        const val COMBAT_RANGE_M = 14.0      // foes can be struck from range
        const val SHRINE_MIN_M = 70.0
        const val SHRINE_MAX_M = 160.0
        const val MONSTER_SPAWN_MIN_M = 90.0
        const val MONSTER_SPAWN_MAX_M = 200.0
        const val ESCAPE_DIST_M = 150.0
        const val FLEE_DEADLINE_MS = 180_000L

        // indoor scale
        const val INDOOR_START_HALF_M = 7.6        // 50 ft / 2
        const val INDOOR_COLLECT_RADIUS_M = 3.0
        const val INDOOR_ITEM_MIN_SPAWN_M = 4.0
        const val INDOOR_TARGET_ITEM_COUNT = 2
        const val INDOOR_STRIKE_M = 2.0
        const val STRIKE_M = 9.0

        // the evidence trail
        const val TRACE_INTERVAL_OUTDOOR_M = 9.0
        const val TRACE_INTERVAL_INDOOR_M = 2.5
        const val TRACE_LIFETIME_MS = 150_000L
        const val TRACE_DROP_CHANCE_PCT = 12   // per footprint: thief fumbles loot
    }

    val items = ArrayList<WorldItem>()
    val monsters = ArrayList<Monster>()
    val traces = ArrayList<Trace>()
    val critters = ArrayList<Critter>()   // field foes — they hunt, you fight
    val herbs = ArrayList<Herb>()         // ingredients underfoot
    val buildings = ArrayList<Building>() // ambient wireframe skyline (decor)
    var portal: Portal? = null            // the veil, when it opens
    var shrine: Shrine? = null
    var npc: Npc? = null          // Keeper Finn / a villager, when about
    var lever: Lever? = null      // the vault lever
    var sign: Signpost? = null    // a roadside signpost
    var npcIsVillager = false     // the current npc is a generic villager
    private var lastNpcGoneMs = 0L
    private var lastLeverMs = 0L
    private var lastSignMs = 0L

    /** null = outdoor GPS scale; otherwise current Hearth half-size in m. */
    var indoorHalfM: Double? = null

    private var nextId = 1L

    val indoor get() = indoorHalfM != null
    fun collectRadius() = if (indoor) INDOOR_COLLECT_RADIUS_M else COLLECT_RADIUS_M
    fun combatRange() = if (indoor) INDOOR_COLLECT_RADIUS_M * 2 else COMBAT_RANGE_M
    fun strikeRadius() = if (indoor) INDOOR_STRIKE_M else STRIKE_M
    fun escapeDist() = indoorHalfM?.let { (it * 1.2).coerceAtLeast(10.0) } ?: ESCAPE_DIST_M
    private fun huntRange() = indoorHalfM?.let { (it * 0.9).coerceAtLeast(8.0) } ?: 110.0

    fun reset() {
        items.clear()
        monsters.clear()
        traces.clear()
        critters.clear()
        herbs.clear()
        buildings.clear()
        portal = null
        shrine = null
        npc = null
        lever = null
        sign = null
    }

    /** A roadside signpost appears (~every 120 m). */
    fun maybeSpawnSign(lat: Double, lon: Double, floor: Int, text: String): Boolean {
        val nowMs = System.currentTimeMillis()
        if (sign != null || nowMs - lastSignMs < 60_000L) return false
        lastSignMs = nowMs
        val bearing = rng.nextDouble(0.0, 360.0)
        val dist = rng.nextDouble(30.0, 90.0)
        val (slat, slon) = Geo.destination(lat, lon, bearing, dist)
        sign = Signpost(slat, slon, floor, text).also { it.updateFrom(lat, lon) }
        return true
    }

    // ---- IMPORTANCE-TIERED ITEM SPAWNS (distance-driven by the engine) ----

    /** MEDIUM importance: a trinket/tool 30–120 m out (~every 100 m walked). */
    fun spawnMediumItem(lat: Double, lon: Double, floor: Int, tier: Int, duelActive: Boolean): WorldItem? {
        if (items.count { it.droppedCoins == 0L && it.type != ItemType.CHEST && it.type != ItemType.CROWN } >= 6) return null
        val mediumTypes = listOf(ItemType.GEM, ItemType.POTION, ItemType.SCROLL, ItemType.RING, ItemType.SHROOM, ItemType.KEY)
        val type = mediumTypes[rng.nextInt(mediumTypes.size)]
        val bearing = rng.nextDouble(0.0, 360.0)
        val dist = rng.nextDouble(30.0, 120.0)
        val (ilat, ilon) = Geo.destination(lat, lon, bearing, dist)
        val item = WorldItem(nextId++, type, Loot.rollRarity(tier, rng), Loot.rollShiny(rng),
            duelActive && rng.nextInt(100) < 18, ilat, ilon, floor)
        item.updateFrom(lat, lon)
        items.add(item)
        return item
    }

    /** HIGH importance: a vault or crown 80–280 m out (~every 300 m walked). */
    fun spawnHighItem(lat: Double, lon: Double, floor: Int, tier: Int, duelActive: Boolean): WorldItem? {
        if (items.count { it.type == ItemType.CHEST || it.type == ItemType.CROWN } >= 3) return null
        val type = if (rng.nextInt(100) < 60) ItemType.CHEST else ItemType.CROWN
        val rarity = if (type == ItemType.CROWN) (if (rng.nextInt(3) == 0) Rarity.LEGENDARY else Rarity.EPIC)
        else Loot.rollRarity(tier + 1, rng)
        val bearing = rng.nextDouble(0.0, 360.0)
        val dist = rng.nextDouble(80.0, 280.0)
        val (ilat, ilon) = Geo.destination(lat, lon, bearing, dist)
        val item = WorldItem(nextId++, type, rarity, Loot.rollShiny(rng),
            duelActive && rng.nextInt(100) < 25, ilat, ilon, floor)
        item.updateFrom(lat, lon)
        items.add(item)
        return item
    }

    /** Step-rolled: a field foe wanders in (~1 per 40 paces). */
    /** A leg-WARDEN boss: triple HP, spawned a little farther out. */
    fun spawnBossCritter(lat: Double, lon: Double, floor: Int, type: CritterType): Critter {
        val half = indoorHalfM
        val bearing = rng.nextDouble(0.0, 360.0)
        val dist = if (half != null) rng.nextDouble(half * 0.6, (half * 0.95).coerceAtLeast(half * 0.6 + 1.0))
        else rng.nextDouble(40.0, 90.0)
        val (clat, clon) = Geo.destination(lat, lon, bearing, dist)
        val c = Critter(System.nanoTime(), type, clat, clon, floor, isBoss = true)
        c.updateFrom(lat, lon)
        critters.add(c)
        return c
    }

    fun maybeSpawnCritter(lat: Double, lon: Double, floor: Int, type: CritterType): Critter? {
        if (critters.size >= 6) return null   // packs can form near treasure
        val half = indoorHalfM
        val bearing = rng.nextDouble(0.0, 360.0)
        val dist = if (half != null) rng.nextDouble(2.5, (half * 0.7).coerceAtLeast(4.0))
        else rng.nextDouble(9.0, 24.0)
        val (clat, clon) = Geo.destination(lat, lon, bearing, dist)
        val c = Critter(System.nanoTime(), type, clat, clon, floor)
        c.updateFrom(lat, lon)
        critters.add(c)
        return c
    }

    /** Step-rolled: an herb sprouts nearby (~1 per 20 paces). */
    fun maybeSpawnHerb(lat: Double, lon: Double, floor: Int): Herb? {
        if (herbs.size >= 5) return null
        val half = indoorHalfM
        val bearing = rng.nextDouble(0.0, 360.0)
        val dist = if (half != null) rng.nextDouble(1.5, (half * 0.6).coerceAtLeast(3.0))
        else rng.nextDouble(4.0, 15.0)
        val (hlat, hlon) = Geo.destination(lat, lon, bearing, dist)
        // Starbloom is the legend: 1 in 12
        val type = if (rng.nextInt(12) == 0) HerbType.STARBLOOM
        else HerbType.entries.filter { !it.rare }[rng.nextInt(4)]
        val h = Herb(System.nanoTime(), type, hlat, hlon, floor)
        h.updateFrom(lat, lon)
        herbs.add(h)
        return h
    }

    /** Step-rolled (~1 per 50 paces): the veil opens within 20 paces. */
    fun maybeSpawnPortal(lat: Double, lon: Double, floor: Int): Portal? {
        if (portal != null) return null
        val half = indoorHalfM
        val bearing = rng.nextDouble(0.0, 360.0)
        val dist = if (half != null) rng.nextDouble(2.0, (half * 0.6).coerceAtLeast(3.5))
        else rng.nextDouble(6.0, 15.0)   // ≤ 20 paces away
        val (plat, plon) = Geo.destination(lat, lon, bearing, dist)
        val p = Portal(plat, plon, floor)
        p.updateFrom(lat, lon)
        portal = p
        return p
    }

    /**
     * Per-creature combat AI (see CritterType.ai / CritterAI). Every foe now
     * HUNTS on its own terms instead of lurking until struck:
     *
     *  - It wakes (engages) the moment the player enters its awareness
     *    radius (`detectM`), not only after being hit.
     *  - Once awake it moves per archetype: CHARGER/AMBUSHER/BRUTE bear down
     *    to melee range; KITER closes to its stand-off then holds and pelts,
     *    backing off if crowded.
     *  - It loses you past a LEASH (≈1.9× awareness) so a steady walk still
     *    shakes ordinary foes — WARDENS (bosses) never give up.
     *  - Asleep, it drifts gently in place so it never reads as a statue.
     *
     * Movement only — the actual attack (cadence/damage) is resolved by the
     * engine, gated on the same `engagedMs`.
     */
    fun updateCritters(lat: Double, lon: Double, dtSec: Double, playerGhost: Boolean = false) {
        val nowMs = System.currentTimeMillis()
        for (c in critters) {
            c.updateFrom(lat, lon)
            val ai = c.type.ai
            val detect = ai.detectM * (if (c.isBoss) 1.5 else 1.0)
            val leash = detect * 1.9

            // AGGRO: wake when the player enters awareness (ghosts are unseen).
            if (c.engagedMs == 0L && !playerGhost && c.distanceM <= detect) {
                c.engagedMs = nowMs
            }
            // DE-AGGRO: ordinary foes lose you past the leash; bosses never do.
            if (c.engagedMs > 0L && !c.isBoss && (playerGhost || c.distanceM > leash)) {
                c.engagedMs = 0L
            }

            if (c.engagedMs > 0L) {
                val step = ai.speedMps * dtSec
                if (ai.ranged) {
                    // KITER — hold the stand-off: close if far, retreat if crowded.
                    when {
                        c.distanceM > ai.standoffM + 1.5 -> {
                            val toPlayer = Geo.bearingDeg(c.lat, c.lon, lat, lon)
                            val (nlat, nlon) = Geo.destination(c.lat, c.lon, toPlayer, step)
                            c.lat = nlat; c.lon = nlon
                        }
                        c.distanceM < ai.standoffM - 2.0 -> {
                            val away = Geo.bearingDeg(lat, lon, c.lat, c.lon)
                            val (nlat, nlon) = Geo.destination(c.lat, c.lon, away, step * 0.8)
                            c.lat = nlat; c.lon = nlon
                        }
                        else -> Unit   // in the pocket — hold and pelt
                    }
                } else {
                    // CHARGER / AMBUSHER / BRUTE — bear down to melee range.
                    if (c.distanceM > ai.standoffM + 0.6) {
                        val toPlayer = Geo.bearingDeg(c.lat, c.lon, lat, lon)
                        val (nlat, nlon) = Geo.destination(c.lat, c.lon, toPlayer, step)
                        c.lat = nlat; c.lon = nlon
                    }
                }
                c.updateFrom(lat, lon)
            } else {
                // ASLEEP: a slow idle drift so it isn't a frozen statue.
                c.lurkHeading = (c.lurkHeading + rng.nextDouble(-15.0, 15.0) + 360.0) % 360.0
                val drift = (if (c.isBoss) 0.2 else 0.4) * dtSec
                val (nlat, nlon) = Geo.destination(c.lat, c.lon, c.lurkHeading, drift)
                c.lat = nlat; c.lon = nlon
            }
        }
        // Cull foes that have lost interest and wandered far, or aged out.
        // WARDENS never despawn — they must be slain to finish the leg.
        critters.removeAll {
            !it.isBoss && ((it.engagedMs == 0L && it.distanceM > 95.0) || nowMs - it.bornMs > 300_000L)
        }
        herbs.forEach { it.updateFrom(lat, lon) }
        herbs.removeAll { it.distanceM > 70.0 }
        portal?.let {
            it.updateFrom(lat, lon)
            // the veil never lingers: 100 s, then gone
            if (nowMs - it.bornMs > 100_000L || it.distanceM > 80.0) portal = null
        }
    }

    /** Summon the chapter's Shardkeeper for a shard quest. */
    fun spawnBoss(lat: Double, lon: Double, type: MonsterType, floor: Int): Monster {
        val half = indoorHalfM
        val bearing = rng.nextDouble(0.0, 360.0)
        val dist = if (half != null) rng.nextDouble(half * 0.7, half * 1.1)
        else rng.nextDouble(120.0, 260.0)
        val (mlat, mlon) = Geo.destination(lat, lon, bearing, dist)
        val boss = Monster(nextId++, type, mlat, mlon, floor, isBoss = true)
        boss.updateFrom(lat, lon)
        monsters.add(boss)
        return boss
    }

    /** A satchel of dropped coins becomes a world item anyone can find. */
    fun dropSatchel(lat: Double, lon: Double, floor: Int, coins: Long) {
        if (coins <= 0) return
        val item = WorldItem(
            nextId++, ItemType.CHEST, Rarity.RARE,
            shiny = false, duelRelic = false,
            lat = lat, lon = lon, floor = floor, droppedCoins = coins
        )
        items.add(item)
    }

    /**
     * Maintain the ambient wireframe SKYLINE — purely decorative scenery so
     * the streets don't feel empty. Kept SPARSE (≈8 in view-range) and never
     * spawned on top of each other, so it frames the world without crowding
     * the play layer. Outdoor only (the retired Hearth had its own walls).
     */
    private fun maintainBuildings(lat: Double, lon: Double, floor: Int) {
        if (indoor) { buildings.clear(); return }
        buildings.forEach { it.updateFrom(lat, lon) }
        buildings.removeAll { it.distanceM > 150.0 }
        val target = 8
        var guard = 0
        while (buildings.size < target && guard++ < 14) {
            val bearing = rng.nextDouble(0.0, 360.0)
            val dist = rng.nextDouble(28.0, 120.0)
            val (blat, blon) = Geo.destination(lat, lon, bearing, dist)
            val b = Building(
                blat, blon, floor,
                widthM = rng.nextDouble(8.0, 20.0),
                heightM = rng.nextDouble(9.0, 30.0),
                style = rng.nextDouble()
            ).also { it.updateFrom(lat, lon) }
            // never stack two in roughly the same line of sight + range
            val tooClose = buildings.any {
                kotlin.math.abs(Geo.wrapDeg(it.bearingDeg - b.bearingDeg)) < 10.0 &&
                    kotlin.math.abs(it.distanceM - b.distanceM) < 18.0
            }
            if (!tooClose) buildings.add(b)
        }
    }

    /** Top up the world around the player. Call every few seconds. */
    fun tick(
        lat: Double, lon: Double, tier: Int, night: Boolean,
        duelActive: Boolean, sessionSteps: Long, elapsedSec: Long, floor: Int
    ) {
        val half = indoorHalfM
        items.forEach { it.updateFrom(lat, lon) }
        monsters.forEach { it.updateFrom(lat, lon) }
        shrine?.updateFrom(lat, lon)
        traces.forEach { it.updateFrom(lat, lon) }
        npc?.updateFrom(lat, lon)
        lever?.updateFrom(lat, lon)
        sign?.updateFrom(lat, lon)
        val nowMs = System.currentTimeMillis()
        traces.removeAll { nowMs - it.bornMs > TRACE_LIFETIME_MS }
        sign?.let { if (it.distanceM > 200.0) sign = null }   // left far behind
        maintainBuildings(lat, lon, floor)   // refresh the ambient skyline

        // Keeper Finn wanders by every few minutes (engine despawns him
        // once a quest is taken; he lingers ~6 min otherwise).
        val finn = npc
        if (finn != null && nowMs - finn.bornMs > 360_000L) {
            npc = null
            lastNpcGoneMs = nowMs
        }
        // A lever appears rarely — pull it and see.
        if (lever == null && nowMs - lastLeverMs > 420_000L && rng.nextInt(100) < 30) {
            lastLeverMs = nowMs
            val bearing = rng.nextDouble(0.0, 360.0)
            val dist = if (half != null) rng.nextDouble(half * 0.3, half * 0.9)
            else rng.nextDouble(40.0, 130.0)
            val (llat, llon) = Geo.destination(lat, lon, bearing, dist)
            lever = Lever(llat, llon, floor).also { it.updateFrom(lat, lon) }
        }

        // cull what's been left behind — but NEVER the leg's climax relic
        val despawn = half?.let { it * 2.2 + 30.0 } ?: ITEM_DESPAWN_M
        items.removeAll { it.distanceM > despawn && !it.missionGoal }
        // never cull a thief carrying loot — or a Shardkeeper (quest softlock!)
        monsters.removeAll { it.distanceM > despawn * 1.4 && it.stolenCoins == 0L && !it.isBoss }

        // Items are NOT count-restocked here anymore — the engine spawns
        // them by IMPORTANCE as you walk (minor ~10 m, medium ~100 m,
        // high ~300 m). Just cap the world so it can't overflow.
        if (items.size > 14) {
            // trim the farthest ordinary items; protect the climax relic
            items.sortByDescending { if (it.missionGoal) -1.0 else it.distanceM }
            while (items.size > 14 && !items[0].missionGoal) items.removeAt(0)
        }

        // shrine: always exactly one
        if (shrine == null || (shrine != null && indoor && shrine!!.floor != floor && rng.nextInt(100) < 2)) {
            val bearing = rng.nextDouble(0.0, 360.0)
            val dist = if (half != null) {
                rng.nextDouble(half * 0.4, (half * 0.95).coerceAtLeast(half * 0.4 + 1.0))
            } else {
                rng.nextDouble(SHRINE_MIN_M, SHRINE_MAX_M)
            }
            val (slat, slon) = Geo.destination(lat, lon, bearing, dist)
            shrine = Shrine(slat, slon, floor).also { it.updateFrom(lat, lon) }
        }

        // monsters: arrive once the hunt is underway. Indoors the first one
        // shows up sooner (small space, fast loop) but they stay scarce.
        val warmedUp = if (indoor) sessionSteps > 60 || elapsedSec > 100
        else sessionSteps > 150 || elapsedSec > 150
        val maxMonsters = when {
            !warmedUp -> 0
            indoor -> (1 + (if (tier >= 3) 1 else 0)).coerceAtMost(2)
            else -> (1 + (if (tier >= 2) 1 else 0) + (if (night) 1 else 0)).coerceAtMost(3)
        }
        while (monsters.size < maxMonsters) {
            val bearing = rng.nextDouble(0.0, 360.0)
            val dist = if (half != null) {
                rng.nextDouble(half * 0.9, half * 1.2)
            } else {
                rng.nextDouble(MONSTER_SPAWN_MIN_M, MONSTER_SPAWN_MAX_M)
            }
            val (mlat, mlon) = Geo.destination(lat, lon, bearing, dist)
            val type = MonsterType.entries[rng.nextInt(MonsterType.entries.size)]
            val m = Monster(nextId++, type, mlat, mlon, floor)
            m.updateFrom(lat, lon)
            monsters.add(m)
        }
        // never despawn a thief carrying loot, a Shardkeeper, or anything
        // actively hunting in front of the player — trim only far lurkers.
        while (monsters.count { it.stolenCoins == 0L && !it.isBoss } > maxMonsters) {
            val victim = monsters
                .filter { it.stolenCoins == 0L && !it.isBoss && it.state == MonsterState.LURK }
                .maxByOrNull { it.distanceM }
                ?: break
            monsters.remove(victim)
        }
    }

    /** Move the shrine after banking. */
    fun relocateShrine(lat: Double, lon: Double, floor: Int) {
        val half = indoorHalfM
        val bearing = rng.nextDouble(0.0, 360.0)
        val dist = if (half != null) {
            rng.nextDouble(half * 0.5, half * 1.05)
        } else {
            rng.nextDouble(140.0, 300.0)
        }
        val (slat, slon) = Geo.destination(lat, lon, bearing, dist)
        shrine = Shrine(slat, slon, floor).also { it.updateFrom(lat, lon) }
    }

    /** Spawn Keeper Finn nearby ("a friend hails you!"). */
    fun maybeSpawnNpc(lat: Double, lon: Double, floor: Int): Boolean {
        val nowMs = System.currentTimeMillis()
        if (npc != null) return false
        if (nowMs - lastNpcGoneMs < 150_000L) return false
        val half = indoorHalfM
        val bearing = rng.nextDouble(0.0, 360.0)
        val dist = if (half != null) rng.nextDouble(4.0, (half * 0.8).coerceAtLeast(6.0))
        else rng.nextDouble(40.0, 90.0)
        val (nlat, nlon) = Geo.destination(lat, lon, bearing, dist)
        npc = Npc(nlat, nlon, floor, nowMs).also { it.updateFrom(lat, lon) }
        return true
    }

    fun dismissNpc() {
        npc = null
        lastNpcGoneMs = System.currentTimeMillis()
    }

    /**
     * Advance monster positions. Returns a monster that has STRUCK (reached
     * the player) or null. Monsters on another floor never engage; neither
     * do they bother a GHOST (the Gloom has no taste for spirits).
     */
    fun updateMonsters(lat: Double, lon: Double, dtSec: Double, tier: Int, night: Boolean, playerFloor: Int, playerGhost: Boolean = false, playerBoots: Boolean = false): Monster? {
        var striker: Monster? = null
        val half = indoorHalfM
        for (m in monsters) {
            m.updateFrom(lat, lon)
            val sameFloor = (!indoor || m.floor == playerFloor) && !playerGhost
            val baseSpeed = when {
                indoor -> 0.55
                night -> 1.45
                else -> 1.15
            }
            var speed = baseSpeed + (if (indoor) 0.05 else 0.12) * tier
            if (playerBoots) speed *= 0.85   // Striding Boots: they lag behind
            when (m.state) {
                MonsterState.LURK -> {
                    if (sameFloor && m.distanceM < huntRange()) {
                        m.state = MonsterState.HUNT
                    } else {
                        m.lurkHeading = (m.lurkHeading + rng.nextDouble(-20.0, 20.0) + 360.0) % 360.0
                        val (nlat, nlon) = Geo.destination(m.lat, m.lon, m.lurkHeading, speed * 0.5 * dtSec)
                        m.lat = nlat; m.lon = nlon
                    }
                }
                MonsterState.HUNT -> {
                    if (!sameFloor || m.distanceM > escapeDist()) {
                        m.state = MonsterState.LURK // they escaped!
                    } else {
                        val toPlayer = Geo.bearingDeg(m.lat, m.lon, lat, lon)
                        val (nlat, nlon) = Geo.destination(m.lat, m.lon, toPlayer, speed * dtSec)
                        m.lat = nlat; m.lon = nlon
                        if (m.distanceM < strikeRadius()) {
                            m.state = MonsterState.STRIKE
                            striker = m
                        }
                    }
                }
                MonsterState.FLEE -> {
                    // Run from the player, but SLOWER than a walking human
                    // (~1.4 m/s) — a determined chase always wins. Loiters
                    // once it feels safe; the loot stays recoverable until
                    // the deadline.
                    val fleeStop = half?.let { it * 1.05 } ?: 45.0
                    var moved = 0.0
                    if (m.distanceM < fleeStop) {
                        val away = Geo.bearingDeg(lat, lon, m.lat, m.lon) // player -> monster = away
                        val fleeSpeed = if (indoor) 0.8 else 1.0
                        moved = fleeSpeed * dtSec
                        val (nlat, nlon) = Geo.destination(m.lat, m.lon, away, moved)
                        m.lat = nlat; m.lon = nlon
                        m.lurkHeading = away
                    } else {
                        m.lurkHeading = (m.lurkHeading + rng.nextDouble(-25.0, 25.0) + 360.0) % 360.0
                        moved = 0.25 * dtSec
                        val (nlat, nlon) = Geo.destination(m.lat, m.lon, m.lurkHeading, moved)
                        m.lat = nlat; m.lon = nlon
                    }
                    // ---- the evidence trail ----
                    m.traceAccumM += moved
                    val traceInterval = if (indoor) TRACE_INTERVAL_INDOOR_M else TRACE_INTERVAL_OUTDOOR_M
                    if (m.traceAccumM >= traceInterval) {
                        m.traceAccumM = 0.0
                        val t = Trace(m.lat, m.lon, System.currentTimeMillis(), m.floor, m.lurkHeading)
                        t.updateFrom(lat, lon)
                        traces.add(t)
                        if (traces.size > 40) traces.removeAt(0)
                        // Clumsy thief: sometimes fumbles part of the loot
                        // right onto the trail.
                        if (m.stolenCoins >= 5 && rng.nextInt(100) < TRACE_DROP_CHANCE_PCT) {
                            val dropped = (m.stolenCoins * 40L) / 100L
                            m.stolenCoins -= dropped
                            dropSatchel(m.lat, m.lon, m.floor, dropped)
                        }
                    }
                }
                MonsterState.STRIKE -> Unit
            }
        }
        return striker
    }
}
