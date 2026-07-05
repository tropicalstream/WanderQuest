package com.tropicalstream.wanderquest.game

import org.json.JSONObject
import kotlin.random.Random

enum class QuestKind { COLLECT, DEFEAT, BANK, VISIT, SHARD }

/** One quest from Keeper Finn. */
class Quest(
    val kind: QuestKind,
    val targetName: String,   // ItemType / MonsterType / PlaceType name
    val needed: Int,
    var progress: Int,
    val rewardCoins: Long,
    val rewardShard: Boolean,
    val line: String
) {
    fun describe(): String {
        val what = when (kind) {
            QuestKind.COLLECT -> ItemType.valueOf(targetName).label + "s"
            QuestKind.DEFEAT, QuestKind.SHARD -> MonsterType.valueOf(targetName).label
            QuestKind.BANK -> "coins banked"
            QuestKind.VISIT -> targetName.lowercase().replaceFirstChar { it.uppercase() }
        }
        return when (kind) {
            QuestKind.SHARD -> "Defeat the Shardkeeper ($what)"
            QuestKind.VISIT -> "Visit the $what"
            else -> "$progress/$needed $what"
        }
    }

    fun toJson(): String = JSONObject()
        .put("kind", kind.name).put("target", targetName).put("needed", needed)
        .put("progress", progress).put("coins", rewardCoins).put("shard", rewardShard)
        .put("line", line).toString()

    companion object {
        fun fromJson(raw: String): Quest? = runCatching {
            val o = JSONObject(raw)
            Quest(
                QuestKind.valueOf(o.getString("kind")), o.getString("target"),
                o.getInt("needed"), o.getInt("progress"), o.getLong("coins"),
                o.getBoolean("shard"), o.getString("line")
            )
        }.getOrNull()
    }
}

/**
 * The Sundered Crown — plot and quest tables.
 *
 * The Gloom shattered the Sun Crown; its shards lie scattered across the
 * land, hoarded by Gloom-beasts. The Lantern Guild sends its Wanderers to
 * take them back and relight the Beacons, chapter by chapter.
 */
object Lore {

    val intro = listOf(
        "Long ago the SUN CROWN kept the\nGloom beyond the hedgerows.\n\nThen the Crown was SHATTERED,\nand its shards scattered across\nthe very streets you walk.",
        "Now Gloom-beasts prowl between\nthe lampposts, hoarding shards\nand stealing coin from travelers.\n\nThe LANTERN GUILD has chosen you.",
        "Find the shards. Light the beacons.\nMind your satchel — and your heart.\n\nThe hunt begins where you stand,\nWanderer."
    )

    fun chapterTitle(ch: Int) = when (ch) {
        1 -> "Chapter I — Embers in the Dark"
        2 -> "Chapter II — The Hollow Roads"
        3 -> "Chapter III — A Crown of Whispers"
        4 -> "Chapter IV — The Gloom Rising"
        else -> "Chapter V — The Last Beacon"
    }

    fun chapterStory(ch: Int) = when (ch) {
        1 -> "Finn: \"Three shards lie near, Wanderer. The beasts that hold them fear only a steady arm.\""
        2 -> "Finn: \"The roads grow hollow. The wolves carry shards in their shadows now.\""
        3 -> "Finn: \"Half the Crown whispers again! But the Gloom whispers back...\""
        4 -> "Finn: \"The Gloom knows your name. Wear good mail, friend. It bites harder now.\""
        else -> "Finn: \"One beacon left. Light it, and the dawn is yours forever.\""
    }

    fun beaconLit(ch: Int) = when (ch) {
        1 -> "The first beacon blazes! The Gloom recoils from your street."
        2 -> "Two beacons burn. Children sleep easier behind these windows."
        3 -> "Three! The half-Crown sings on the night air."
        4 -> "Four beacons! The Gloom gathers for its last stand."
        else -> "THE SUN CROWN IS WHOLE. The realm is lit... but the wilds always whisper. Wander on."
    }

    /** The quiet opening of a new leg of the journey — no numbers, just story. */
    fun legOpening(leg: Int): String = when (leg) {
        1 -> "Finn: \"The first road is short, friend. Follow the glimmer and see what the Gloom left behind.\""
        2 -> "Finn: \"A longer road now — the hamlets past the hollow have not seen lantern-light in years.\""
        3 -> "Finn: \"They say a Gloom-warden stirs down the old trade road. Walk careful, walk far.\""
        4 -> "Finn: \"The far villages whisper your name now. The road only lengthens from here.\""
        else -> "Finn: \"On and on the path winds — and the realm grows brighter behind your every step.\""
    }

    /**
     * The slow story of a leg — an ordered arc of beats delivered across
     * the walk (opening → rising → foreshadow → the prize is near), paced
     * by the hidden distance clock and culminating in the climax. Four
     * beats per leg, in order.
     */
    fun legBeats(leg: Int): List<String> = when (leg) {
        1 -> listOf(
            "The road is quiet. Finn's lantern bobs somewhere behind you.",
            "A cottage stands dark and still — something glints in its doorway.",
            "Cold air. A low growl drifts from the hedgerows. You're not alone.",
            "A faint golden glow pulses behind the trees ahead..."
        )
        2 -> listOf(
            "The road widens toward hamlets that haven't seen lantern-light in years.",
            "An abandoned market — stalls overturned, left in a hurry.",
            "A single lantern flickers in a far window. Someone still waits for dawn.",
            "Brave, faint song carries on the wind. The road's end is close."
        )
        3 -> listOf(
            "Travelers whisper of a Gloom-warden down the old trade road.",
            "Claw-marks gouge a milestone — fresh. It passed this way.",
            "The air turns heavy. Even the birds have gone silent.",
            "A lair looms somewhere ahead, ringed in cold mist. Steel yourself."
        )
        4 -> listOf(
            "Word runs ahead of you now — and so does the Gloom.",
            "A roadside shrine, defaced. The dark grows bolder the farther you walk.",
            "Shadows pool unnaturally at the verge, watching you pass.",
            "Something vast stirs beyond the next rise. The realm holds its breath."
        )
        else -> listOf(
            "Each road is longer than the last, and the Gloom runs deeper.",
            "But behind you, windows glow — the realm remembers your passing.",
            "A hush... then a distant horn. The land calls you onward.",
            "Your journey's next waypoint shimmers far ahead. Walk on."
        )
    }

    /** The leg's climax appears ahead — narrated, never measured. */
    fun legClimax(leg: Int): String = when (leg) {
        1 -> "A glint on the horizon — the leg's prize draws near. Press on!"
        2 -> "Smoke and song ahead — the road's end is close. Keep walking!"
        3 -> "The Gloom-warden's lair looms somewhere ahead. Steel yourself."
        else -> "Your journey's next waypoint shimmers in the distance. Onward!"
    }

    fun legComplete(leg: Int): String = when (leg) {
        1 -> "The first leg is yours! Finn raises a lantern in salute."
        2 -> "Two roads walked! Villages light their windows as you pass."
        3 -> "The warden falls and a town breathes free. Word travels ahead of you."
        else -> "Another leg behind you — the realm is warmer for it."
    }

    /** Town chatter / road flavor that fills the walk between climaxes. */
    val chatter = listOf(
        "A traveler nods: \"Mind the hollows after dusk, friend.\"",
        "Somewhere a child laughs — the Gloom is thinner here.",
        "An old voice on the wind: \"Shards bring back the sun, you know.\"",
        "A market crier: \"Fresh moonleaf! Glowcaps! Two for a coin!\"",
        "Finn's note flutters past: \"Keep your satchel light — bank often.\"",
        "A minstrel hums a half-remembered Crown song.",
        "Distant bells. Someone, somewhere, is grateful.",
        "A farmer waves from a field you cannot quite see.",
        "\"They say a Wanderer walks these roads again,\" someone murmurs.",
        "The scent of woodsmoke and bread drifts by."
    )

    fun chatterLine(rng: Random): String = chatter[rng.nextInt(chatter.size)]

    /** Worldbuilding carved on roadside signs — read for lore & flavor. */
    val signposts = listOf(
        "⌖ HOLLOWMERE — 2 leagues. Mind the marsh-lights.",
        "⌖ The LANTERN GUILD welcomes weary Wanderers.",
        "Notice: Gloom-beasts sighted after dusk. Travel in light.",
        "Here fell the old Sun Crown. Walk softly, remember.",
        "⌖ THORNHOLLOW market — fresh herbs, fair trades.",
        "By order of the Guild: bank your coin at the shrines.",
        "Beware the Mimic. Not every chest is a friend.",
        "⌖ To the BEACON HILLS — keep the light at your back.",
        "Pilgrim: the road is long, but the dawn is longer.",
        "Lost? Mark a glimmer ahead and simply walk to it."
    )

    fun signpostText(rng: Random): String = signposts[rng.nextInt(signposts.size)]

    /** Generic villagers met on the road — each has a little to say. */
    val villagers = listOf(
        "\"Bless you, Wanderer. The nights are kinder when you pass.\"",
        "\"Spare a coin? ...No? Then take my thanks instead.\"",
        "\"My cousin swears she saw a portal bloom near the mill.\"",
        "\"They're lighting lamps again in the next village over.\"",
        "\"Careful past the orchard — something growls in there.\"",
        "\"A Wanderer's boots never tire, they say. Lucky you!\""
    )

    fun villagerLine(rng: Random): String = villagers[rng.nextInt(villagers.size)]

    private val finnGreetings = listOf(
        "Psst — Wanderer! Over here.",
        "Well met, friend of the Lantern.",
        "The roads told me you'd come.",
        "Got your wand? Good. Listen..."
    )

    fun finnGreeting(rng: Random) = finnGreetings[rng.nextInt(finnGreetings.size)]

    /** Roll a side quest scaled to the chapter. */
    fun rollSideQuest(chapter: Int, rng: Random): Quest {
        return when (rng.nextInt(4)) {
            0 -> {
                val types = listOf(ItemType.SHROOM, ItemType.POTION, ItemType.GEM, ItemType.SCROLL)
                val t = types[rng.nextInt(types.size)]
                val n = 2 + rng.nextInt(2)
                Quest(
                    QuestKind.COLLECT, t.name, n, 0, 60L * chapter + 40L, false,
                    "\"The Guild needs $n ${t.label}s. The geiger-charm will sing you to them.\""
                )
            }
            1 -> {
                val m = MonsterType.entries[rng.nextInt(MonsterType.entries.size)]
                Quest(
                    QuestKind.DEFEAT, m.name, 1, 0, 90L * chapter, false,
                    "\"A ${m.label} stalks these streets. Show it the Guild's light.\""
                )
            }
            2 -> Quest(
                QuestKind.BANK, "", 150 * chapter, 0, 70L * chapter, false,
                "\"Bank ${150 * chapter} coins at a shrine. An empty satchel fears no thief!\""
            )
            else -> Quest(
                QuestKind.VISIT, "POOL", 1, 0, 50L * chapter, false,
                "\"Know your ground: find the Healing Pool before you need it.\""
            )
        }
    }

    /** The chapter's boss quest — its reward is a Crown Shard. */
    fun rollShardQuest(chapter: Int, rng: Random): Quest {
        val m = MonsterType.entries[rng.nextInt(MonsterType.entries.size)]
        return Quest(
            QuestKind.SHARD, m.name, 1, 0, 120L * chapter, true,
            "\"A SHARDKEEPER ${m.label} guards a Crown Shard nearby. It will not part with it kindly.\""
        )
    }
}
