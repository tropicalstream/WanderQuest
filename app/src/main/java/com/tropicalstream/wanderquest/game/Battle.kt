package com.tropicalstream.wanderquest.game

import kotlin.random.Random

/**
 * The confrontation — JRPG-flavored real-body combat.
 *
 * Buildup: growl -> heartbeat -> jump-scare INTRO -> battle drums.
 * You shake your head (gyro gesture) or tap to attack with the equipped weapon.
 * The monster TELEGRAPHS before it bites (warning sting + rearing sprite);
 * physically stepping during the telegraph DODGES it. Armor absorbs.
 * Damage numbers pop, criticals hit 2x, and the STAR GAUGE charges with
 * every blow given or taken — full gauge unleashes STARFALL (long-press).
 *
 * Lose your last heart and you rise as a GHOST: the world greys out and
 * nothing answers your hand until a Healing Pool (or time) restores you.
 */
class Battle(
    private val stats: StatsStore,
    private val rng: Random
) {

    enum class Phase { NONE, INTRO, FIGHT, VICTORY }

    companion object {
        const val INTRO_MS = 1300L
        const val VICTORY_MS = 3200L
        const val STARFALL_DAMAGE = 6
        const val CRIT_ONE_IN = 8
    }

    var phase = Phase.NONE
        private set
    var monster: Monster? = null
        private set
    var monsterHp = 0
    var monsterMaxHp = 0
    var telegraphing = false
    var message = ""
    var swingFlashMs = 0L
    var hurtFlashMs = 0L
    var critFlashMs = 0L
    var starfallFlashMs = 0L
    var starGauge = 0f          // 0..1; survives between battles (session)
    var victoryCoins = 0L
    var victoryXp = 0L
    var victoryDrops = ""
    var phaseStartMs = 0L

    private var nextAttackMs = 0L
    private var telegraphStartMs = 0L
    private var starAnnounced = false

    /** Telegraph window, ms — the higher the foe's level, the less time to duck. */
    var telegraphMs = 1400L
        private set
    private var foeLevel = 1

    /**
     * THE ONE RULE OF MOTION (no nod/shake ambiguity, by design):
     *  - foe lunging (telegraph) → ANY head movement = DUCK
     *  - otherwise               → ANY head movement = STRIKE
     */
    var motionThresholdDps = 80f
    private var motionDucked = false
    private var attackArmed = true
    private var lastMotionAttackMs = 0L

    /** Engine feeds live head angular speed each frame. True = struck. */
    fun noteMotion(speedDps: Float, nowMs: Long): Boolean {
        if (phase != Phase.FIGHT) return false
        if (telegraphing) {
            if (speedDps > motionThresholdDps) motionDucked = true
            attackArmed = false   // re-arm only after the lunge resolves
            return false
        }
        if (speedDps < motionThresholdDps * 0.5f) attackArmed = true
        if (attackArmed && speedDps > motionThresholdDps &&
            nowMs - lastMotionAttackMs > 900L
        ) {
            attackArmed = false
            lastMotionAttackMs = nowMs
            playerSwing(nowMs)
            return true
        }
        return false
    }

    /** 1 → 0 while the DUCK! window drains (for the timer meter). */
    fun duckTimeFraction(nowMs: Long): Float {
        if (!telegraphing || telegraphMs <= 0L) return 0f
        return ((nextAttackMs - nowMs).toFloat() / telegraphMs).coerceIn(0f, 1f)
    }

    class Pop(val text: String, val color: Int, val bornMs: Long, val big: Boolean)
    val pops = ArrayList<Pop>()

    /** Events for the engine to act on (sound, state). */
    var onSfx: ((String, Float) -> Unit)? = null
    var onPlayerDeath: (() -> Unit)? = null
    var onVictoryDone: ((Monster) -> Unit)? = null
    var onFled: ((Monster) -> Unit)? = null

    fun active() = phase != Phase.NONE

    fun begin(m: Monster, chapter: Int, night: Boolean, nowMs: Long) {
        monster = m
        val base = when (m.type) {
            MonsterType.BAT -> 2
            MonsterType.GHOUL -> 3
            MonsterType.WOLF -> 4
        }
        // x2 — confrontations last about twice as long as before
        monsterMaxHp = 2 * (base + (chapter - 1) + (if (m.isBoss) 3 else 0) + (if (night) 1 else 0))
        monsterHp = monsterMaxHp
        // foe level shrinks the DUCK! window: lvl1 ≈ 1.4 s ... floor 0.55 s
        foeLevel = chapter + (if (m.isBoss) 2 else 0) + (if (night) 1 else 0)
        telegraphMs = (1400L - 130L * (foeLevel - 1)).coerceAtLeast(550L)
        phase = Phase.INTRO
        phaseStartMs = nowMs
        telegraphing = false
        starAnnounced = starGauge >= 1f
        pops.clear()
        message = if (m.isBoss) "The SHARDKEEPER ${m.type.label} bars your way!"
        else "A wild ${m.type.label} draws near!"
        onSfx?.invoke("jump_scare", 1f)
    }

    fun update(nowMs: Long, chapter: Int) {
        pops.removeAll { nowMs - it.bornMs > 1400L }
        when (phase) {
            Phase.INTRO -> if (nowMs - phaseStartMs > INTRO_MS) {
                phase = Phase.FIGHT
                phaseStartMs = nowMs
                scheduleAttack(nowMs, chapter, first = true)
                onSfx?.invoke("battle_start", 1f)
                message = "MOVE your head to strike — MOVE again when it lunges!"
            }
            Phase.FIGHT -> updateFight(nowMs, chapter)
            Phase.VICTORY -> if (nowMs - phaseStartMs > VICTORY_MS) {
                val m = monster
                endBattle()
                if (m != null) onVictoryDone?.invoke(m)
            }
            Phase.NONE -> Unit
        }
    }

    private fun scheduleAttack(nowMs: Long, chapter: Int, first: Boolean = false) {
        var interval = (3800L - chapter * 150L).coerceAtLeast(2200L)
        if (Gear.weaponOf(stats).slowsFoe) interval = (interval * 1.6).toLong()
        if (first) interval += 1200L // grace to read the scene
        nextAttackMs = nowMs + interval
        telegraphing = false
    }

    private fun updateFight(nowMs: Long, chapter: Int) {
        if (!telegraphing && nowMs >= nextAttackMs - telegraphMs) {
            telegraphing = true
            telegraphStartMs = nowMs
            motionDucked = false
            onSfx?.invoke("telegraph_warn", 1f)
            message = "${monster?.type?.label ?: "It"} lunges — MOVE!"
        }
        if (telegraphing && nowMs >= nextAttackMs) {
            resolveMonsterAttack(nowMs, chapter)
        }
    }

    private fun resolveMonsterAttack(nowMs: Long, chapter: Int) {
        // DUCK: any head movement during the lunge.
        val ducked = motionDucked
        val armor = Gear.armorOf(stats)
        when {
            ducked -> {
                onSfx?.invoke("dodge_swish", 1f)
                pop("DUCKED!", 0xFF8FE08F.toInt(), nowMs, big = true)
                message = "It sails over your head!"
            }
            armor.absorbPct > 0 && rng.nextInt(100) < armor.absorbPct -> {
                onSfx?.invoke("hit_impact", 0.6f)
                pop("ABSORBED", 0xFF9AD1FF.toInt(), nowMs)
                message = "${armor.label} turns the blow!"
            }
            else -> {
                stats.hearts -= 1
                hurtFlashMs = nowMs
                starGauge = (starGauge + 0.25f).coerceAtMost(1f)
                onSfx?.invoke("player_hurt", 1f)
                pop("-1 ♥", 0xFFFF5252.toInt(), nowMs, big = true)
                message = "It bites! ${stats.hearts}♥ left"
                if (stats.hearts <= 0) {
                    stats.hearts = 0
                    endBattle()
                    onPlayerDeath?.invoke()
                    return
                }
            }
        }
        if (starGauge >= 1f) announceStarReady(nowMs)
        scheduleAttack(nowMs, chapter)
    }

    /** Player attack: arm swing or temple tap. */
    fun playerSwing(nowMs: Long) {
        if (phase != Phase.FIGHT) return
        val weapon = Gear.weaponOf(stats)
        val usable = if (weapon.manaCost > 0 && stats.mana < weapon.manaCost) Weapon.SPARK else weapon
        if (usable != weapon) message = "No mana — the Spark Wand answers instead!"
        if (usable.manaCost > 0) stats.mana -= usable.manaCost
        var dmg = usable.damage
        val crit = rng.nextInt(CRIT_ONE_IN) == 0
        if (crit) {
            dmg *= 2
            critFlashMs = nowMs
            onSfx?.invoke("crit_hit", 1f)
        }
        onSfx?.invoke(
            when (usable) {
                Weapon.SPARK -> "wand_zap"
                Weapon.EMBER -> "ember_blast"
                Weapon.FROST -> "frost_cast"
            }, 1f
        )
        onSfx?.invoke("swing_whoosh", 0.7f)
        swingFlashMs = nowMs
        dealDamage(dmg, crit, nowMs)
    }

    /** STARFALL — the limit break. Long-press when the gauge is full. */
    fun starfall(nowMs: Long): Boolean {
        if (phase != Phase.FIGHT || starGauge < 1f) return false
        starGauge = 0f
        starAnnounced = false
        starfallFlashMs = nowMs
        onSfx?.invoke("starfall", 1f)
        pop("★ STARFALL ★", 0xFFFFD75E.toInt(), nowMs, big = true)
        dealDamage(STARFALL_DAMAGE, crit = false, nowMs = nowMs)
        return true
    }

    private fun dealDamage(dmg: Int, crit: Boolean, nowMs: Long) {
        monsterHp -= dmg
        starGauge = (starGauge + 0.18f).coerceAtMost(1f)
        onSfx?.invoke("monster_yelp", 0.8f)
        pop(if (crit) "$dmg CRITICAL!" else "$dmg", if (crit) 0xFFFFD75E.toInt() else 0xFFFFFFFF.toInt(), nowMs, big = crit)
        if (starGauge >= 1f) announceStarReady(nowMs)
        if (monsterHp <= 0) {
            monsterHp = 0
            beginVictory(nowMs)
        }
    }

    private fun announceStarReady(nowMs: Long) {
        if (!starAnnounced) {
            starAnnounced = true
            onSfx?.invoke("limit_ready", 1f)
            pop("STAR GAUGE FULL — hold to unleash!", 0xFFFFD75E.toInt(), nowMs)
        }
    }

    private fun beginVictory(nowMs: Long) {
        phase = Phase.VICTORY
        phaseStartMs = nowMs
        telegraphing = false
        message = "VICTORY!"
        onSfx?.invoke("jrpg_victory", 1f)
    }

    /** Double-tap: run away. The monster snatches a cut as you flee. */
    fun flee() {
        val m = monster ?: return
        endBattle()
        onFled?.invoke(m)
    }

    private fun endBattle() {
        phase = Phase.NONE
        monster = null
        telegraphing = false
    }

    private fun pop(text: String, color: Int, nowMs: Long, big: Boolean = false) {
        pops.add(Pop(text, color, nowMs, big))
        if (pops.size > 6) pops.removeAt(0)
    }
}
