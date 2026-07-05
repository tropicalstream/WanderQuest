package com.tropicalstream.wanderquest.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import com.tropicalstream.wanderquest.R

/**
 * SoundPool SFX manager. The joy of the hunt — and the fear of being hunted —
 * mostly lives in here.
 *
 * X3 etiquette (dossier §5): we request transient-may-duck focus, duck to 30%
 * when someone else speaks over us, and keep total render+audio work modest.
 * All SFX are short pre-generated chiptune WAVs, so there is no decoder to
 * starve.
 */
class Sfx(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private val loaded = HashSet<Int>()

    @Volatile var masterVolume = 1.0f
    @Volatile private var duckFactor = 1.0f

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var ambientStream = 0

    // -- rhythmic layers: heartbeat (fear) and geiger ticks (greed) --
    @Volatile var heartbeatIntervalMs = 0L   // 0 = off
    @Volatile var geigerIntervalMs = 0L      // 0 = off

    private val heartbeatTick = object : Runnable {
        override fun run() {
            val interval = heartbeatIntervalMs
            if (interval > 0L) {
                play("heartbeat", 0.9f)
                handler.postDelayed(this, interval)
            }
        }
    }
    private val geigerTick = object : Runnable {
        override fun run() {
            val interval = geigerIntervalMs
            if (interval > 0L) {
                play("geiger_tick", 0.5f)
                handler.postDelayed(this, interval)
            }
        }
    }

    private val rawByName = mapOf(
        "ui_move" to R.raw.ui_move,
        "ui_select" to R.raw.ui_select,
        "ui_back" to R.raw.ui_back,
        "collect_common" to R.raw.collect_common,
        "collect_uncommon" to R.raw.collect_uncommon,
        "collect_rare" to R.raw.collect_rare,
        "collect_epic" to R.raw.collect_epic,
        "collect_legendary" to R.raw.collect_legendary,
        "shiny_sparkle" to R.raw.shiny_sparkle,
        "chest_open" to R.raw.chest_open,
        "shrine_chime" to R.raw.shrine_chime,
        "bank_coins" to R.raw.bank_coins,
        "level_up" to R.raw.level_up,
        "unlock_fanfare" to R.raw.unlock_fanfare,
        "streak_chime" to R.raw.streak_chime,
        "golden_start" to R.raw.golden_start,
        "duel_start" to R.raw.duel_start,
        "duel_win" to R.raw.duel_win,
        "duel_lose" to R.raw.duel_lose,
        "geiger_tick" to R.raw.geiger_tick,
        "radar_ping" to R.raw.radar_ping,
        "heartbeat" to R.raw.heartbeat,
        "monster_growl" to R.raw.monster_growl,
        "monster_near" to R.raw.monster_near,
        "jump_scare" to R.raw.jump_scare,
        "escape_relief" to R.raw.escape_relief,
        "satchel_loss" to R.raw.satchel_loss,
        "ambient_wind" to R.raw.ambient_wind,
        // battle
        "swing_whoosh" to R.raw.swing_whoosh,
        "wand_zap" to R.raw.wand_zap,
        "ember_blast" to R.raw.ember_blast,
        "frost_cast" to R.raw.frost_cast,
        "hit_impact" to R.raw.hit_impact,
        "monster_yelp" to R.raw.monster_yelp,
        "player_hurt" to R.raw.player_hurt,
        "battle_start" to R.raw.battle_start,
        "battle_drums" to R.raw.battle_drums,
        "telegraph_warn" to R.raw.telegraph_warn,
        "dodge_swish" to R.raw.dodge_swish,
        "ghost_moan" to R.raw.ghost_moan,
        "revive_chime" to R.raw.revive_chime,
        "mana_fill" to R.raw.mana_fill,
        "quest_accept" to R.raw.quest_accept,
        "lever_creak" to R.raw.lever_creak,
        "shard_get" to R.raw.shard_get,
        "beacon_light" to R.raw.beacon_light,
        "jrpg_victory" to R.raw.jrpg_victory,
        "crit_hit" to R.raw.crit_hit,
        "limit_ready" to R.raw.limit_ready,
        "starfall" to R.raw.starfall,
        // field & alchemy
        "herb_pick" to R.raw.herb_pick,
        "craft_magic" to R.raw.craft_magic,
        "item_use" to R.raw.item_use,
        "critter_die" to R.raw.critter_die,
        "critter_nip" to R.raw.critter_nip,
        "vault_rumble" to R.raw.vault_rumble,
        "portal_warp" to R.raw.portal_warp
    )

    fun init() {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attrs).build()
        p.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loaded.add(sampleId)
        }
        pool = p
        for ((name, res) in rawByName) {
            ids[name] = p.load(context, res, 1)
        }
        requestFocus()
    }

    private fun requestFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                duckFactor = when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> 1.0f
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> 0.3f
                    // Exact comparison — `<= LOSS` is also true for transient
                    // values (a shipped TapInsight bug; dossier §5.2).
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> 0.0f
                    else -> duckFactor
                }
            }
            .build()
        focusRequest = req
        runCatching { am.requestAudioFocus(req) }
    }

    fun play(name: String, volume: Float = 1.0f, rate: Float = 1.0f) {
        val p = pool ?: return
        val id = ids[name] ?: return
        if (!loaded.contains(id)) return
        val v = (volume * masterVolume * duckFactor).coerceIn(0f, 1f)
        if (v <= 0f) return
        p.play(id, v, v, 1, 0, rate)
    }

    @Volatile private var ambientWanted = false

    /** Looping ambient wind bed — very quiet, adds presence outdoors. */
    fun setAmbient(enabled: Boolean) {
        ambientWanted = enabled
        val p = pool ?: return
        if (enabled && ambientStream == 0) {
            val id = ids["ambient_wind"] ?: return
            if (!loaded.contains(id)) return   // not decoded yet — retried later
            val v = (0.25f * masterVolume * duckFactor).coerceIn(0f, 1f)
            ambientStream = p.play(id, v, v, 0, -1, 1.0f)
        } else if (!enabled && ambientStream != 0) {
            p.stop(ambientStream)
            ambientStream = 0
        }
    }

    /** Cheap per-frame retry: starts the bed once SoundPool finishes loading. */
    fun retryAmbientIfPending() {
        if (ambientWanted && ambientStream == 0) setAmbient(true)
        if (drumsWanted && drumStream == 0) setBattleDrums(true)
    }

    // -- battle drums loop --
    private var drumStream = 0
    @Volatile private var drumsWanted = false

    fun setBattleDrums(enabled: Boolean) {
        drumsWanted = enabled
        val p = pool ?: return
        if (enabled && drumStream == 0) {
            val id = ids["battle_drums"] ?: return
            if (!loaded.contains(id)) return
            val v = (0.5f * masterVolume * duckFactor).coerceIn(0f, 1f)
            drumStream = p.play(id, v, v, 0, -1, 1.0f)
        } else if (!enabled && drumStream != 0) {
            p.stop(drumStream)
            drumStream = 0
        }
    }

    fun setHeartbeat(intervalMs: Long) {
        if (intervalMs == heartbeatIntervalMs) return
        val wasOff = heartbeatIntervalMs == 0L
        heartbeatIntervalMs = intervalMs
        if (intervalMs > 0L && wasOff) {
            handler.removeCallbacks(heartbeatTick)
            handler.post(heartbeatTick)
        } else if (intervalMs == 0L) {
            handler.removeCallbacks(heartbeatTick)
        }
    }

    fun setGeiger(intervalMs: Long) {
        if (intervalMs == geigerIntervalMs) return
        val wasOff = geigerIntervalMs == 0L
        geigerIntervalMs = intervalMs
        if (intervalMs > 0L && wasOff) {
            handler.removeCallbacks(geigerTick)
            handler.post(geigerTick)
        } else if (intervalMs == 0L) {
            handler.removeCallbacks(geigerTick)
        }
    }

    fun pauseAll() {
        setHeartbeat(0)
        setGeiger(0)
        setAmbient(false)
        setBattleDrums(false)
        pool?.autoPause()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        runCatching {
            val am = audioManager
            val req = focusRequest
            if (am != null && req != null) am.abandonAudioFocusRequest(req)
        }
        pool?.release()
        pool = null
        ids.clear()
        loaded.clear()
    }
}
