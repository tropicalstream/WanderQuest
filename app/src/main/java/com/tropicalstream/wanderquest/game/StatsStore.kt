package com.tropicalstream.wanderquest.game

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent stats — every grind survives. Single JSON document inside
 * SharedPreferences; written on autosave / pause (AR glasses can kill
 * Activities without onDestroy, so we save early and often).
 */
class StatsStore(context: Context) {

    private val prefs = context.getSharedPreferences("wanderquest", Context.MODE_PRIVATE)

    // economy
    var bankCoins = 0L
    var satchelCoins = 0L
    var xp = 0L
    var level = 1

    // journey (never marketed as exercise — these are "paces" and "leagues")
    var totalSteps = 0L
    var totalDistanceM = 0.0
    var bestWanderTier = 0

    // chronicle
    var itemsCollected = 0L
    var shiniesFound = 0L
    var chestsOpened = 0L
    var monstersEscaped = 0L
    var scaresSuffered = 0L
    var lootRecovered = 0L
    var shrinesBanked = 0L
    var goldenHoursSeen = 0L

    // streak
    var streakDays = 0
    var lastPlayDay = ""

    // grimoire: per ItemType -> [count, bestRarityOrdinal, shinyFound(0/1)]
    val grimoire = HashMap<String, LongArray>()
    // monster sightings: per MonsterType -> times escaped
    val bestiary = HashMap<String, Long>()

    // themes
    var activeTheme = GameTheme.FANTASY
    val unlockedThemes = linkedSetOf(GameTheme.FANTASY)

    // the adventurer (Sundered Crown update)
    var hearts = 3
    var maxHearts = 3
    var mana = 10
    var maxMana = 10
    var keys = 0
    var shards = 0
    var chapter = 1
    var ghost = false
    var introSeen = false
    var bootsOwned = false
    var armor = ArmorKind.CLOAK.name
    val ownedWeapons = linkedSetOf(Weapon.SPARK.name)
    var equippedWeapon = Weapon.SPARK.name
    var activeQuestJson = ""
    var questsDone = 0
    var battlesWon = 0
    var beaconsLit = 0
    var crittersSlain = 0L
    // mileage missions: the headline goal is DISTANCE WALKED
    var mission = 1
    var missionStartDist = 0.0   // totalDistanceM when this mission began
    var legBossesDown = 0        // wardens felled this leg (need 2 to finish)
    // alchemy pouch + crafted wonders
    val herbs = HashMap<String, Long>()         // HerbType.name -> count
    val specialItems = HashMap<String, Long>()  // SpecialItem.name -> count
    var heldIndex = 0                            // loadout slot (weapons + items)
    // anchored, persistent real-world places
    val places = ArrayList<Place>()

    // duels
    var playerName = "WANDERER"
    var duelWins = 0
    var duelLosses = 0
    val rivals = ArrayList<RivalEntry>()
    // active duel (if any)
    var duelCode = ""
    var duelEndsAtMs = 0L
    var duelScore = 0L

    // settings
    var volumePercent = 80
    var simMode = false           // Hearth Mode
    var axisMode = 0
    var yawOffsetDeg = 0f
    var autoCompass = true
    var radarRangeM = 100
    var debugOverlay = false
    var shakeDps = 80f   // combat motion trigger, deg/s (any-direction head flick)

    fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun load() {
        val raw = prefs.getString("save", null) ?: return
        runCatching {
            val o = JSONObject(raw)
            bankCoins = o.optLong("bankCoins")
            satchelCoins = o.optLong("satchelCoins")
            xp = o.optLong("xp")
            level = o.optInt("level", 1)
            totalSteps = o.optLong("totalSteps")
            totalDistanceM = o.optDouble("totalDistanceM", 0.0)
            bestWanderTier = o.optInt("bestWanderTier")
            itemsCollected = o.optLong("itemsCollected")
            shiniesFound = o.optLong("shiniesFound")
            chestsOpened = o.optLong("chestsOpened")
            monstersEscaped = o.optLong("monstersEscaped")
            scaresSuffered = o.optLong("scaresSuffered")
            lootRecovered = o.optLong("lootRecovered")
            shrinesBanked = o.optLong("shrinesBanked")
            goldenHoursSeen = o.optLong("goldenHoursSeen")
            streakDays = o.optInt("streakDays")
            lastPlayDay = o.optString("lastPlayDay")
            playerName = o.optString("playerName", "WANDERER")
            duelWins = o.optInt("duelWins")
            duelLosses = o.optInt("duelLosses")
            duelCode = o.optString("duelCode")
            duelEndsAtMs = o.optLong("duelEndsAtMs")
            duelScore = o.optLong("duelScore")
            volumePercent = o.optInt("volumePercent", 80)
            simMode = o.optBoolean("simMode", false)
            axisMode = o.optInt("axisMode", 0)
            yawOffsetDeg = o.optDouble("yawOffsetDeg", 0.0).toFloat()
            autoCompass = o.optBoolean("autoCompass", true)
            radarRangeM = o.optInt("radarRangeM", 100)
            debugOverlay = o.optBoolean("debugOverlay", false)
            shakeDps = o.optDouble("shakeDps", 80.0).toFloat()
            activeTheme = runCatching {
                GameTheme.valueOf(o.optString("activeTheme", "FANTASY"))
            }.getOrDefault(GameTheme.FANTASY)
            unlockedThemes.clear()
            unlockedThemes.add(GameTheme.FANTASY)
            val ut = o.optJSONArray("unlockedThemes") ?: JSONArray()
            for (i in 0 until ut.length()) {
                runCatching { unlockedThemes.add(GameTheme.valueOf(ut.getString(i))) }
            }
            grimoire.clear()
            val g = o.optJSONObject("grimoire") ?: JSONObject()
            for (k in g.keys()) {
                val a = g.getJSONArray(k)
                grimoire[k] = longArrayOf(a.getLong(0), a.getLong(1), a.getLong(2))
            }
            bestiary.clear()
            val b = o.optJSONObject("bestiary") ?: JSONObject()
            for (k in b.keys()) bestiary[k] = b.getLong(k)
            rivals.clear()
            val r = o.optJSONArray("rivals") ?: JSONArray()
            for (i in 0 until r.length()) {
                val e = r.getJSONObject(i)
                rivals.add(
                    RivalEntry(
                        e.optString("name"), e.optLong("score"),
                        e.optBoolean("won"), e.optLong("date")
                    )
                )
            }
            hearts = o.optInt("hearts", 3)
            maxHearts = o.optInt("maxHearts", 3)
            mana = o.optInt("mana", 10)
            maxMana = o.optInt("maxMana", 10)
            keys = o.optInt("keys")
            shards = o.optInt("shards")
            chapter = o.optInt("chapter", 1)
            ghost = o.optBoolean("ghost", false)
            introSeen = o.optBoolean("introSeen", false)
            bootsOwned = o.optBoolean("bootsOwned", false)
            armor = o.optString("armor", ArmorKind.CLOAK.name)
            equippedWeapon = o.optString("equippedWeapon", Weapon.SPARK.name)
            activeQuestJson = o.optString("activeQuestJson", "")
            questsDone = o.optInt("questsDone")
            battlesWon = o.optInt("battlesWon")
            beaconsLit = o.optInt("beaconsLit")
            crittersSlain = o.optLong("crittersSlain")
            mission = o.optInt("mission", 1)
            missionStartDist = o.optDouble("missionStartDist", 0.0)
            legBossesDown = o.optInt("legBossesDown", 0)
            heldIndex = o.optInt("heldIndex")
            herbs.clear()
            val hb = o.optJSONObject("herbs") ?: JSONObject()
            for (k in hb.keys()) herbs[k] = hb.getLong(k)
            specialItems.clear()
            val si = o.optJSONObject("specialItems") ?: JSONObject()
            for (k in si.keys()) specialItems[k] = si.getLong(k)
            ownedWeapons.clear()
            ownedWeapons.add(Weapon.SPARK.name)
            val ow = o.optJSONArray("ownedWeapons") ?: JSONArray()
            for (i in 0 until ow.length()) ownedWeapons.add(ow.getString(i))
            places.clear()
            val pl = o.optJSONArray("places") ?: JSONArray()
            for (i in 0 until pl.length()) {
                val p = pl.getJSONObject(i)
                runCatching {
                    places.add(
                        Place(
                            PlaceType.valueOf(p.getString("type")),
                            p.getDouble("lat"), p.getDouble("lon"),
                            p.optBoolean("lit", false)
                        )
                    )
                }
            }
        }
    }

    fun save() {
        val o = JSONObject()
        o.put("bankCoins", bankCoins)
        o.put("satchelCoins", satchelCoins)
        o.put("xp", xp)
        o.put("level", level)
        o.put("totalSteps", totalSteps)
        o.put("totalDistanceM", totalDistanceM)
        o.put("bestWanderTier", bestWanderTier)
        o.put("itemsCollected", itemsCollected)
        o.put("shiniesFound", shiniesFound)
        o.put("chestsOpened", chestsOpened)
        o.put("monstersEscaped", monstersEscaped)
        o.put("scaresSuffered", scaresSuffered)
        o.put("lootRecovered", lootRecovered)
        o.put("shrinesBanked", shrinesBanked)
        o.put("goldenHoursSeen", goldenHoursSeen)
        o.put("streakDays", streakDays)
        o.put("lastPlayDay", lastPlayDay)
        o.put("playerName", playerName)
        o.put("duelWins", duelWins)
        o.put("duelLosses", duelLosses)
        o.put("duelCode", duelCode)
        o.put("duelEndsAtMs", duelEndsAtMs)
        o.put("duelScore", duelScore)
        o.put("volumePercent", volumePercent)
        o.put("simMode", simMode)
        o.put("axisMode", axisMode)
        o.put("yawOffsetDeg", yawOffsetDeg.toDouble())
        o.put("autoCompass", autoCompass)
        o.put("radarRangeM", radarRangeM)
        o.put("debugOverlay", debugOverlay)
        o.put("shakeDps", shakeDps.toDouble())
        o.put("activeTheme", activeTheme.name)
        o.put("unlockedThemes", JSONArray(unlockedThemes.map { it.name }))
        val g = JSONObject()
        for ((k, v) in grimoire) g.put(k, JSONArray(v.toList()))
        o.put("grimoire", g)
        val b = JSONObject()
        for ((k, v) in bestiary) b.put(k, v)
        o.put("bestiary", b)
        val r = JSONArray()
        for (e in rivals) {
            r.put(
                JSONObject()
                    .put("name", e.rivalName).put("score", e.score)
                    .put("won", e.won).put("date", e.dateMs)
            )
        }
        o.put("rivals", r)
        o.put("hearts", hearts)
        o.put("maxHearts", maxHearts)
        o.put("mana", mana)
        o.put("maxMana", maxMana)
        o.put("keys", keys)
        o.put("shards", shards)
        o.put("chapter", chapter)
        o.put("ghost", ghost)
        o.put("introSeen", introSeen)
        o.put("bootsOwned", bootsOwned)
        o.put("armor", armor)
        o.put("equippedWeapon", equippedWeapon)
        o.put("activeQuestJson", activeQuestJson)
        o.put("questsDone", questsDone)
        o.put("battlesWon", battlesWon)
        o.put("beaconsLit", beaconsLit)
        o.put("crittersSlain", crittersSlain)
        o.put("mission", mission)
        o.put("missionStartDist", missionStartDist)
        o.put("legBossesDown", legBossesDown)
        o.put("heldIndex", heldIndex)
        val hb = JSONObject()
        for ((k, v) in herbs) hb.put(k, v)
        o.put("herbs", hb)
        val si = JSONObject()
        for ((k, v) in specialItems) si.put(k, v)
        o.put("specialItems", si)
        o.put("ownedWeapons", JSONArray(ownedWeapons.toList()))
        val pl = JSONArray()
        for (p in places) {
            pl.put(
                JSONObject().put("type", p.type.name)
                    .put("lat", p.lat).put("lon", p.lon).put("lit", p.lit)
            )
        }
        o.put("places", pl)
        prefs.edit().putString("save", o.toString()).apply()
    }

    fun recordCollect(type: ItemType, rarity: Rarity, shiny: Boolean) {
        val e = grimoire.getOrPut(type.name) { longArrayOf(0, -1, 0) }
        e[0] += 1
        if (rarity.ordinal > e[1]) e[1] = rarity.ordinal.toLong()
        if (shiny) e[2] = 1
    }
}
