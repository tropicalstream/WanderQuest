package com.tropicalstream.wanderquest.game

import android.os.SystemClock
import com.tropicalstream.wanderquest.platform.FloorTracker
import com.tropicalstream.wanderquest.platform.GeoFix
import com.tropicalstream.wanderquest.platform.Geo
import com.tropicalstream.wanderquest.platform.HeadTracker
import com.tropicalstream.wanderquest.platform.Sfx
import com.tropicalstream.wanderquest.platform.StepTracker
import java.util.Calendar
import kotlin.math.abs
import kotlin.random.Random

class Popup(val text: String, val color: Int, val startMs: Long, val big: Boolean = false)

/**
 * The conductor. Owns game state, the reward loop, the fear loop, duels,
 * menus — everything except pixels (GameView) and Android plumbing
 * (MainActivity).
 */
class GameEngine(
    val stats: StatsStore,
    val sfx: Sfx,
    val head: HeadTracker,
    val steps: StepTracker,
    val floors: FloorTracker
) {

    companion object {
        const val AIM_CONE_DEG = 22.0
        // You must be roughly FACING a foe to land a blow. Swing while turned
        // further than this away and it's a clean miss.
        const val STRIKE_FACING_DEG = 50.0
        // Gaze-walk: an object within this cone of your view closes in as you
        // walk toward it, even unmarked (see gazeApproach).
        const val GAZE_CONE_DEG = 24.0
        const val MILE_M = 1609.34
        const val GPS_STALE_MS = 15_000L
        const val SCARE_DURATION_MS = 2400L
        const val GOLDEN_DURATION_MS = 8L * 60_000L
        const val NAME_CYCLE = "WANDERER,FOXGLOVE,STARLING,MOSSBOOT,EMBER,PIXEL,JUNIPER,COMET,BRAMBLE,WILLOW"
        val DUEL_WORDS_A = listOf("FOX", "MOON", "STAR", "EMBER", "FROST", "THORN", "RIVER", "SHADOW")
        val DUEL_WORDS_B = listOf("FIRE", "DANCE", "HUNT", "CROWN", "SONG", "TRAIL", "STONE", "GLOW")
    }

    val spawner = SpawnManager()
    private val rng = Random(System.nanoTime())
    val battle = Battle(stats, rng)

    // ---- live state the renderer reads ----
    @Volatile var screen = Screen.TITLE
    var lastFix: GeoFix? = null
    var gpsOk = false
    var night = false
    var goldenActive = false
    var goldenEndsMs = 0L
    var vignetteAlpha = 0f
    var scareMonster: MonsterType? = null
    var scareStartMs = 0L
    var scareStolen = 0L
    val popups = ArrayList<Popup>()
    var targetItem: WorldItem? = null      // aimed + in reach
    var targetMonster: Monster? = null     // fleeing thief, in reach — grab it!
    var targetCritter: Critter? = null     // field foe — RED crosshair, strike!
    var targetHerb: Herb? = null           // ingredient underfoot
    var targetPlace: Place? = null         // pool/guild/beacon in reach
    var targetNpc: Npc? = null             // Keeper Finn in reach
    var targetLever: Lever? = null         // pull it!
    var targetSign: Signpost? = null       // read it
    var targetPortal: Portal? = null       // step through the veil...
    var shrineTargeted = false
    var luckyUntilMs = 0L                  // Lucky Charm: double loot window
    var nearestItemDist = Double.MAX_VALUE
    var sessionStartMs = SystemClock.uptimeMillis()
    var leaderboardUrl = ""
    @Volatile var inputDebug = ""   // last raw input event (title screen + Sage's Overlay)
    var waveFxMs = 0L               // air-flow streaks on every gesture
    // calibration state (gyro head-shake tuning)
    val calibPeaks = ArrayList<Float>()
    var calibWaves = 0
    var suggestedThreshold = 0f
    private var calibInAttempt = false
    private var calibAttemptPeak = 0f

    // Hearth (indoor) world: starts as a 50x50 ft hall, grows as the
    // player roams beyond it. Stairs via FloorTracker.
    var hearthHalfM = SpawnManager.INDOOR_START_HALF_M
    private var hearthOriginLat = Double.NaN
    private var hearthOriginLon = Double.NaN
    val currentFloor: Int get() = if (stats.simMode) floors.floor else 0

    // The Sundered Crown
    var activeQuest: Quest? = null
    var dialogQuest: Quest? = null          // the offer on the table
    var dialogGreeting = ""
    var guildIndex = 0
    var introPage = 0
    var ghostUntilMs = 0L
    private var poolCooldownUntilMs = 0L
    private var lastNpcRollMs = 0L
    private var lastSignRollMs = 0L
    private var lastManaStepBucket = 0L
    private var lastPlacePopupMs = 0L
    private var worldSeeded = false
    private var weaponNoticeMs = 0L
    var weaponNotice = ""

    // menus
    var menuIndex = 0
    var settingsIndex = 0
    var duelIndex = 0
    var grimoireIndex = 0
    // duel setup wheels + result entry
    var duelWordA = 0
    var duelWordB = 0
    var duelEnded = false
    var duelEntryMode = false               // entering rival result code
    val duelEntryChars = CharArray(7) { '0' }
    var duelEntryCursor = 0
    var duelVerdict = ""                    // last verify outcome message

    private var lastUpdateMs = 0L
    private var lastSpawnTickMs = 0L
    private var lastGoldenRollMs = 0L
    private var lastSaveMs = 0L
    private var lastNightCheckMs = 0L
    private var lastHearthPopupMs = 0L
    private var prevFix: GeoFix? = null
    private var stepsAtDuelTick = 0L
    private var firstCollectChecked = false

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    fun startSession() {
        sessionStartMs = SystemClock.uptimeMillis()
        steps.resetSession()
        firstCollectChecked = false
        head.axisMode = stats.axisMode
        head.yawOffsetDeg = stats.yawOffsetDeg
        sfx.masterVolume = stats.volumePercent / 100f
        stats.simMode = false   // one world: outdoor GPS only (indoor retired)
        // Sync theme unlocks with the current level (covers old saves).
        for (theme in GameTheme.entries) {
            if (stats.level >= theme.unlockLevel) stats.unlockedThemes.add(theme)
        }
        activeQuest = if (stats.activeQuestJson.isNotEmpty()) Quest.fromJson(stats.activeQuestJson) else null
        if (stats.ghost) ghostUntilMs = SystemClock.uptimeMillis() + 240_000L
        if (!stats.introSeen) {
            screen = Screen.INTRO
            introPage = 0
        }
        // wiring the battle system
        battle.onSfx = { name, vol -> sfx.play(name, vol) }
        battle.onPlayerDeath = { onPlayerDeath() }
        battle.onVictoryDone = { m -> onBattleVictory(m) }
        battle.onFled = { m -> onBattleFled(m) }
    }

    // ------------------------------------------------------------------
    //  Battle outcomes
    // ------------------------------------------------------------------

    private fun onPlayerDeath() {
        screen = Screen.ROAMING
        sfx.setBattleDrums(false)
        // The Phoenix Feather acts on its own
        val feathers = stats.specialItems[SpecialItem.PHOENIX_FEATHER.name] ?: 0L
        if (feathers > 0) {
            stats.specialItems[SpecialItem.PHOENIX_FEATHER.name] = feathers - 1
            stats.hearts = stats.maxHearts
            vignetteAlpha = 0.6f
            // the blaze hurls your attacker back into the dark
            spawner.monsters.lastOrNull { it.state == MonsterState.STRIKE }?.let { m ->
                val fix = lastFix
                if (fix != null) {
                    val away = Geo.bearingDeg(fix.lat, fix.lon, m.lat, m.lon)
                    val dist = if (spawner.indoor) hearthHalfM * 0.9 else 90.0
                    val (nlat, nlon) = Geo.destination(m.lat, m.lon, away, dist)
                    m.lat = nlat
                    m.lon = nlon
                }
                m.state = MonsterState.LURK
                m.growled = false
            }
            popup("THE PHOENIX FEATHER BLAZES — you rise!", 0xFFFFD75E.toInt(), big = true)
            sfx.play("starfall", 0.7f)
            sfx.play("revive_chime")
            persist()
            return
        }
        stats.ghost = true
        ghostUntilMs = SystemClock.uptimeMillis() + 240_000L
        val steal = (stats.satchelCoins * 25L) / 100L
        stats.satchelCoins -= steal
        stats.scaresSuffered += 1
        // your defeater becomes a thief and flees with the cut...
        val killer = spawner.monsters.lastOrNull { it.state == MonsterState.STRIKE }
        if (killer != null) {
            if (steal > 0) {
                killer.stolenCoins = steal
                killer.state = MonsterState.FLEE
                killer.fleeDeadlineMs = SystemClock.uptimeMillis() + SpawnManager.FLEE_DEADLINE_MS
            } else {
                spawner.monsters.remove(killer)
            }
        } else if (steal > 0) {
            // ...but a critter-nip death has no thief: the coins spill where
            // you fell, recoverable when you return in the flesh.
            val fix = lastFix
            if (fix != null) {
                spawner.dropSatchel(fix.lat, fix.lon, currentFloor, steal)
                popup("Your satchel spills where you fell...", 0xFFFF9A3D.toInt())
            }
        }
        vignetteAlpha = 1f
        sfx.play("ghost_moan")
        popup("You fall... and rise as a GHOST", 0xFF9AD1FF.toInt(), big = true)
        popup("Find a Healing Pool to return", 0xFF9AD1FF.toInt())
        persist()
    }

    private fun onBattleVictory(m: Monster) {
        screen = Screen.ROAMING
        sfx.setBattleDrums(false)
        spawner.monsters.remove(m)
        stats.battlesWon += 1
        stats.bestiary[m.type.name] = (stats.bestiary[m.type.name] ?: 0L) + 1L
        var coins = (30L + rng.nextInt(40)) * stats.chapter * (if (m.isBoss) 2 else 1)
        if (goldenActive || luckyActive()) coins *= 2
        stats.satchelCoins += coins
        gainXp(if (m.isBoss) 120L else 45L)
        if (duelActive()) stats.duelScore += coins
        var drops = "+$coins coins"
        if (rng.nextInt(100) < 25) {
            stats.keys += 1
            drops += " · Wander Key!"
        }
        if (m.isBoss || rng.nextInt(100) < 12) {
            stats.shards += 1
            drops += " · CROWN SHARD!"
            sfx.play("shard_get")
        }
        popup("Victory! $drops", 0xFFFFD75E.toInt(), big = true)
        questProgress(QuestKind.DEFEAT, m.type.name)
        if (m.isBoss) questProgress(QuestKind.SHARD, m.type.name)
        persist()
    }

    private var smokeEscape = false

    private fun onBattleFled(m: Monster) {
        screen = Screen.ROAMING
        sfx.setBattleDrums(false)
        if (smokeEscape) {
            // The smoke does its one job perfectly: nothing stolen, and the
            // bewildered foe is whisked far from your heels.
            spawner.monsters.remove(m)
            return
        }
        val steal = (stats.satchelCoins * 15L) / 100L
        stats.satchelCoins -= steal
        if (steal > 0) {
            m.stolenCoins = steal
            m.state = MonsterState.FLEE
            m.fleeDeadlineMs = SystemClock.uptimeMillis() + SpawnManager.FLEE_DEADLINE_MS
            popup("You escape — it snatched $steal coins!", 0xFFFF9A3D.toInt())
        } else {
            // It's still inside strike range — push it well away or the
            // battle re-triggers on the very next frame.
            val fix = lastFix
            if (fix != null) {
                val away = Geo.bearingDeg(fix.lat, fix.lon, m.lat, m.lon)
                val dist = if (spawner.indoor) hearthHalfM * 0.9 else 80.0
                val (nlat, nlon) = Geo.destination(m.lat, m.lon, away, dist)
                m.lat = nlat
                m.lon = nlon
            }
            m.state = MonsterState.LURK
            m.growled = false
            popup("You slip away into the dusk", 0xFF9AD1FF.toInt())
        }
        sfx.play("escape_relief", 0.7f)
    }

    fun persist() {
        stats.yawOffsetDeg = head.yawOffsetDeg
        stats.axisMode = head.axisMode
        stats.save()
    }

    // ------------------------------------------------------------------
    //  Inputs from platform
    // ------------------------------------------------------------------

    fun onFix(fix: GeoFix) {
        val prev = lastFix
        lastFix = fix
        head.updateDeclination(fix)
        if (stats.autoCompass) head.applyCourseCalibration(fix)
        // First fix of a fresh world: seed a starter set so there's
        // something to find before the first steps.
        if (!worldSeeded) {
            worldSeeded = true
            val tier = Loot.wanderTier(steps.sessionSteps)
            repeat(3) { spawner.maybeSpawnHerb(fix.lat, fix.lon, currentFloor) }
            repeat(2) { spawner.spawnMediumItem(fix.lat, fix.lon, currentFloor, tier, duelActive()) }
            spawner.spawnHighItem(fix.lat, fix.lon, currentFloor, tier, duelActive())
        }
        if (prev != null) {
            val d = Geo.distanceM(prev.lat, prev.lon, fix.lat, fix.lon)
            if (d < 60.0) {
                stats.totalDistanceM += d
                checkMission()
            }
            // DESTINATION LOCK: every meter walked, any direction, counts down
            if (d in 0.4..30.0 && screen == Screen.ROAMING) {
                advanceDestination(d)
                // GAZE-WALK: whatever you're looking at also draws nearer as
                // you walk toward it — no need to mark it first.
                gazeApproach(d)
            }
            // distance-driven world: minor/medium/high spawns by meters roamed
            if (d in 0.2..30.0) rollDistanceSpawns(fix, d)
        }
        prevFix = prev

        // Hearth bounds: track displacement from the session origin and
        // grow the hall when the player wanders past ~80% of it.
        @Suppress("ControlFlowWithEmptyBody")
        if (stats.simMode) {
            if (hearthOriginLat.isNaN()) {
                hearthOriginLat = fix.lat
                hearthOriginLon = fix.lon
            }
            val fromOrigin = Geo.distanceM(hearthOriginLat, hearthOriginLon, fix.lat, fix.lon)
            if (fromOrigin > hearthHalfM * 0.8 && hearthHalfM < 150.0) {
                // Grow in big jumps so a corridor walk doesn't re-trigger
                // every few steps; announce at most every 10 s.
                hearthHalfM = (fromOrigin * 1.6).coerceAtLeast(hearthHalfM + 6.0).coerceAtMost(150.0)
                val nowMs = SystemClock.uptimeMillis()
                if (nowMs - lastHearthPopupMs > 10_000L) {
                    lastHearthPopupMs = nowMs
                    popup("The halls grow wider... (${(hearthHalfM * 2 * 3.28).toInt()} ft)", 0xFF9AD1FF.toInt())
                    sfx.play("radar_ping", 0.6f)
                }
            }
        }
    }

    /**
     * REDIRECTED WALKING for collectibles (items/herbs/portal — never the
     * anchored places or AI creatures):
     *
     *  - GAZE LOCK: a target inside the gaze cone (±25°) while you walk
     *    gets rotated gently around you toward your view center each fix.
     *    GPS noise and compass error can no longer slide it out of the
     *    field of view — keep looking roughly at it and keep walking, and
     *    the distance simply counts down.
     *  - PERIPHERY HOLD: things well off to the side (>60°) translate
     *    along with your own movement (70%), so they don't appear to be
     *    closing in — the world's "elsewhere" politely stays elsewhere.
     */
    // ------------------------------------------------------------------
    //  DESTINATION LOCK — look at an object, tap, and it becomes your
    //  mark: the world turns it to face you (re-anchored straight ahead),
    //  and EVERY meter you walk, in any direction, closes the distance.
    //  Honest, deterministic, kid-proof.
    // ------------------------------------------------------------------

    var destRef: Any? = null
    var destDistM = 0.0             // LIVE counter — only ever counts down
    var destLabel = ""
    var farTarget: Any? = null      // sighted beyond reach — tappable to mark

    /** Mark a sighted object as destination, seeding its live distance. */
    fun markDestination(ref: Any) {
        val info = destInfoOf(ref) ?: return
        destRef = ref
        destDistM = info.second
        destLabel = info.third
    }

    /** Sprite name for a markable object (for the screen-pinned render). */
    fun destSpriteOf(ref: Any?): String? = when (ref) {
        is WorldItem -> if (ref.droppedCoins > 0) "satchel" else ref.type.sprite
        is Herb -> ref.type.sprite
        is Portal -> "portal"
        is Shrine -> "shrine"
        is Lever -> "lever"
        is Npc -> "npc"
        is Signpost -> "sign"
        is Place -> ref.type.sprite
        else -> null
    }

    /** (bearingDeg, distanceM, label) of any markable object, else null. */
    fun destInfoOf(ref: Any?): Triple<Double, Double, String>? = when (ref) {
        is WorldItem -> Triple(ref.bearingDeg, ref.distanceM, if (ref.droppedCoins > 0) "Satchel" else ref.type.label)
        is Herb -> Triple(ref.bearingDeg, ref.distanceM, ref.type.label)
        is Portal -> Triple(ref.bearingDeg, ref.distanceM, "Portal")
        is Shrine -> Triple(ref.bearingDeg, ref.distanceM, "Shrine")
        is Lever -> Triple(ref.bearingDeg, ref.distanceM, "Vault Lever")
        is Npc -> Triple(ref.bearingDeg, ref.distanceM, "Traveler")
        is Signpost -> Triple(ref.bearingDeg, ref.distanceM, "Signpost")
        is Place -> Triple(ref.bearingDeg, ref.distanceM, ref.type.label)
        else -> null
    }

    private fun destAlive(ref: Any): Boolean = when (ref) {
        is WorldItem -> spawner.items.contains(ref)
        is Herb -> spawner.herbs.contains(ref)
        is Portal -> spawner.portal === ref
        is Shrine -> spawner.shrine === ref
        is Lever -> spawner.lever === ref
        is Npc -> spawner.npc === ref
        is Signpost -> spawner.sign === ref
        is Place -> worldPlaces().contains(ref)
        else -> false
    }


    /**
     * THE WHOLE TRUTH OF NAVIGATION, deterministic and unbreakable:
     * every meter you walk — in ANY direction — subtracts from the
     * destination counter. No bearings, no GPS heading, no geometry that
     * could ever make it climb. Walk, and the number goes down.
     */
    private fun advanceDestination(movedM: Double) {
        val dest = destRef ?: return
        if (!destAlive(dest)) {
            destRef = null
            return
        }
        destDistM = (destDistM - movedM).coerceAtLeast(0.0)
    }

    /**
     * GAZE-WALK (the documented "gaze lock", now live for EVERYTHING you can
     * walk to — not just a marked destination). Any collectible/interactable
     * within the gaze cone of your view is gently re-centered and pulled in
     * by the metres you just walked, so "face it and walk" reliably closes
     * the distance despite compass drift. Anchored Places never move; foes
     * are excluded (they steer themselves). Mirrors the destination guarantee.
     */
    private fun gazeRecenter(
        fix: GeoFix, yaw: Double, movedM: Double, bearingDeg: Double, distanceM: Double
    ): Pair<Double, Double>? {
        val rel = Geo.wrapDeg(bearingDeg - yaw)
        if (abs(rel) > GAZE_CONE_DEG) return null
        if (distanceM <= 1.2) return null              // already underfoot
        val newBearing = yaw + rel * 0.4               // ease ~60% toward center
        val newDist = (distanceM - movedM).coerceAtLeast(0.8)
        return Geo.destination(fix.lat, fix.lon, newBearing, newDist)
    }

    private fun gazeApproach(movedM: Double) {
        if (!head.hasData) return
        val fix = lastFix ?: return
        val yaw = head.yawDeg.toDouble()
        val floor = currentFloor
        val indoor = spawner.indoor
        for (item in spawner.items) {
            if (item === destRef || (indoor && item.floor != floor)) continue
            gazeRecenter(fix, yaw, movedM, item.bearingDeg, item.distanceM)?.let { (la, lo) ->
                item.lat = la; item.lon = lo; item.updateFrom(fix.lat, fix.lon)
            }
        }
        for (h in spawner.herbs) {
            if (h === destRef || (indoor && h.floor != floor)) continue
            gazeRecenter(fix, yaw, movedM, h.bearingDeg, h.distanceM)?.let { (la, lo) ->
                h.lat = la; h.lon = lo; h.updateFrom(fix.lat, fix.lon)
            }
        }
        spawner.shrine?.let { s ->
            if (s !== destRef && (!indoor || s.floor == floor)) {
                gazeRecenter(fix, yaw, movedM, s.bearingDeg, s.distanceM)?.let { (la, lo) ->
                    s.lat = la; s.lon = lo; s.updateFrom(fix.lat, fix.lon)
                }
            }
        }
        spawner.portal?.let { p ->
            if (p !== destRef && (!indoor || p.floor == floor)) {
                gazeRecenter(fix, yaw, movedM, p.bearingDeg, p.distanceM)?.let { (la, lo) ->
                    p.lat = la; p.lon = lo; p.updateFrom(fix.lat, fix.lon)
                }
            }
        }
        spawner.npc?.let { n ->
            if (n !== destRef && (!indoor || n.floor == floor)) {
                gazeRecenter(fix, yaw, movedM, n.bearingDeg, n.distanceM)?.let { (la, lo) ->
                    n.lat = la; n.lon = lo; n.updateFrom(fix.lat, fix.lon)
                }
            }
        }
        spawner.lever?.let { lv ->
            if (lv !== destRef && (!indoor || lv.floor == floor)) {
                gazeRecenter(fix, yaw, movedM, lv.bearingDeg, lv.distanceM)?.let { (la, lo) ->
                    lv.lat = la; lv.lon = lo; lv.updateFrom(fix.lat, fix.lon)
                }
            }
        }
        spawner.sign?.let { sg ->
            if (sg !== destRef && (!indoor || sg.floor == floor)) {
                gazeRecenter(fix, yaw, movedM, sg.bearingDeg, sg.distanceM)?.let { (la, lo) ->
                    sg.lat = la; sg.lon = lo; sg.updateFrom(fix.lat, fix.lon)
                }
            }
        }
    }

    /** Reset the Hearth world when (re)entering indoor mode. */
    fun rebaseHearth() {
        hearthOriginLat = Double.NaN
        hearthOriginLon = Double.NaN
        hearthHalfM = SpawnManager.INDOOR_START_HALF_M
        floors.rebase()
        spawner.reset()
        hearthPlaces.clear()
    }

    fun onStep(delta: Long, sessionSteps: Long) {
        stats.totalSteps += delta   // counter sensors batch — credit them all
        val tier = Loot.wanderTier(sessionSteps)
        if (tier > stats.bestWanderTier) stats.bestWanderTier = tier
        if (duelActive()) {
            stepsAtDuelTick += delta
            while (stepsAtDuelTick >= 10) {
                stepsAtDuelTick -= 10
                stats.duelScore += 1
            }
        }
        // walking restores the spirit: +1 mana per 50 paces
        val bucket = sessionSteps / 50
        if (bucket > lastManaStepBucket) {
            lastManaStepBucket = bucket
            if (stats.mana < stats.maxMana) {
                stats.mana += 1
                if (stats.mana == stats.maxMana) sfx.play("mana_fill", 0.5f)
            }
        }
    }

    /**
     * The living world, classified by IMPORTANCE and driven by METERS
     * walked (not steps, not a fixed count):
     *   minor  — herbs/ingredients/plants ......... ~1 per 10 m
     *   medium — trinkets, tools, field foes ...... ~1 per 100 m
     *   high   — vaults, crowns, portals ........... ~1 per 300 m
     * Probability per meter = meters / interval, so it averages out
     * exactly to those spacings however the player moves.
     */
    private fun rollDistanceSpawns(fix: GeoFix, movedM: Double) {
        if (screen != Screen.ROAMING || stats.ghost) return
        val tier = Loot.wanderTier(steps.sessionSteps)
        val f = currentFloor

        if (rng.nextDouble() < movedM / 10.0) {
            spawner.maybeSpawnHerb(fix.lat, fix.lon, f)
        }
        if (rng.nextDouble() < movedM / 100.0) {
            spawner.spawnMediumItem(fix.lat, fix.lon, f, tier, duelActive())
        }
        if (rng.nextDouble() < movedM / 300.0) {
            if (rng.nextInt(100) < 70) {
                spawner.spawnHighItem(fix.lat, fix.lon, f, tier, duelActive())
            } else {
                spawner.maybeSpawnPortal(fix.lat, fix.lon, f)?.let { p ->
                    popup("The veil shimmers ${windName(p.bearingDeg)}... a PORTAL!", 0xFFC98FFF.toInt(), big = true)
                    sfx.play("radar_ping", 0.8f)
                }
            }
        }

        // signposts — worldbuilding ~every 120 m
        if (rng.nextDouble() < movedM / 120.0) {
            spawner.maybeSpawnSign(fix.lat, fix.lon, f, Lore.signpostText(rng))
        }

        // ENEMY ENCOUNTERS — ~1 every 50 m, but the rate (and pack size)
        // climbs the closer you are to medium and especially HIGH-value
        // ground. Treasure is guarded; the realm makes you earn it.
        val danger = dangerLevel()
        val interval = (50.0 / (1.0 + danger)).coerceAtLeast(14.0)
        if (rng.nextDouble() < movedM / interval) {
            spawner.maybeSpawnCritter(fix.lat, fix.lon, f, missionCreature())
            if (danger >= 1.3) spawner.maybeSpawnCritter(fix.lat, fix.lon, f, missionCreature())  // a pair
            if (danger >= 2.4) spawner.maybeSpawnCritter(fix.lat, fix.lon, f, missionCreature())  // a pack
        }
    }

    /**
     * Pick a creature to spawn. HIGH-importance objects are GUARDED by the
     * toughest creatures the leg allows (even +1 tier); medium objects draw
     * the middle of the band; open ground spawns the easy low-tier folk.
     */
    private fun missionCreature(): CritterType {
        val band = CritterType.bandFor(stats.mission)
        // What's the most valuable thing near the player right now?
        var importance = 0   // 0 = open ground, 1 = medium loot, 2 = high prize
        for (item in spawner.items) {
            val high = item.type == ItemType.CHEST || item.type == ItemType.CROWN || item.missionGoal
            if (high && item.distanceM < 120.0) importance = maxOf(importance, 2)
            else if (!high && item.distanceM < 70.0) importance = maxOf(importance, 1)
        }
        for (p in worldPlaces()) if (p.distanceM < 110.0) importance = maxOf(importance, 2)
        val lo: Int; val hi: Int
        when (importance) {
            2 -> { lo = band.last - 1; hi = band.last + 1 }   // guards: top of band (+1)
            1 -> { lo = band.first + 1; hi = band.last }       // mid band
            else -> { lo = band.first; hi = band.first + 1 }   // open: low end
        }
        return CritterType.ofTier(lo.coerceIn(1, 6)..hi.coerceIn(1, 6), rng)
    }

    /**
     * How dangerous the ground feels right now: 0 in the open, rising as
     * valuable things cluster nearby. High items weigh most, then guild/
     * pool landmarks, then medium trinkets.
     */
    private fun dangerLevel(): Double {
        var d = 0.0
        for (item in spawner.items) {
            val high = item.type == ItemType.CHEST || item.type == ItemType.CROWN || item.missionGoal
            when {
                high && item.distanceM < 100.0 -> d += 1.3
                !high && item.distanceM < 55.0 -> d += 0.4
            }
        }
        for (p in worldPlaces()) {
            if (p.distanceM < 90.0) d += 0.6
        }
        return d.coerceAtMost(3.5)
    }

    // ------------------------------------------------------------------
    //  JOURNEY LEGS — an INTERNAL distance clock paces the story. The
    //  player never sees a mile count; instead, story beats and town
    //  chatter fill the walk, and as the leg's pacing nears its end the
    //  world quietly seeds the leg's CLIMAX relic ahead. Claiming it ends
    //  the leg. (Average leg distance: leg 1 ≈ 1 mi, leg 2 ≈ 5 mi, then
    //  +1 mi each — but that's pacing math, never shown.)
    // ------------------------------------------------------------------

    private fun legGoalM(n: Int): Double = (if (n <= 1) 1.0 else (n + 3).toDouble()) * MILE_M
    private fun legWalkedM(): Double = (stats.totalDistanceM - stats.missionStartDist).coerceAtLeast(0.0)
    private fun legFraction(): Double = (legWalkedM() / legGoalM(stats.mission)).coerceIn(0.0, 1.0)

    private var legBeatsShown = 0
    // the TWO leg-wardens: chosen for this leg, revealed as you progress
    private var legWardenTypes: List<CritterType> = emptyList()

    // beats unfold at these fractions of the leg, in order
    private val legBeatThresholds = listOf(0.12, 0.32, 0.52, 0.72)
    private var lastChatterMs = 0L

    /** Wardens needed this leg (2) minus those felled. */
    fun wardensRemaining(): Int = (2 - stats.legBossesDown).coerceAtLeast(0)

    private fun ensureWardenTypes() {
        if (legWardenTypes.size == 2) return
        // two DISTINCT boss myth-creatures from the top of this leg's band
        val band = CritterType.bandFor(stats.mission)
        val top = (band.last - 1)..band.last
        val a = CritterType.ofTier(top, rng)
        var b = CritterType.ofTier(top, rng)
        var guard = 0
        while (b == a && guard++ < 8) b = CritterType.ofTier(top, rng)
        legWardenTypes = listOf(a, b)
    }

    private fun checkMission() {
        val frac = legFraction()
        ensureWardenTypes()

        // The slow storyline: deliver the leg's ordered beats in order.
        val beats = Lore.legBeats(stats.mission)
        while (legBeatsShown < beats.size &&
            legBeatsShown < legBeatThresholds.size &&
            frac >= legBeatThresholds[legBeatsShown]
        ) {
            popup(beats[legBeatsShown], 0xFFC9C2E8.toInt(), big = true)
            sfx.play("ui_select", 0.45f)
            legBeatsShown++
        }

        // Light ambient chatter between beats.
        val nowMs = SystemClock.uptimeMillis()
        if (frac < 0.82 && nowMs - lastChatterMs > 75_000L && rng.nextInt(100) < 35) {
            lastChatterMs = nowMs
            popup(Lore.chatterLine(rng), 0xFF9A93B8.toInt())
        }

        // ---- the TWO leg-WARDENS — reveal them as the leg matures ----
        // warden 1 around the midpoint, warden 2 near the end.
        val shouldBeRevealed = when {
            frac >= 0.62 -> 2
            frac >= 0.32 -> 1
            else -> 0
        }
        val revealable = (shouldBeRevealed - stats.legBossesDown).coerceAtLeast(0)
        val aliveBosses = spawner.critters.count { it.isBoss }
        val pos = lastFix
        if (aliveBosses < revealable && pos != null) {
            // spawn the next warden (index = how many already down + alive)
            val idx = (stats.legBossesDown + aliveBosses).coerceIn(0, 1)
            val boss = spawner.spawnBossCritter(pos.lat, pos.lon, currentFloor, legWardenTypes[idx])
            popup("⚔ A WARDEN stirs — the ${boss.type.label} bars the road ${windName(boss.bearingDeg)}!", 0xFFFF5252.toInt(), big = true)
            sfx.play("monster_growl", 0.9f)
            sfx.play("battle_start", 0.7f)
        }
    }

    /** Called from strikeCritter when a warden falls. */
    private fun onWardenDown(c: Critter) {
        stats.legBossesDown += 1
        if (stats.legBossesDown >= 2) {
            completeLeg()
        } else {
            popup("One warden falls! One more guards the road ahead.", 0xFFFFD75E.toInt(), big = true)
            sfx.play("unlock_fanfare", 0.7f)
            persist()
        }
    }

    /** Both wardens down — the leg ends and the next opens. */
    private fun completeLeg() {
        val n = stats.mission
        val reward = 150L * n
        stats.bankCoins += reward
        stats.shards += 1
        gainXp(150L + 50L * n)
        popup(Lore.legComplete(n), 0xFFFFD75E.toInt(), big = true)
        popup("+$reward coins banked · a Crown Shard is yours", 0xFF8FE08F.toInt())
        sfx.play("unlock_fanfare")
        sfx.play("shard_get")
        stats.mission = n + 1
        stats.missionStartDist = stats.totalDistanceM
        stats.legBossesDown = 0
        legBeatsShown = 0
        legWardenTypes = emptyList()
        popup(Lore.legOpening(stats.mission), 0xFF8FE08F.toInt(), big = true)
        persist()
    }

    fun revive(reason: String) {
        stats.ghost = false
        stats.hearts = stats.maxHearts
        sfx.play("revive_chime")
        popup(reason, 0xFF8FE08F.toInt(), big = true)
        persist()
    }

    // ------------------------------------------------------------------
    //  Main update (≈30 fps, called from the frame loop)
    // ------------------------------------------------------------------

    fun update(nowMs: Long) {
        val dtSec = if (lastUpdateMs == 0L) 0.033 else ((nowMs - lastUpdateMs) / 1000.0).coerceIn(0.0, 0.25)
        lastUpdateMs = nowMs

        val fix = lastFix
        gpsOk = fix != null && (System.currentTimeMillis() - fix.timeMs) < GPS_STALE_MS
        // Calendar is heavyweight — check once a second, not per frame.
        if (nowMs - lastNightCheckMs > 1000L) {
            lastNightCheckMs = nowMs
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            night = hour >= 19 || hour < 6
        }

        if (vignetteAlpha > 0f) vignetteAlpha = (vignetteAlpha - (dtSec * 0.35).toFloat()).coerceAtLeast(0f)
        popups.removeAll { nowMs - it.startMs > (if (it.big) 5000L else 2400L) }

        when (screen) {
            Screen.ROAMING -> if (gpsOk && fix != null) updateWorld(fix, nowMs, dtSec)
            Screen.SCARE -> updateScare(nowMs)
            Screen.BATTLE -> {
                // ONE motion rule: lunge → any movement ducks; else → strikes
                battle.motionThresholdDps = stats.shakeDps
                if (battle.noteMotion(head.angularSpeedDps, nowMs)) waveFxMs = nowMs
                battle.update(nowMs, stats.chapter)
                sfx.setBattleDrums(battle.phase == Battle.Phase.FIGHT)
                if (!battle.active() && screen == Screen.BATTLE) screen = Screen.ROAMING
            }
            Screen.CALIBRATE -> updateCalibration()
            else -> Unit
        }
        if (screen != Screen.BATTLE) sfx.setBattleDrums(false)
        // Fear/greed audio must die the moment we leave the hunt.
        if (screen != Screen.ROAMING || !gpsOk) {
            sfx.setHeartbeat(0)
            sfx.setGeiger(0)
        }
        // ghosts hear nothing but wind — and the haunting fades on its own
        // schedule no matter what screen they're staring at
        if (stats.ghost) {
            if (screen == Screen.ROAMING) {
                sfx.setHeartbeat(0)
                sfx.setGeiger(0)
            }
            if (nowMs > ghostUntilMs) revive("The haunting fades... you breathe again")
        }
        sfx.retryAmbientIfPending()

        // golden hour expiry
        if (goldenActive && nowMs > goldenEndsMs) {
            goldenActive = false
            popup("The Gilded Hour fades...", 0xFFE8C66A.toInt())
        }

        // duel expiry (duelEndsAtMs stays put — duelAwaitingResult() keys off it)
        if (!duelEnded && stats.duelCode.isNotEmpty() && stats.duelEndsAtMs in 1..System.currentTimeMillis()) {
            duelEnded = true
            popup("DUEL COMPLETE! Check the Duel page", 0xFFFF9A3D.toInt(), big = true)
            sfx.play("unlock_fanfare")
        }

        // autosave
        if (nowMs - lastSaveMs > 20_000L) {
            lastSaveMs = nowMs
            persist()
        }
    }

    private fun updateWorld(fix: GeoFix, nowMs: Long, dtSec: Double) {
        val tier = Loot.wanderTier(steps.sessionSteps)
        val elapsedSec = (nowMs - sessionStartMs) / 1000L
        val floor = currentFloor
        spawner.indoorHalfM = if (stats.simMode) hearthHalfM else null
        val indoor = spawner.indoor

        // Fresh geometry EVERY frame (aiming + collect radius depend on it;
        // a walker covers ~5 m between 4 s spawn ticks). Spawn/cull stays gated.
        for (item in spawner.items) item.updateFrom(fix.lat, fix.lon)
        spawner.shrine?.updateFrom(fix.lat, fix.lon)
        for (t in spawner.traces) t.updateFrom(fix.lat, fix.lon)

        if (nowMs - lastSpawnTickMs > 4000L) {
            lastSpawnTickMs = nowMs
            spawner.tick(fix.lat, fix.lon, tier, night, duelActive(), steps.sessionSteps, elapsedSec, floor)
            ensurePlaces(fix)
        }
        for (p in worldPlaces()) p.updateFrom(fix.lat, fix.lon)

        // Folk of the road — Keeper Finn (quests) when you have none, else
        // a generic villager with a kind word. They come by often now.
        if (nowMs - lastNpcRollMs > 35_000L) {
            lastNpcRollMs = nowMs
            val wantFinn = activeQuest == null && rng.nextInt(100) < 45
            if (rng.nextInt(100) < 55 && spawner.maybeSpawnNpc(fix.lat, fix.lon, floor)) {
                spawner.npcIsVillager = !wantFinn
                val who = if (wantFinn) "A friendly lantern bobs" else "A villager waves"
                popup("$who ${windName(spawner.npc?.bearingDeg ?: 0.0)}...", 0xFF8FE08F.toInt())
                sfx.play("ui_select", 0.6f)
            }
        }
        // Roadside signposts — worldbuilding (~every 120 m walked is rolled
        // in rollDistanceSpawns; this is the gentle time-based backup).
        if (spawner.sign == null && nowMs - lastSignRollMs > 50_000L) {
            lastSignRollMs = nowMs
            if (rng.nextInt(100) < 40) {
                spawner.maybeSpawnSign(fix.lat, fix.lon, floor, Lore.signpostText(rng))
            }
        }

        val striker = spawner.updateMonsters(
            fix.lat, fix.lon, dtSec, tier, night, floor,
            playerGhost = stats.ghost, playerBoots = stats.bootsOwned
        )
        spawner.updateCritters(fix.lat, fix.lon, dtSec, playerGhost = stats.ghost)

        // Foes that have WOKEN (engagedMs set by their AI when you enter
        // their awareness — see SpawnManager.updateCritters) now press the
        // attack on their own archetype's terms: MELEE types bite at close
        // range; KITERS pelt you from their stand-off (slimes spit, imps hurl
        // embers, skeletons fling bones). Each strikes on its own cadence
        // after a short wind-up. Armor and the duck reflex still help; a hit
        // costs a heart. Disengagement/leashing is handled by the AI itself.
        if (!stats.ghost) {
            for (c in spawner.critters) {
                if (c.engagedMs == 0L) continue
                val ai = c.type.ai
                // attack reach: ranged fire from the stand-off; melee up close.
                val atkRange = if (ai.ranged) ai.standoffM + 4.0 else 2.8
                if (c.distanceM > atkRange) continue
                if (nowMs - c.engagedMs < ai.graceMs) continue     // winding up
                if (nowMs - c.lastNipMs < ai.cadenceMs) continue   // on cooldown
                c.lastNipMs = nowMs
                val isRanged = ai.ranged
                val verb = if (isRanged) c.type.rangedVerb else "claws"
                if (isRanged) sfx.play("wand_zap", 0.5f)
                val armor = Gear.armorOf(stats)
                if (armor.absorbPct > 0 && rng.nextInt(100) < armor.absorbPct) {
                    popup("${c.type.label} $verb — ${armor.label} holds!", 0xFF9AD1FF.toInt())
                    sfx.play("hit_impact", 0.5f)
                } else {
                    stats.hearts -= 1
                    vignetteAlpha = 0.5f
                    sfx.play(if (isRanged) "monster_yelp" else "critter_nip", 0.8f)
                    popup("${c.type.label} $verb you! -1 ♥", 0xFFFF5252.toInt())
                    if (stats.hearts <= 0) {
                        stats.hearts = 0
                        onPlayerDeath()
                        return
                    }
                }
            }
        }

        // lucky charm expiry
        if (luckyUntilMs in 1..nowMs) {
            luckyUntilMs = 0L
            popup("The Lucky Charm dims...", 0xFF9A93B8.toInt())
        }

        // ---- monster audio: growl on approach, heartbeat when hunted ----
        val growlDist = if (indoor) 10.0 else 85.0
        val hbStart = if (indoor) 14.0 else 70.0
        val hbFloor = if (indoor) 2.0 else 12.0
        var nearestHunter = Double.MAX_VALUE
        val expired = ArrayList<Monster>()
        for (m in spawner.monsters) {
            val sameFloor = !indoor || m.floor == floor
            if (sameFloor && m.distanceM < growlDist && !m.growled) {
                m.growled = true
                sfx.play("monster_growl", 0.8f)
            }
            if (m.state == MonsterState.HUNT) {
                nearestHunter = minOf(nearestHunter, m.distanceM)
            }
            // a fleeing thief keeps a faint heartbeat going — the chase pulse
            if (m.state == MonsterState.FLEE && m.stolenCoins > 0) {
                if (nowMs > m.fleeDeadlineMs) expired.add(m)
            }
            // escape detection: HUNT -> LURK transition happens in spawner;
            // detect by distance band + growled flag reset
            if (m.state == MonsterState.LURK && m.growled && m.distanceM > spawner.escapeDist()) {
                m.growled = false
                stats.monstersEscaped += 1
                stats.bestiary[m.type.name] = (stats.bestiary[m.type.name] ?: 0L) + 1L
                gainXp(25)
                popup("Escaped the ${m.type.label}! +25 XP", 0xFF8FE08F.toInt())
                sfx.play("escape_relief")
            }
        }
        for (m in expired) {
            spawner.monsters.remove(m)
            // Even an escaped thief is careless: usually it abandons the
            // satchel where it vanished — the trail still pays off.
            if (rng.nextInt(100) < 60) {
                spawner.dropSatchel(m.lat, m.lon, m.floor, m.stolenCoins)
                popup("The ${m.type.label} vanished — but dropped your satchel!", 0xFFFF9A3D.toInt(), big = true)
                sfx.play("radar_ping", 0.7f)
            } else {
                popup("The ${m.type.label} escaped with ${m.stolenCoins} coins...", 0xFFFF6B6B.toInt())
                sfx.play("satchel_loss", 0.8f)
            }
        }
        sfx.setHeartbeat(
            when {
                nearestHunter > hbStart -> 0L
                else -> (350 + ((nearestHunter - hbFloor).coerceAtLeast(0.0) / (hbStart - hbFloor) * 850)).toLong()
            }
        )

        // ---- the greed channel: geiger ticks toward the nearest trinket ----
        val geigerStart = if (indoor) 12.0 else 50.0
        nearestItemDist = spawner.items
            .filter { !indoor || it.floor == floor }
            .minOfOrNull { it.distanceM } ?: Double.MAX_VALUE
        sfx.setGeiger(
            when {
                nearestItemDist > geigerStart -> 0L
                else -> (140 + (nearestItemDist / geigerStart * 1100)).toLong()
            }
        )

        // ---- aiming: trinkets, herbs, foes, friends, fixtures ----
        targetItem = null
        targetMonster = null
        targetCritter = null
        targetHerb = null
        targetPlace = null
        targetNpc = null
        targetLever = null
        targetPortal = null
        targetSign = null
        shrineTargeted = false
        if (head.hasData && !stats.ghost) {
            val yaw = head.yawDeg.toDouble()
            val reach = spawner.collectRadius()
            val pointBlank = if (indoor) 1.5 else 6.0
            var best: WorldItem? = null
            var bestErr = AIM_CONE_DEG
            for (item in spawner.items) {
                if (indoor && item.floor != floor) continue
                if (item.distanceM > reach) continue
                val err = abs(Geo.wrapDeg(item.bearingDeg - yaw))
                val effective = if (item.distanceM < pointBlank) 0.0 else err
                if (effective <= bestErr) {
                    bestErr = effective
                    best = item
                }
            }
            targetItem = best
            // herbs underfoot (same priority tier as items)
            if (best == null) {
                for (h in spawner.herbs) {
                    if (indoor && h.floor != floor) continue
                    if (h.distanceM > reach) continue
                    val err = abs(Geo.wrapDeg(h.bearingDeg - yaw))
                    if (err <= AIM_CONE_DEG || h.distanceM < pointBlank) {
                        targetHerb = h
                        break
                    }
                }
            }
            // field foes: the RED crosshair — strikeable from RANGE (aim at them)
            val combat = spawner.combatRange()
            if (best == null && targetHerb == null) {
                for (c in spawner.critters) {
                    if (indoor && c.floor != floor) continue
                    if (c.distanceM > combat) continue
                    val err = abs(Geo.wrapDeg(c.bearingDeg - yaw))
                    if (err <= AIM_CONE_DEG || c.distanceM < pointBlank) {
                        targetCritter = c
                        break
                    }
                }
            }
            if (best == null && targetHerb == null && targetCritter == null) {
                for (m in spawner.monsters) {
                    if (m.stolenCoins <= 0L) continue
                    if (indoor && m.floor != floor) continue
                    if (m.distanceM > combat) continue
                    val err = abs(Geo.wrapDeg(m.bearingDeg - yaw))
                    if (err <= AIM_CONE_DEG || m.distanceM < pointBlank) {
                        targetMonster = m
                        break
                    }
                }
            }
            val noTarget = best == null && targetHerb == null && targetCritter == null && targetMonster == null
            val s = spawner.shrine
            if (noTarget && s != null &&
                (!indoor || s.floor == floor) && s.distanceM <= reach
            ) {
                val err = abs(Geo.wrapDeg(s.bearingDeg - yaw))
                shrineTargeted = err <= AIM_CONE_DEG || s.distanceM < pointBlank
            }
            // the portal (same reach as everything else; it spawns close)
            val pt = spawner.portal
            if (noTarget && !shrineTargeted && pt != null &&
                (!indoor || pt.floor == floor) && pt.distanceM <= reach
            ) {
                val err = abs(Geo.wrapDeg(pt.bearingDeg - yaw))
                if (err <= AIM_CONE_DEG || pt.distanceM < pointBlank) targetPortal = pt
            }
            // friendly faces and fixtures
            if (noTarget && !shrineTargeted && targetPortal == null) {
                val n = spawner.npc
                if (n != null && (!indoor || n.floor == floor) && n.distanceM <= reach) {
                    val err = abs(Geo.wrapDeg(n.bearingDeg - yaw))
                    if (err <= AIM_CONE_DEG || n.distanceM < pointBlank) targetNpc = n
                }
                val lv = spawner.lever
                if (targetNpc == null && lv != null && (!indoor || lv.floor == floor) && lv.distanceM <= reach) {
                    val err = abs(Geo.wrapDeg(lv.bearingDeg - yaw))
                    if (err <= AIM_CONE_DEG || lv.distanceM < pointBlank) targetLever = lv
                }
                val sg = spawner.sign
                if (targetNpc == null && targetLever == null && sg != null &&
                    (!indoor || sg.floor == floor) && sg.distanceM <= reach) {
                    val err = abs(Geo.wrapDeg(sg.bearingDeg - yaw))
                    if (err <= AIM_CONE_DEG || sg.distanceM < pointBlank) targetSign = sg
                }
                if (targetNpc == null && targetLever == null && targetSign == null) {
                    for (p in worldPlaces()) {
                        if (p.distanceM > reach) continue
                        val err = abs(Geo.wrapDeg(p.bearingDeg - yaw))
                        if (err <= AIM_CONE_DEG || p.distanceM < pointBlank) {
                            targetPlace = p
                            break
                        }
                    }
                }
            }
        }
        // ---- sighting beyond reach: anything you can TAP to mark ----
        // Ghosts may ONLY mark places (the pool/beacon that frees them).
        farTarget = null
        if (head.hasData && targetItem == null && targetHerb == null &&
            targetCritter == null && targetMonster == null && !shrineTargeted &&
            targetPortal == null && targetNpc == null && targetLever == null &&
            targetSign == null && targetPlace == null
        ) {
            val yaw = head.yawDeg.toDouble()
            val reach = spawner.collectRadius()
            var bestErr = 15.0
            var best: Any? = null
            fun consider(ref: Any, bearing: Double, dist: Double, refFloor: Int) {
                if (dist <= reach || dist > 300.0) return
                if (indoor && refFloor != floor) return
                val err = abs(Geo.wrapDeg(bearing - yaw))
                if (err < bestErr) {
                    bestErr = err
                    best = ref
                }
            }
            if (!stats.ghost) {
                for (i in spawner.items) consider(i, i.bearingDeg, i.distanceM, i.floor)
                for (h in spawner.herbs) consider(h, h.bearingDeg, h.distanceM, h.floor)
                spawner.portal?.let { consider(it, it.bearingDeg, it.distanceM, it.floor) }
                spawner.shrine?.let { consider(it, it.bearingDeg, it.distanceM, it.floor) }
                spawner.lever?.let { consider(it, it.bearingDeg, it.distanceM, it.floor) }
                spawner.npc?.let { consider(it, it.bearingDeg, it.distanceM, it.floor) }
                spawner.sign?.let { consider(it, it.bearingDeg, it.distanceM, it.floor) }
            }
            for (p in worldPlaces()) {
                if (stats.ghost && p.type != PlaceType.POOL && p.type != PlaceType.BEACON) continue
                consider(p, p.bearingDeg, p.distanceM, floor)
            }
            farTarget = best
        }
        // a dead mark clears itself
        destRef?.let { if (!destAlive(it)) destRef = null }

        // THE MARK IS ALWAYS CENTERED — so when it comes into reach it is,
        // by definition, aimed. Force it as the active target; tap collects.
        val dest = destRef
        if (dest != null) {
            if (destDistM <= spawner.collectRadius()) {
                when (dest) {
                    is Place -> { targetPlace = dest }   // ghosts reach the pool this way
                    is WorldItem -> if (!stats.ghost) targetItem = dest
                    is Herb -> if (!stats.ghost) targetHerb = dest
                    is Portal -> if (!stats.ghost) targetPortal = dest
                    is Shrine -> if (!stats.ghost) shrineTargeted = true
                    is Lever -> if (!stats.ghost) targetLever = dest
                    is Npc -> if (!stats.ghost) targetNpc = dest
                    is Signpost -> if (!stats.ghost) targetSign = dest
                }
            }
        }

        // ghosts can still reach for the pool
        if (stats.ghost && head.hasData) {
            for (p in worldPlaces()) {
                if (p.type != PlaceType.POOL && p.type != PlaceType.BEACON) continue
                if (p.distanceM > spawner.collectRadius()) continue
                targetPlace = p
                break
            }
        }

        // ---- golden hour roll (anticipation spike, ~7%/min) ----
        if (!goldenActive && nowMs - lastGoldenRollMs > 60_000L) {
            lastGoldenRollMs = nowMs
            if (rng.nextInt(100) < 7) {
                goldenActive = true
                goldenEndsMs = nowMs + GOLDEN_DURATION_MS
                stats.goldenHoursSeen += 1
                popup("THE GILDED HOUR! Double loot!", 0xFFFFD75E.toInt(), big = true)
                sfx.play("golden_start")
            }
        }

        // ---- a monster reached you: CONFRONTATION ----
        if (striker != null && !stats.ghost) {
            screen = Screen.BATTLE
            sfx.setHeartbeat(0)
            sfx.setGeiger(0)
            battle.begin(striker, stats.chapter, night, nowMs)
        }
    }

    /** Hearth-mode places live here, session-only — dead-reckoned virtual
     *  coordinates must never pollute the persistent real-world list. */
    private val hearthPlaces = ArrayList<Place>()

    /** The active anchored-place list for everything (aim/render/calling). */
    fun worldPlaces(): List<Place> = if (stats.simMode) hearthPlaces else stats.places

    /**
     * Anchored places — created near the player's stomping grounds and (for
     * the real outdoors) saved FOREVER at those coordinates. Bounded: at
     * most a few of each type; the farthest is recycled as you roam, so the
     * open world keeps offering without the save growing forever.
     */
    private fun ensurePlaces(fix: GeoFix) {
        val indoor = spawner.indoor
        val half = hearthHalfM
        val list = if (indoor) hearthPlaces else stats.places
        fun nearestOf(type: PlaceType): Place? =
            list.filter { it.type == type }.minByOrNull {
                Geo.distanceM(fix.lat, fix.lon, it.lat, it.lon)
            }

        fun create(type: PlaceType, minM: Double, maxM: Double, cap: Int) {
            // recycle the farthest of this type when at cap
            val ofType = list.filter { it.type == type && !(type == PlaceType.BEACON && it.lit) }
            if (ofType.size >= cap) {
                ofType.maxByOrNull { Geo.distanceM(fix.lat, fix.lon, it.lat, it.lon) }
                    ?.let { list.remove(it) }
            }
            val bearing = rng.nextDouble(0.0, 360.0)
            val dist = rng.nextDouble(minM, maxM)
            val (plat, plon) = Geo.destination(fix.lat, fix.lon, bearing, dist)
            val p = Place(type, plat, plon)
            p.updateFrom(fix.lat, fix.lon)
            list.add(p)
            val nowMs = SystemClock.uptimeMillis()
            if (nowMs - lastPlacePopupMs > 30_000L) {
                lastPlacePopupMs = nowMs
                popup("You hear of a ${type.label} ${windName(p.bearingDeg)}...", 0xFF7FE7E0.toInt())
            }
        }

        val poolRange = if (indoor) half * 1.2 else 400.0
        val pool = nearestOf(PlaceType.POOL)
        if (pool == null || Geo.distanceM(fix.lat, fix.lon, pool.lat, pool.lon) > poolRange) {
            create(PlaceType.POOL, if (indoor) 4.0 else 90.0, if (indoor) (half * 0.9).coerceAtLeast(6.0) else 250.0, cap = 3)
        }
        val guild = nearestOf(PlaceType.GUILD)
        if (guild == null || Geo.distanceM(fix.lat, fix.lon, guild.lat, guild.lon) > (if (indoor) half * 1.5 else 600.0)) {
            create(PlaceType.GUILD, if (indoor) 5.0 else 120.0, if (indoor) (half * 1.0).coerceAtLeast(7.0) else 350.0, cap = 2)
        }
        // (Beacons retired — the journey-leg climax relic is the goal now.)
        // a Shardkeeper quest must always remain completable
        val q = activeQuest
        if (q != null && q.kind == QuestKind.SHARD && spawner.monsters.none { it.isBoss }) {
            val boss = spawner.spawnBoss(fix.lat, fix.lon, MonsterType.valueOf(q.targetName), currentFloor)
            popup("The Shardkeeper circles back ${windName(boss.bearingDeg)}!", 0xFFFF6B6B.toInt())
        }
    }

    private fun beginScare(monster: Monster, nowMs: Long) {
        screen = Screen.SCARE
        scareMonster = monster.type
        scareStartMs = nowMs
        scareStolen = (stats.satchelCoins * 25L) / 100L
        stats.satchelCoins -= scareStolen
        stats.scaresSuffered += 1
        sfx.setHeartbeat(0)
        sfx.setGeiger(0)
        sfx.play("jump_scare", 1.0f)
        // The thief RUNS with your coins — chase it down to get them back!
        if (scareStolen > 0) {
            monster.stolenCoins = scareStolen
            monster.state = MonsterState.FLEE
            monster.fleeDeadlineMs = nowMs + SpawnManager.FLEE_DEADLINE_MS
            monster.growled = false
        } else {
            spawner.monsters.remove(monster)
        }
    }

    private fun updateScare(nowMs: Long) {
        if (nowMs - scareStartMs > SCARE_DURATION_MS) {
            screen = Screen.ROAMING
            vignetteAlpha = 0.8f
            if (scareStolen > 0) {
                // Direction clue — the first piece of evidence.
                val thief = spawner.monsters.firstOrNull { it.stolenCoins > 0 }
                val clue = thief?.let { " It fled ${windName(it.bearingDeg)} — follow the tracks!" } ?: ""
                popup("It stole $scareStolen coins!$clue", 0xFFFF9A3D.toInt(), big = true)
                sfx.play("satchel_loss")
            } else {
                popup("It got you... but your satchel was empty!", 0xFFFF6B6B.toInt())
            }
            scareMonster = null
        }
    }

    /**
     * No compass — directions are RELATIVE to where you're looking:
     * ahead, to your left/right, behind you. (The radar is heading-up too.)
     */
    private fun windName(bearing: Double): String {
        if (!head.hasData) return "nearby"
        val rel = Geo.wrapDeg(bearing - head.yawDeg.toDouble())
        return when {
            rel >= -25 && rel <= 25 -> "straight ahead"
            rel > 25 && rel < 65 -> "ahead-right"
            rel >= 65 && rel <= 115 -> "to your right"
            rel > 115 && rel < 155 -> "behind-right"
            rel < -25 && rel > -65 -> "ahead-left"
            rel <= -65 && rel >= -115 -> "to your left"
            rel < -115 && rel > -155 -> "behind-left"
            else -> "behind you"
        }
    }

    /** Pick an herb — the alchemy layer. Crafts happen BY THEMSELVES. */
    private fun pickHerb(herb: Herb) {
        spawner.herbs.remove(herb)
        val count = (stats.herbs[herb.type.name] ?: 0L) + 1L
        stats.herbs[herb.type.name] = count
        sfx.play("herb_pick")
        if (herb.type.rare) {
            popup("✦ A STARBLOOM! The rarest of blooms ✦", 0xFFFFB7E0.toInt(), big = true)
            sfx.play("shiny_sparkle")
        } else {
            popup("+1 ${herb.type.label} ($count)", 0xFF9FE0A8.toInt())
        }
        gainXp(4)
        // ...and when enough is gathered, something magical happens
        checkCraft()
    }

    /** Run the cauldron — call whenever herbs enter the pouch, however. */
    private fun checkCraft() {
        val crafted = Alchemy.tryCraft(stats.herbs) ?: return
        val heldName = heldLoadout().getOrNull(stats.heldIndex)?.name
        stats.specialItems[crafted.name] = (stats.specialItems[crafted.name] ?: 0L) + 1L
        reanchorHeld(heldName)
        popup("✨ The herbs shimmer... ${crafted.label.uppercase()}! ✨", 0xFFFFD75E.toInt(), big = true)
        popup(crafted.desc + " — swipe to hold, LEFT tap to use", 0xFF9AD1FF.toInt())
        sfx.play("craft_magic")
        gainXp(25)
        persist()
    }

    /** Strike a field foe — the red-crosshair melee. */
    private fun strikeCritter(c: Critter, nowMs: Long) {
        // FACING CHECK: you have to be looking at a foe to hit it. A creature
        // right on top of you is auto-acquired even off-axis, but flailing
        // with your back turned WHIFFS — and the wasted swing wakes it.
        if (head.hasData &&
            abs(Geo.wrapDeg(c.bearingDeg - head.yawDeg.toDouble())) > STRIKE_FACING_DEG
        ) {
            if (c.engagedMs == 0L) c.engagedMs = nowMs
            sfx.play("swing_whoosh", 0.6f)
            popup("you swing wild — MISS! (face the foe)", 0xFFFF9A3D.toInt())
            return
        }
        if (c.engagedMs == 0L) c.engagedMs = nowMs
        val weapon = Gear.weaponOf(stats)
        val usable = if (weapon.manaCost > 0 && stats.mana < weapon.manaCost) Weapon.SPARK else weapon
        if (usable.manaCost > 0) stats.mana -= usable.manaCost
        var dmg = usable.damage
        val crit = rng.nextInt(8) == 0
        if (crit) dmg *= 2
        // RANGED weapons are wands and staves — they FUMBLE in close combat.
        // Full power at range; halved once a foe is in your face (< 4 m).
        val pointBlank = c.distanceM < 4.0
        if (pointBlank && dmg > 1) dmg = (dmg + 1) / 2
        sfx.play("swing_whoosh", 0.6f)
        sfx.play(
            when (usable) {
                Weapon.SPARK -> "wand_zap"
                Weapon.EMBER -> "ember_blast"
                Weapon.FROST -> "frost_cast"
            }, 0.8f
        )
        c.hp -= dmg
        if (crit) popup("CRITICAL! $dmg", 0xFFFFD75E.toInt())
        else if (pointBlank) popup("too close! weak hit", 0xFFFF9A3D.toInt())
        if (c.hp <= 0) {
            spawner.critters.remove(c)
            targetCritter = null
            stats.crittersSlain += 1
            var coins = c.type.coins * stats.chapter
            if (goldenActive || luckyActive()) coins *= 2
            stats.satchelCoins += coins
            if (duelActive()) stats.duelScore += coins
            gainXp(c.type.xp)
            sfx.play("critter_die")
            var drops = "+$coins coins +${c.type.xp} XP"
            // foes carry pocketfuls of the wild
            if (rng.nextInt(100) < 35) {
                val herbType = HerbType.entries[rng.nextInt(HerbType.entries.size - 1)]
                stats.herbs[herbType.name] = (stats.herbs[herbType.name] ?: 0L) + 1L
                drops += " +${herbType.label}"
                checkCraft()   // drops count toward the cauldron too
            }
            // tougher creatures hoard Wander Keys
            if ((c.type.tier >= 4 || c.isBoss) && rng.nextInt(100) < (if (c.isBoss) 90 else 25)) {
                stats.keys += 1
                drops += " +Wander Key!"
            }
            if (c.isBoss) {
                popup("WARDEN SLAIN — the ${c.type.label} falls! $drops", 0xFFFFD75E.toInt(), big = true)
                onWardenDown(c)
            } else {
                popup("${c.type.label} bested! $drops", 0xFF8FE08F.toInt(), big = true)
            }
        } else {
            sfx.play("monster_yelp", 0.6f)
            val label = if (c.isBoss) "WARDEN ${c.type.label}" else c.type.label
            popup("$dmg → $label (${c.hp}/${c.hpMax})", 0xFFFFFFFF.toInt())
        }
    }

    fun luckyActive() = luckyUntilMs > SystemClock.uptimeMillis()

    /** Caught the thief — vengeance! */
    private fun recover(monster: Monster) {
        val loot = monster.stolenCoins
        stats.satchelCoins += loot
        stats.lootRecovered += loot
        stats.bestiary[monster.type.name] = (stats.bestiary[monster.type.name] ?: 0L) + 1L
        gainXp(50)
        spawner.monsters.remove(monster)
        targetMonster = null
        popup("VENGEANCE! Took back $loot coins! +50 XP", 0xFFFFD75E.toInt(), big = true)
        sfx.play("duel_win")
        sfx.play("bank_coins")
    }

    // ------------------------------------------------------------------
    //  Player actions
    // ------------------------------------------------------------------

    /** Right-temple tap. */
    fun onTap(nowMs: Long) {
        when (screen) {
            Screen.INTRO -> {
                sfx.play("ui_select")
                introPage += 1
                if (introPage >= Lore.intro.size) {
                    stats.introSeen = true
                    persist()
                    screen = Screen.TITLE
                }
            }
            Screen.TITLE -> {
                screen = Screen.ROAMING
                sfx.play("ui_select")
                sfx.setAmbient(true)
                // open this leg's story (only at its very start)
                if (legWalkedM() < 30.0) popup(Lore.legOpening(stats.mission), 0xFF8FE08F.toInt(), big = true)
            }
            Screen.ROAMING -> {
                val item = targetItem
                val herb = targetHerb
                val foe = targetCritter
                val thief = targetMonster
                val place = targetPlace
                val npc = targetNpc
                val lv = targetLever
                when {
                    stats.ghost -> when {
                        place != null -> interactPlace(place)        // reached it
                        destRef != null -> {
                            destRef = null; popup("Destination cleared", 0xFF9A93B8.toInt()); sfx.play("ui_back")
                        }
                        farTarget != null -> {
                            markDestination(farTarget!!)
                            popup("Destination: $destLabel ${destDistM.toInt()}m — drift to it", 0xFF9AD1FF.toInt(), big = true)
                            sfx.play("radar_ping", 0.7f)
                        }
                        else -> sfx.play("ghost_moan", 0.3f)
                    }
                    item != null -> collect(item, nowMs)
                    herb != null -> pickHerb(herb)
                    foe != null -> strikeCritter(foe, nowMs)
                    thief != null -> recover(thief)
                    targetPortal != null -> enterPortal()
                    npc != null -> greetNpc()
                    targetSign != null -> readSign()
                    lv != null -> pullLever(lv)
                    place != null -> interactPlace(place)
                    shrineTargeted -> bankAtShrine()
                    // tap with a mark already set (and not yet in reach) RELEASES it
                    destRef != null -> {
                        destRef = null
                        popup("Destination cleared", 0xFF9A93B8.toInt())
                        sfx.play("ui_back")
                    }
                    farTarget != null -> {
                        markDestination(farTarget!!)
                        popup("Destination: $destLabel ${destDistM.toInt()}m — just walk, any way!", 0xFFFFD75E.toInt(), big = true)
                        sfx.play("radar_ping", 0.8f)
                    }
                    else -> sfx.play("ui_move", 0.4f)
                }
            }
            Screen.BATTLE -> battle.playerSwing(nowMs)
            Screen.DIALOG -> if (dialogVillager != null) closeVillagerDialog() else acceptQuest()
            Screen.GUILD -> guildBuy()
            Screen.CALIBRATE -> saveCalibration()
            Screen.MENU -> menuSelect()
            Screen.JOURNAL, Screen.GRIMOIRE, Screen.QUESTS, Screen.GUIDE -> { sfx.play("ui_back"); screen = Screen.MENU }
            Screen.SETTINGS -> settingsSelect()
            Screen.DUEL -> duelSelect()
            Screen.SCARE -> Unit
        }
    }

    /**
     * Double-tap: from the hunt it opens the MAIN MENU; inside any
     * interaction (shopkeeper, friend, panels, battle) it EXITS it.
     */
    fun onDoubleTap() {
        // In duel code-entry, double-tap CANCELS entry only (stays on the
        // duel screen) — it must not also dump you out to the menu.
        if (screen == Screen.DUEL && duelEntryMode) {
            duelEntryMode = false
            sfx.play("ui_back")
            return
        }
        when (screen) {
            Screen.ROAMING -> {
                menuIndex = 0
                screen = Screen.MENU
                sfx.play("ui_select")
                return
            }
            Screen.BATTLE -> {
                if (battle.phase == Battle.Phase.FIGHT) battle.flee()
                return
            }
            Screen.DIALOG -> {
                if (dialogVillager != null) closeVillagerDialog() else { sfx.play("ui_back"); declineQuest() }
                return
            }
            Screen.GUILD -> {
                sfx.play("ui_back")
                screen = Screen.ROAMING   // leave the shopkeeper, back to the hunt
                return
            }
            Screen.TITLE, Screen.INTRO, Screen.SCARE -> return
            else -> Unit
        }
        sfx.play("ui_back")
        screen = when (screen) {
            Screen.MENU -> Screen.ROAMING
            Screen.JOURNAL, Screen.GRIMOIRE, Screen.SETTINGS, Screen.DUEL,
            Screen.QUESTS, Screen.CALIBRATE, Screen.GUIDE -> Screen.MENU
            else -> screen
        }
    }

    fun onTripleTap() {
        // Wipe accumulated compass trim; the GPS-course auto-tune rebuilds it
        // from scratch on the next straight walk.
        if (screen == Screen.ROAMING) {
            head.yawOffsetDeg = 0f
            stats.yawOffsetDeg = 0f
            persist()
            popup("Compass reset — walk straight to re-tune", 0xFF9AD1FF.toInt())
            sfx.play("ui_select")
        }
    }

    /** Long-HOLD = the menu (in battle: STARFALL). */
    fun onLongTap() {
        when (screen) {
            Screen.ROAMING -> {
                // press-and-hold = clear the marked destination (double-tap is
                // the menu); a handy distinct shortcut, not a duplicate.
                if (destRef != null) {
                    destRef = null
                    popup("Destination cleared", 0xFF9A93B8.toInt())
                    sfx.play("ui_back")
                } else {
                    sfx.play("ui_move", 0.4f)
                }
            }
            Screen.BATTLE -> {
                if (!battle.starfall(SystemClock.uptimeMillis())) sfx.play("ui_move", 0.4f)
            }
            else -> Unit
        }
    }

    /** Keep the hand on the same slot when the loadout list shifts. */
    private fun reanchorHeld(heldName: String?) {
        val slots = heldLoadout()
        if (slots.isEmpty()) {
            stats.heldIndex = 0
            return
        }
        val idx = slots.indexOfFirst { it.name == heldName }
        stats.heldIndex = if (idx >= 0) idx
        else stats.heldIndex.coerceIn(0, slots.size - 1)
    }

    /** LEFT temple tap = use a special item (the held one, or a wise pick). */
    fun onLeftArmTap() {
        if (screen != Screen.ROAMING && screen != Screen.BATTLE) return
        if (stats.ghost) {
            popup("Spirits cannot drink...", 0xFF9AD1FF.toInt())
            return
        }
        val held = heldLoadout().getOrNull(stats.heldIndex)
        val itemName = when {
            held != null && !held.isWeapon -> held.name
            // weapon held: pick wisely
            stats.hearts < stats.maxHearts && (stats.specialItems[SpecialItem.HEART_TONIC.name] ?: 0L) > 0 ->
                SpecialItem.HEART_TONIC.name
            stats.mana < stats.maxMana / 2 && (stats.specialItems[SpecialItem.MANA_ELIXIR.name] ?: 0L) > 0 ->
                SpecialItem.MANA_ELIXIR.name
            else -> null
        }
        if (itemName == null) {
            popup("No wonder to use — gather herbs to craft one", 0xFF9A93B8.toInt())
            sfx.play("ui_back", 0.5f)
            return
        }
        useSpecialItem(SpecialItem.valueOf(itemName))
    }

    private fun useSpecialItem(item: SpecialItem) {
        val count = stats.specialItems[item.name] ?: 0L
        if (count <= 0) {
            popup("None left...", 0xFF9A93B8.toInt())
            return
        }
        when (item) {
            SpecialItem.HEART_TONIC -> {
                if (stats.hearts >= stats.maxHearts) {
                    popup("Your hearts are already full", 0xFF9A93B8.toInt())
                    return
                }
                stats.hearts = stats.maxHearts
                popup("Heart Tonic! ♥ restored", 0xFFFF8FA0.toInt(), big = true)
            }
            SpecialItem.MANA_ELIXIR -> {
                if (stats.mana >= stats.maxMana) {
                    popup("Your spirit is already brimming", 0xFF9A93B8.toInt())
                    return
                }
                stats.mana = stats.maxMana
                popup("Mana Elixir! ✦ restored", 0xFF9AD1FF.toInt(), big = true)
            }
            SpecialItem.SMOKE_BOMB -> {
                if (screen == Screen.BATTLE && battle.phase == Battle.Phase.FIGHT) {
                    smokeEscape = true     // onBattleFled: no theft, foe banished
                    battle.flee()
                    smokeEscape = false
                    popup("POOF! You vanish — satchel intact", 0xFF9AD1FF.toInt(), big = true)
                } else {
                    popup("Save the Smoke Bomb for a battle!", 0xFF9A93B8.toInt())
                    return
                }
            }
            SpecialItem.PHOENIX_FEATHER -> {
                popup("The feather glows — it acts on its own when you fall", 0xFFFFD75E.toInt())
                return  // passive; not consumed by hand
            }
            SpecialItem.LUCKY_CHARM -> {
                luckyUntilMs = SystemClock.uptimeMillis() + 10L * 60_000L
                popup("LUCKY CHARM! Double loot for 10 minutes ✦", 0xFFFFD75E.toInt(), big = true)
            }
        }
        val heldName = heldLoadout().getOrNull(stats.heldIndex)?.name
        stats.specialItems[item.name] = count - 1
        reanchorHeld(heldName)
        sfx.play("item_use")
        persist()
    }

    /**
     * Calibration: track motion ATTEMPTS straight from the gyro speed —
     * head flicks in ANY direction (the combat rule is direction-blind).
     */
    fun updateCalibration() {
        val speed = head.angularSpeedDps
        if (!calibInAttempt && speed > 70f) {
            calibInAttempt = true
            calibAttemptPeak = speed
        } else if (calibInAttempt) {
            calibAttemptPeak = maxOf(calibAttemptPeak, speed)
            if (speed < 35f) {
                calibInAttempt = false
                calibWaves += 1
                calibPeaks.add(calibAttemptPeak)
                if (calibPeaks.size > 6) calibPeaks.removeAt(0)
                val avg = calibPeaks.average().toFloat()
                suggestedThreshold = (avg * 0.5f).coerceIn(50f, 200f)
                waveFxMs = SystemClock.uptimeMillis()
                sfx.play("swing_whoosh")
            }
        }
    }

    /** One slot in the hand: a weapon or a crafted wonder. */
    class HeldSlot(val name: String, val label: String, val sprite: String, val isWeapon: Boolean, val count: Long = 0)

    fun heldLoadout(): List<HeldSlot> {
        val slots = ArrayList<HeldSlot>()
        for (w in Weapon.entries) {
            if (stats.ownedWeapons.contains(w.name)) {
                slots.add(HeldSlot(w.name, w.label, w.sprite, isWeapon = true))
            }
        }
        for (item in SpecialItem.entries) {
            val n = stats.specialItems[item.name] ?: 0L
            if (n > 0) {
                val icon = when (item) {
                    SpecialItem.HEART_TONIC -> "potion"
                    SpecialItem.MANA_ELIXIR -> "glowcap"
                    SpecialItem.SMOKE_BOMB -> "dewroot"
                    SpecialItem.PHOENIX_FEATHER -> "starbloom"
                    SpecialItem.LUCKY_CHARM -> "sunpetal"
                }
                slots.add(HeldSlot(item.name, item.label, icon, isWeapon = false, count = n))
            }
        }
        return slots
    }

    /** Swipe ⇄ switches what's in your hand (weapon or wonder). */
    fun cycleWeapon(dir: Int) {
        val slots = heldLoadout()
        if (slots.isEmpty()) return
        stats.heldIndex = ((stats.heldIndex + dir) % slots.size + slots.size) % slots.size
        val held = slots[stats.heldIndex]
        if (held.isWeapon) stats.equippedWeapon = held.name
        weaponNotice = if (held.isWeapon) "${held.label} readied!"
        else "${held.label} in hand (x${held.count}) — tap LEFT arm to use"
        weaponNoticeMs = SystemClock.uptimeMillis()
        sfx.play("ui_select", 0.7f)
    }

    fun weaponNoticeActive(nowMs: Long) = nowMs - weaponNoticeMs < 1600L

    /**
     * The CALLING — there is always a task, wherever you stand. When no
     * Finn-quest is active, the HUD shows the most compelling nearby
     * experience, picked procedurally from whatever the world has grown
     * around the player. The open world always whispers.
     */
    fun currentCalling(): String {
        activeQuest?.let { return "◆ ${it.describe()}" }
        if (stats.ghost) {
            val pool = worldPlaces().filter { it.type == PlaceType.POOL }.minByOrNull { it.distanceM }
            return if (pool != null) "☽ Reach the Healing Pool ${windName(pool.bearingDeg)}"
            else "☽ Drift... the haunting will fade"
        }
        spawner.monsters.firstOrNull { it.stolenCoins > 0 }?.let {
            return "⚔ Chase the ${it.type.label}! ${windName(it.bearingDeg)}"
        }
        spawner.critters.firstOrNull { it.isBoss }?.let {
            return "⚔ WARDEN: ${it.type.label} ${windName(it.bearingDeg)} — slay it!"
        }
        spawner.monsters.firstOrNull { it.isBoss }?.let {
            return "★ Shardkeeper ${windName(it.bearingDeg)}"
        }
        if (destRef != null) {
            return "→ $destLabel  ${destDistM.toInt()}m — straight ahead, just walk!"
        }
        if (stats.hearts <= 1) {
            worldPlaces().filter { it.type == PlaceType.POOL }.minByOrNull { it.distanceM }?.let {
                return "♥ Healing Pool ${windName(it.bearingDeg)}"
            }
        }
        if (stats.satchelCoins > 150) {
            spawner.shrine?.let {
                return "✦ Heavy satchel! Shrine ${windName(it.bearingDeg)}"
            }
        }
        spawner.portal?.let {
            return "◎ A PORTAL swirls ${windName(it.bearingDeg)} — hurry!"
        }
        spawner.npc?.let {
            return if (spawner.npcIsVillager) "☺ A traveler waves ${windName(it.bearingDeg)}"
            else "☺ Finn waves ${windName(it.bearingDeg)}"
        }
        spawner.sign?.let { if (it.distanceM < 90) return "▤ A signpost stands ${windName(it.bearingDeg)}" }
        spawner.lever?.let { if (it.distanceM < 80) return "? A vault lever ${windName(it.bearingDeg)}..." }
        spawner.critters.minByOrNull { it.distanceM }?.let {
            if (it.distanceM < 40) return "⚔ ${it.type.label} lingers ${windName(it.bearingDeg)}"
        }
        spawner.items.minByOrNull { it.distanceM }?.let {
            return "✧ Something glitters ${windName(it.bearingDeg)}"
        }
        spawner.herbs.minByOrNull { it.distanceM }?.let {
            return "❀ ${it.type.label} grows ${windName(it.bearingDeg)}"
        }
        return "✧ Wander on — your story unfolds with every step"
    }

    fun onSwipeVertical(dir: Int) {
        when (screen) {
            Screen.ROAMING -> {
                stats.radarRangeM = when {
                    dir < 0 -> when (stats.radarRangeM) { 200 -> 100; 100 -> 50; else -> 50 }
                    else -> when (stats.radarRangeM) { 50 -> 100; 100 -> 200; else -> 200 }
                }
                sfx.play("radar_ping", 0.5f)
            }
            Screen.GUILD -> {
                val rows = Gear.shopFor(stats)
                if (rows.isNotEmpty()) {
                    guildIndex = (guildIndex + dir + rows.size) % rows.size
                    sfx.play("ui_move")
                }
            }
            Screen.MENU -> { menuIndex = (menuIndex + dir + menuItems().size) % menuItems().size; sfx.play("ui_move") }
            Screen.SETTINGS -> { settingsIndex = (settingsIndex + dir + settingsRows().size) % settingsRows().size; sfx.play("ui_move") }
            Screen.GRIMOIRE -> { grimoireIndex = (grimoireIndex + dir + ItemType.entries.size) % ItemType.entries.size; sfx.play("ui_move") }
            Screen.DUEL -> {
                if (duelEntryMode) {
                    val c = duelEntryChars[duelEntryCursor]
                    val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    val idx = alphabet.indexOf(c).coerceAtLeast(0)
                    duelEntryChars[duelEntryCursor] = alphabet[(idx - dir + alphabet.length) % alphabet.length]
                    sfx.play("ui_move")
                } else {
                    duelIndex = (duelIndex + dir + duelRows().size) % duelRows().size
                    sfx.play("ui_move")
                }
            }
            else -> Unit
        }
    }

    fun onSwipeHorizontal(dir: Int) {
        when (screen) {
            Screen.ROAMING, Screen.BATTLE -> cycleWeapon(dir)
            Screen.SETTINGS -> settingsAdjust(dir)
            Screen.DUEL -> {
                if (duelEntryMode) {
                    duelEntryCursor = (duelEntryCursor + dir + duelEntryChars.size) % duelEntryChars.size
                    sfx.play("ui_move")
                } else if (!duelActive() && !duelEnded) {
                    if (duelIndex == 0) duelWordA = (duelWordA + dir + DUEL_WORDS_A.size) % DUEL_WORDS_A.size
                    if (duelIndex == 1) duelWordB = (duelWordB + dir + DUEL_WORDS_B.size) % DUEL_WORDS_B.size
                    sfx.play("ui_move")
                }
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------
    //  Collect / bank / progression
    // ------------------------------------------------------------------

    private fun collect(item: WorldItem, nowMs: Long) {
        spawner.items.remove(item)
        if (destRef === item) destRef = null

        // The climax relic of the journey-leg: claiming it ends the leg.
        if (item.missionGoal) {
            sfx.play("collect_legendary")
            popup("THE LEG'S RELIC IS YOURS!", 0xFFFFD75E.toInt(), big = true)
            completeLeg()
            return
        }

        // A dropped satchel: exactly the stolen coins inside, no roll.
        // ("Sometimes enemies just drop items — or a friend finds them.")
        if (item.droppedCoins > 0) {
            stats.satchelCoins += item.droppedCoins
            stats.lootRecovered += item.droppedCoins
            gainXp(20)
            popup("Found a dropped satchel! +${item.droppedCoins} coins", 0xFFFFD75E.toInt(), big = true)
            sfx.play("chest_open")
            sfx.play("bank_coins")
            return
        }

        val tier = Loot.wanderTier(steps.sessionSteps)
        var coins = Loot.coinPayout(item.rarity, item.shiny, tier, goldenActive, item.duelRelic)
        if (luckyActive()) coins *= 2
        var xpGain = item.rarity.xp.toLong()

        // ---- every find DOES something (the D&D layer) ----
        when (item.type) {
            ItemType.POTION -> if (stats.hearts < stats.maxHearts) {
                stats.hearts += 1
                popup("The Glow Potion mends a heart! ♥", 0xFFFF8FA0.toInt())
                sfx.play("revive_chime", 0.6f)
            }
            ItemType.SHROOM -> {
                stats.mana = (stats.mana + 3).coerceAtMost(stats.maxMana)
                popup("Moon Shroom: +3 mana", 0xFF9AD1FF.toInt())
                sfx.play("mana_fill")
            }
            ItemType.KEY -> {
                stats.keys += 1
                popup("Wander Key! (${stats.keys} held) — vaults await", 0xFFFFD75E.toInt())
            }
            ItemType.SCROLL -> {
                // a rumor: conjure a far-off treasure worth chasing
                val fix = lastFix
                if (fix != null) {
                    val bearing = rng.nextDouble(0.0, 360.0)
                    val dist = if (spawner.indoor) hearthHalfM * 0.8 else rng.nextDouble(90.0, 160.0)
                    val (rlat, rlon) = Geo.destination(fix.lat, fix.lon, bearing, dist)
                    val rumor = WorldItem(
                        System.nanoTime(), ItemType.CROWN,
                        if (rng.nextInt(3) == 0) Rarity.LEGENDARY else Rarity.EPIC,
                        Loot.rollShiny(rng), duelActive(), rlat, rlon, currentFloor
                    )
                    rumor.updateFrom(fix.lat, fix.lon)
                    spawner.items.add(rumor)
                    popup("The scroll whispers: treasure ${windName(bearing)}!", 0xFFC98FFF.toInt(), big = true)
                }
            }
            else -> Unit
        }

        if (item.type == ItemType.CHEST) {
            // Vaults are LOCKED — a Wander Key turns scarcity into purpose.
            if (item.droppedCoins == 0L && stats.keys <= 0) {
                spawner.items.add(item)  // it stays, taunting you
                popup("Locked tight... find a Wander Key!", 0xFFAAAAAA.toInt())
                sfx.play("ui_back", 0.6f)
                return
            }
            if (item.droppedCoins == 0L) {
                stats.keys -= 1
                coins *= 3   // vaults pay triple
            }
            stats.chestsOpened += 1
            sfx.play("chest_open")
            val bonus = Loot.coinPayout(Loot.rollRarity(tier, rng), false, tier, goldenActive, false)
            coins += bonus
            xpGain += 10
        } else {
            sfx.play(
                when (item.rarity) {
                    Rarity.COMMON -> "collect_common"
                    Rarity.UNCOMMON -> "collect_uncommon"
                    Rarity.RARE -> "collect_rare"
                    Rarity.EPIC -> "collect_epic"
                    Rarity.LEGENDARY -> "collect_legendary"
                }
            )
        }
        if (item.shiny) {
            stats.shiniesFound += 1
            sfx.play("shiny_sparkle")
            popup("SHINY ${item.type.label.uppercase()}!", 0xFFB9F2FF.toInt(), big = true)
        }

        stats.satchelCoins += coins
        stats.itemsCollected += 1
        stats.recordCollect(item.type, item.rarity, item.shiny)
        if (duelActive()) stats.duelScore += coins
        gainXp(xpGain)
        questProgress(QuestKind.COLLECT, item.type.name)

        val rarityColors = intArrayOf(
            0xFFE6E6E6.toInt(), 0xFF8FE08F.toInt(), 0xFF6FB8FF.toInt(),
            0xFFC98FFF.toInt(), 0xFFFFD75E.toInt()
        )
        val tag = if (item.duelRelic) " ⚔" else ""
        popup("+$coins ✦ ${item.rarity.label} ${item.type.label}$tag", rarityColors[item.rarity.colorIdx])

        // streak: first find of the day
        val today = stats.todayKey()
        if (stats.lastPlayDay != today) {
            stats.streakDays = if (isYesterday(stats.lastPlayDay, today)) stats.streakDays + 1 else 1
            stats.lastPlayDay = today
            val bonus = (50L * stats.streakDays).coerceAtMost(500L)
            stats.satchelCoins += bonus
            popup("Day ${stats.streakDays} streak! +$bonus coins", 0xFFFFB76B.toInt(), big = true)
            sfx.play("streak_chime")
        }
    }

    private fun isYesterday(prevDay: String, today: String): Boolean {
        if (prevDay.isEmpty()) return false
        return runCatching {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val prev = fmt.parse(prevDay)!!.time
            val now = fmt.parse(today)!!.time
            (now - prev) in 1..(36L * 3600L * 1000L)
        }.getOrDefault(false)
    }

    private fun bankAtShrine() {
        val amount = stats.satchelCoins
        if (amount <= 0) {
            popup("Your satchel is empty, wanderer", 0xFFAAAAAA.toInt())
            sfx.play("ui_move", 0.5f)
            return
        }
        stats.bankCoins += amount
        stats.satchelCoins = 0
        stats.shrinesBanked += 1
        gainXp(15)
        questProgress(QuestKind.BANK, "", amount.toInt())
        popup("Banked $amount coins — safe forever!", 0xFFFFD75E.toInt(), big = true)
        sfx.play("shrine_chime")
        sfx.play("bank_coins")
        val fix = lastFix
        if (fix != null) spawner.relocateShrine(fix.lat, fix.lon, currentFloor)
    }

    private fun gainXp(amount: Long) {
        stats.xp += amount
        val newLevel = Loot.levelForXp(stats.xp)
        if (newLevel > stats.level) {
            stats.level = newLevel
            popup("LEVEL $newLevel!", 0xFFFFD75E.toInt(), big = true)
            sfx.play("level_up")
            // JRPG stat growth
            if (newLevel % 5 == 0 && stats.maxHearts < 6) {
                stats.maxHearts += 1
                stats.hearts = stats.maxHearts
                popup("Your heart grows stronger! ♥${stats.maxHearts}", 0xFFFF8FA0.toInt())
            }
            if (newLevel % 3 == 0 && stats.maxMana < 16) {
                stats.maxMana += 1
                popup("Your spirit deepens! ✦${stats.maxMana} mana", 0xFF9AD1FF.toInt())
            }
            for (theme in GameTheme.entries) {
                if (newLevel >= theme.unlockLevel && !stats.unlockedThemes.contains(theme)) {
                    stats.unlockedThemes.add(theme)
                    popup("New look unlocked: ${theme.label}!", 0xFFC98FFF.toInt(), big = true)
                    sfx.play("unlock_fanfare")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  Quests, places, Finn, levers
    // ------------------------------------------------------------------

    fun questProgress(kind: QuestKind, targetName: String, amount: Int = 1) {
        val q = activeQuest ?: return
        if (q.kind != kind) return
        if (kind != QuestKind.BANK && q.targetName.isNotEmpty() && q.targetName != targetName) return
        q.progress = (q.progress + amount).coerceAtMost(q.needed)
        if (q.progress >= q.needed) {
            stats.satchelCoins += q.rewardCoins
            stats.questsDone += 1
            var line = "Quest complete! +${q.rewardCoins} coins"
            if (q.rewardShard) {
                stats.shards += 1
                line += " + CROWN SHARD"
                sfx.play("shard_get")
            }
            popup(line, 0xFFFFD75E.toInt(), big = true)
            sfx.play("streak_chime")
            activeQuest = null
            stats.activeQuestJson = ""
            gainXp(40)
            popup("Shards: ${stats.shards}/3 — the Beacon waits", 0xFFC98FFF.toInt())
        } else {
            popup("Quest: ${q.describe()}", 0xFF8FE08F.toInt())
            stats.activeQuestJson = q.toJson()
        }
        persist()
    }

    var dialogVillager: String? = null     // a villager's line (DIALOG screen)
    var dialogVillagerGift: String? = null

    /** Tap a road-folk: a villager opens a readable DIALOG (held until you
     *  dismiss it — never a blink); Keeper Finn opens a quest. */
    private fun greetNpc() {
        if (spawner.npcIsVillager) {
            dialogVillager = Lore.villagerLine(rng)
            dialogVillagerGift = when (rng.nextInt(3)) {
                0 -> { val c = 5L + rng.nextInt(15); stats.satchelCoins += c; "They press $c coins into your hand." }
                1 -> {
                    val h = HerbType.entries[rng.nextInt(HerbType.entries.size - 1)]
                    stats.herbs[h.name] = (stats.herbs[h.name] ?: 0L) + 1L
                    checkCraft()
                    "They share a ${h.label} from their pouch."
                }
                else -> null
            }
            screen = Screen.DIALOG     // the NPC stays until you close the panel
            sfx.play("ui_select")
        } else {
            beginDialog()
        }
    }

    /** Close a villager chat — only now does the traveler move on. */
    fun closeVillagerDialog() {
        dialogVillager = null
        dialogVillagerGift = null
        if (destRef is Npc) destRef = null
        spawner.dismissNpc()
        targetNpc = null
        screen = Screen.ROAMING
        sfx.play("ui_back", 0.6f)
    }

    /** Read a roadside signpost — worldbuilding, lingering on screen. */
    private fun readSign() {
        spawner.sign?.let { popup(it.text, 0xFFE8D9A0.toInt(), big = true) }
        sfx.play("ui_select", 0.7f)
        if (destRef === spawner.sign) destRef = null
        spawner.sign = null
        targetSign = null
    }

    private fun beginDialog() {
        dialogGreeting = Lore.finnGreeting(rng)
        // Shard quest when you need shards and the dice agree; else a side job.
        dialogQuest = if (stats.shards < 3 && rng.nextInt(100) < 45) {
            Lore.rollShardQuest(stats.chapter, rng)
        } else {
            Lore.rollSideQuest(stats.chapter, rng)
        }
        screen = Screen.DIALOG
        sfx.play("ui_select")
    }

    private fun acceptQuest() {
        val q = dialogQuest ?: return
        activeQuest = q
        stats.activeQuestJson = q.toJson()
        dialogQuest = null
        spawner.dismissNpc()
        screen = Screen.ROAMING
        sfx.play("quest_accept")
        popup("Quest accepted: ${q.describe()}", 0xFF8FE08F.toInt(), big = true)
        if (q.kind == QuestKind.SHARD) {
            val fix = lastFix
            if (fix != null) {
                val boss = spawner.spawnBoss(
                    fix.lat, fix.lon, MonsterType.valueOf(q.targetName), currentFloor
                )
                popup("The Shardkeeper prowls ${windName(boss.bearingDeg)}!", 0xFFFF6B6B.toInt())
                sfx.play("monster_growl", 0.9f)
            }
        }
        persist()
    }

    private fun declineQuest() {
        dialogQuest = null
        spawner.dismissNpc()
        screen = Screen.ROAMING
        popup("Finn nods. \"The roads will wait.\"", 0xFF9A93B8.toInt())
    }

    /**
     * THE PORTAL — step through and the veil drags the world's treasure TO
     * you: every active item relocates within close reach, rarities
     * re-rolled a tier higher. Teleportation, GPS-style.
     */
    private fun enterPortal() {
        val fix = lastFix ?: return
        spawner.portal = null
        targetPortal = null
        sfx.play("portal_warp")
        vignetteAlpha = 0.4f
        val tier = (Loot.wanderTier(steps.sessionSteps) + 1).coerceAtMost(Loot.MAX_TIER)
        val near = if (spawner.indoor) 3.0..(hearthHalfM * 0.7).coerceAtLeast(5.0) else 12.0..35.0
        val relocated = ArrayList<WorldItem>()
        for (old in spawner.items) {
            val bearing = rng.nextDouble(0.0, 360.0)
            val (ilat, ilon) = Geo.destination(fix.lat, fix.lon, bearing, rng.nextDouble(near.start, near.endInclusive))
            val item = WorldItem(
                old.id, old.type,
                maxOf(old.rarity, Loot.rollRarity(tier, rng)),
                old.shiny || Loot.rollShiny(rng), old.duelRelic,
                ilat, ilon, currentFloor, old.droppedCoins
            )
            item.updateFrom(fix.lat, fix.lon)
            relocated.add(item)
        }
        spawner.items.clear()
        spawner.items.addAll(relocated)
        popup("THROUGH THE VEIL! The world's treasure follows you!", 0xFFC98FFF.toInt(), big = true)
        popup("${relocated.size} treasures shimmer nearby, finer than before", 0xFF9AD1FF.toInt())
        gainXp(15)
    }

    /**
     * THE VAULT LEVER — purpose you can see: pull it, the ground rumbles,
     * and a treasure vault rises nearby. Cause, then golden effect.
     */
    private fun pullLever(lever: Lever) {
        spawner.lever = null
        sfx.play("lever_creak")
        sfx.play("vault_rumble")
        val fix = lastFix ?: return
        val bearing = rng.nextDouble(0.0, 360.0)
        val dist = if (spawner.indoor) rng.nextDouble(3.0, (hearthHalfM * 0.7).coerceAtLeast(5.0))
        else rng.nextDouble(15.0, 30.0)
        val (vlat, vlon) = Geo.destination(fix.lat, fix.lon, bearing, dist)
        var coins = (80L + rng.nextInt(120)) * stats.chapter
        if (goldenActive || luckyActive()) coins *= 2
        // droppedCoins > 0 = pre-unlocked vault, exact payout, no key needed
        val vault = WorldItem(
            System.nanoTime(), ItemType.CHEST, Rarity.EPIC,
            shiny = false, duelRelic = false,
            lat = vlat, lon = vlon, floor = currentFloor, droppedCoins = coins
        )
        vault.updateFrom(fix.lat, fix.lon)
        spawner.items.add(vault)
        popup("RRRUMBLE — a vault rises ${windName(bearing)}!", 0xFFFFD75E.toInt(), big = true)
        popup("(${dist.toInt()}m away — go claim it)", 0xFF9AD1FF.toInt())
    }

    private fun interactPlace(place: Place) {
        val nowMs = SystemClock.uptimeMillis()
        when (place.type) {
            PlaceType.POOL -> {
                if (stats.ghost) {
                    revive("The pool's light pulls you back!")
                    questProgress(QuestKind.VISIT, "POOL")
                    return
                }
                if (nowMs < poolCooldownUntilMs) {
                    popup("The pool is still gathering its light...", 0xFF9A93B8.toInt())
                    return
                }
                if (stats.hearts >= stats.maxHearts) {
                    popup("The waters find nothing to mend", 0xFF9A93B8.toInt())
                } else {
                    stats.hearts = stats.maxHearts
                    poolCooldownUntilMs = nowMs + 90_000L
                    popup("The Healing Pool restores you! ♥ full", 0xFF8FE08F.toInt(), big = true)
                    sfx.play("revive_chime")
                }
                questProgress(QuestKind.VISIT, "POOL")
            }
            PlaceType.GUILD -> {
                if (stats.mana < stats.maxMana) {
                    stats.mana = stats.maxMana
                    popup("The Guild's lanterns refill your mana", 0xFF9AD1FF.toInt())
                    sfx.play("mana_fill")
                }
                guildIndex = 0
                screen = Screen.GUILD
                sfx.play("ui_select")
                questProgress(QuestKind.VISIT, "GUILD")
            }
            PlaceType.BEACON -> {
                if (place.lit) {
                    popup("This beacon already burns bright", 0xFF9A93B8.toInt())
                    if (stats.ghost) revive("The beacon's warmth restores you!")
                    return
                }
                if (stats.shards >= 3) {
                    stats.shards -= 3
                    place.lit = true
                    stats.beaconsLit += 1
                    popup(Lore.beaconLit(stats.chapter), 0xFFFFD75E.toInt(), big = true)
                    sfx.play("beacon_light")
                    stats.chapter = (stats.chapter + 1).coerceAtMost(9)
                    popup(Lore.chapterTitle(stats.chapter), 0xFFC98FFF.toInt(), big = true)
                    gainXp(200)
                    persist()
                } else {
                    popup("The beacon needs 3 shards (you hold ${stats.shards})", 0xFF9A93B8.toInt())
                }
                questProgress(QuestKind.VISIT, "BEACON")
            }
        }
    }

    private fun guildBuy() {
        val rows = Gear.shopFor(stats)
        if (rows.isEmpty()) {
            popup("\"You carry our finest already, Wanderer.\"", 0xFF9A93B8.toInt())
            return
        }
        val entry = rows[guildIndex.coerceIn(0, rows.size - 1)]
        val result = Gear.buy(stats, entry)
        if (result == null) {
            popup("Not enough banked coins (${stats.bankCoins}/${entry.price})", 0xFFFF6B6B.toInt())
            sfx.play("ui_back")
        } else {
            popup(result, 0xFFFFD75E.toInt(), big = true)
            sfx.play("unlock_fanfare")
            guildIndex = 0
            persist()
        }
    }

    fun popup(text: String, color: Int, big: Boolean = false) {
        popups.add(Popup(text, color, SystemClock.uptimeMillis(), big))
        if (popups.size > 5) popups.removeAt(0)
    }

    // ------------------------------------------------------------------
    //  Menus
    // ------------------------------------------------------------------

    fun menuItems(): List<String> = listOf(
        "Return to the Hunt", "How to Play", "Quest & Chronicle",
        "Adventurer's Journal", "Grimoire", "Wander Duel",
        "Calibrate the Wanderer", "Settings"
    )

    private fun menuSelect() {
        sfx.play("ui_select")
        when (menuIndex) {
            0 -> screen = Screen.ROAMING
            1 -> screen = Screen.GUIDE
            2 -> screen = Screen.QUESTS
            3 -> screen = Screen.JOURNAL
            4 -> { grimoireIndex = 0; screen = Screen.GRIMOIRE }
            5 -> { duelIndex = 0; screen = Screen.DUEL }
            6 -> beginCalibration()
            7 -> { settingsIndex = 0; screen = Screen.SETTINGS }
        }
    }

    private fun beginCalibration() {
        calibPeaks.clear()
        calibWaves = 0
        calibInAttempt = false
        suggestedThreshold = stats.shakeDps
        screen = Screen.CALIBRATE
    }

    /** Tap on the calibration screen = save the tuned shake sensitivity. */
    fun saveCalibration() {
        if (calibWaves >= 2 && suggestedThreshold > 0f) {
            stats.shakeDps = suggestedThreshold
            popup("Motion tuned! trigger ${"%.0f".format(suggestedThreshold)}°/s", 0xFF8FE08F.toInt(), big = true)
            sfx.play("quest_accept")
            persist()
        } else {
            popup("Shake your head a few times first, then tap to save", 0xFF9A93B8.toInt())
            return
        }
        screen = Screen.MENU
    }

    // ---- settings ----

    fun settingsRows(): List<Pair<String, String>> {
        val themeLabel = stats.activeTheme.label
        return listOf(
            "Volume" to "${stats.volumePercent}%",
            "Look" to themeLabel,
            "Adventurer Name" to stats.playerName,
            "Auto Compass Tune" to if (stats.autoCompass) "ON" else "OFF",
            "Compass Axis" to if (stats.axisMode == 0) "A" else "B",
            "Sage's Overlay (debug)" to if (stats.debugOverlay) "ON" else "OFF"
        )
    }

    /** Settings row "select" = same as adjust right. */
    private fun settingsSelect() = settingsAdjust(1)

    private fun settingsAdjust(dir: Int) {
        sfx.play("ui_select", 0.6f)
        when (settingsIndex) {
            0 -> {
                stats.volumePercent = (stats.volumePercent + dir * 10).coerceIn(0, 100)
                sfx.masterVolume = stats.volumePercent / 100f
                sfx.play("collect_common")
            }
            1 -> {
                val unlocked = GameTheme.entries.filter { stats.unlockedThemes.contains(it) }
                val cur = unlocked.indexOf(stats.activeTheme).coerceAtLeast(0)
                stats.activeTheme = unlocked[(cur + dir + unlocked.size) % unlocked.size]
            }
            2 -> {
                val names = NAME_CYCLE.split(",")
                val cur = names.indexOf(stats.playerName).coerceAtLeast(0)
                stats.playerName = names[(cur + dir + names.size) % names.size]
            }
            3 -> stats.autoCompass = !stats.autoCompass
            4 -> {
                head.axisMode = if (head.axisMode == 0) 1 else 0
                stats.axisMode = head.axisMode
            }
            5 -> stats.debugOverlay = !stats.debugOverlay
        }
        persist()
    }

    // ---- duel ----

    fun duelActive(): Boolean =
        stats.duelCode.isNotEmpty() && stats.duelEndsAtMs > System.currentTimeMillis()

    /** True when a duel finished and the result code should be shown. */
    fun duelAwaitingResult(): Boolean =
        stats.duelCode.isNotEmpty() && stats.duelEndsAtMs in 1..System.currentTimeMillis()

    fun currentDuelCodeWord(): String = DUEL_WORDS_A[duelWordA] + DUEL_WORDS_B[duelWordB]

    fun duelRows(): List<String> = when {
        duelActive() -> listOf("Score: ${stats.duelScore}", "Time left: ${duelTimeLeft()}", "Abandon duel")
        duelAwaitingResult() -> listOf(
            "Your result: ${DuelManager.resultCode(stats.duelCode, stats.duelScore)}",
            "Enter rival's result code",
            "Finish duel (no compare)"
        )
        else -> listOf(
            "Word 1: ${DUEL_WORDS_A[duelWordA]}",
            "Word 2: ${DUEL_WORDS_B[duelWordB]}",
            "Begin duel \"${currentDuelCodeWord()}\" (24h)"
        )
    }

    fun duelTimeLeft(): String {
        val ms = (stats.duelEndsAtMs - System.currentTimeMillis()).coerceAtLeast(0)
        val h = ms / 3_600_000L
        val m = (ms % 3_600_000L) / 60_000L
        return "${h}h ${m}m"
    }

    private fun duelSelect() {
        if (duelEntryMode) {
            // Confirm entered rival code
            val entered = String(duelEntryChars)
            val formatted = "WQ-" + entered.substring(0, 5) + "-" + entered.substring(5, 7)
            val rivalScore = DuelManager.verifyResultCode(stats.duelCode, formatted)
            duelEntryMode = false
            if (rivalScore == null) {
                duelVerdict = "Code doesn't match this duel — check it!"
                sfx.play("ui_back")
            } else {
                val won = stats.duelScore >= rivalScore
                if (won) {
                    stats.duelWins += 1
                    duelVerdict = "VICTORY! ${stats.duelScore} vs $rivalScore"
                    sfx.play("duel_win")
                } else {
                    stats.duelLosses += 1
                    duelVerdict = "Defeat... ${stats.duelScore} vs $rivalScore. Next time!"
                    sfx.play("duel_lose")
                }
                stats.rivals.add(
                    RivalEntry("RIVAL-${stats.duelCode}", rivalScore, won, System.currentTimeMillis())
                )
                stats.duelCode = ""
                stats.duelEndsAtMs = 0L
                stats.duelScore = 0L
                duelEnded = false
                persist()
            }
            return
        }
        sfx.play("ui_select")
        when {
            duelActive() -> if (duelIndex == 2) {
                stats.duelCode = ""
                stats.duelEndsAtMs = 0L
                stats.duelScore = 0L
                popup("Duel abandoned", 0xFFAAAAAA.toInt())
                persist()
            }
            duelAwaitingResult() -> when (duelIndex) {
                1 -> {
                    duelEntryMode = true
                    duelEntryCursor = 0
                    duelVerdict = ""
                    for (i in duelEntryChars.indices) duelEntryChars[i] = '0'
                }
                2 -> {
                    stats.duelCode = ""
                    stats.duelEndsAtMs = 0L
                    stats.duelScore = 0L
                    duelEnded = false
                    persist()
                }
            }
            else -> if (duelIndex == 2) {
                stats.duelCode = DuelManager.normalizeCode(currentDuelCodeWord())
                stats.duelEndsAtMs = System.currentTimeMillis() + DuelManager.DUEL_DURATION_MS
                stats.duelScore = 0
                duelEnded = false
                duelVerdict = ""
                popup("Duel \"${stats.duelCode}\" begins! ⚔", 0xFFFF9A3D.toInt(), big = true)
                sfx.play("duel_start")
                persist()
                screen = Screen.ROAMING
            }
        }
    }
}
