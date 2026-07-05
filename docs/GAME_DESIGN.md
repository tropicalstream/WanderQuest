# WanderQuest — design rationale

## The Sundered Crown (v2): from collectathon to RPG

The historical sweep, applied. **Scarcity** (Diablo loot scarcity, Zelda's
empty fields): only 2-3 treasures exist at once, spawned far — the geiger
and radar make the FIND the dopamine, not the pickup. **Items do things**
(D&D): potions heal hearts, shrooms restore mana, keys open locked vaults
(3x payout), scrolls whisper rumors of far treasure. **Gear** from the
Lantern Guild (coin sink!): Spark Wand → Ember Rod → Frost Staff, leather →
moonsilver armor, Striding Boots — swipe the pad to switch weapons, even
mid-battle. **Confrontation** (JRPG): growl → heartbeat → jump-scare intro
→ battle drums → telegraphed attacks you dodge BY PHYSICALLY STEPPING,
damage numbers, criticals, a Star Gauge limit break (STARFALL), the classic
blue message window, and a victory jingle. **Consequence**: hearts are HP;
at zero you rise as a ghost — grey world, nothing answers your hand — until
a Healing Pool, a lit beacon, or time restores you. **Permanence**: pools,
guilds and beacons anchor to real coordinates and persist forever (bounded:
farthest recycles as you roam). **Plot**: five chapters of shard-hunting,
Shardkeeper bosses, beacon-lighting, delivered by Keeper Finn — a friend
who appears on the road with quests.

**Open-world guarantee — there is always a task.** Nothing is pinned to one
neighborhood: places, quests, bosses, Finn, levers, and loot all generate
relative to wherever the player stands (move cities and the world regrows).
When no quest is active, the CALLING line on the HUD procedurally picks the
most compelling nearby experience — chase that thief, the beacon's close,
your satchel's heavy, something glitters east. The world always whispers.

What was scrubbed from the games that made walking around with a screen
addictive, and how each lesson landed in code.

## Borrowed psychology, and where it lives

**Variable-ratio rewards (Pokémon GO, every loot game).** Every trinket is
a rarity roll — common through legendary, plus a 1/64 shiny jackpot with its
own sparkle audio. Unpredictable reward on a variable schedule is the
strongest habit loop known; it's the core of `Loot.rollRarity()`.

**The near-miss / anticipation gradient (Geocaching, Zelda's shrine
chimes).** Items appear on the radar long before they're reachable, and a
geiger counter ticks faster as you close in (`Sfx.setGeiger`, interval
mapped to nearest-item distance). The reward starts paying out emotionally
*before* the grab.

**Loss aversion (Death-cap mechanics, roguelites).** Coins land in a
*satchel*; only banking at a shrine makes them permanent. A monster catch
steals 25% of the satchel. The walk to the shrine with a fat satchel while
a heartbeat thumps in your ears is the emotional spine of the game —
risk/reward you can feel in your chest.

**Steps as a hidden engine (Pokémon GO egg hatching, Pikmin Bloom).**
Wander Power = session steps / 400, capped at tier 5. Each tier shifts the
rarity table toward the good stuff and adds +10%/tier coins. The game NEVER
says "exercise" — the journal counts "paces" and "leagues," and the meter is
framed as magic charging up. More steps → better loot → more steps.

**Collection completionism (Pokédex, Animal Crossing).** The Grimoire shows
silhouettes of the 8 trinket types until found (Zeigarnik effect — open
loops itch), tracks best rarity and shiny per type.

**Streaks (Duolingo, daily quests).** First find of each day extends the
streak; bonus coins scale with it (capped so it never becomes a chore).

**Timed scarcity events (golden weekends, lure modules).** ~7%/minute roll
starts a 8-minute Gilded Hour: double loot, fanfare, HUD banner. Random,
brief, worth staying out for.

**The chase and the evidence (detective games, Zelda trails).** A monster
that catches you doesn't delete your coins — it TAKES them and runs,
visibly carrying your sparkle. It leaves a trail: footprints fade in over
150 s in the world and on the radar, a compass clue ("It fled NORTH-EAST!")
points the first direction, and a clumsy thief sometimes fumbles part of
the loot as dropped satchels along the path. Catch it (it flees slower
than you walk) for full Vengeance recovery + bonus XP; even if it escapes,
it usually abandons the satchel where it vanished — so the trail almost
always pays. Loss becomes a quest instead of a punishment.

**Physical grabbing (camera arm-motion).** When a treasure, shrine, or
thief is right in front of you, the world-facing camera switches on and a
wave of your arm grabs it — frame-difference motion detection, no ML, and
the camera runs ONLY while a target is in reach (battery discipline).
Tapping always works as fallback.

**Indoor Hearth Mode (dead reckoning).** No GPS needed indoors: each real
step moves you ~0.75 m in the direction you're looking. The hall starts at
50x50 ft with a trinket seconds away (instant first reward), then grows as
you roam past the edges. A barometer (if present) detects stairs — loot on
another floor shows as ▲/▼ on the radar.

**Fear as flavor (Five Nights jump scares, horror walking games).**
Monsters lurk, then hunt inside 110 m. Heartbeat interval maps to hunter
distance; a growl marks first approach. At 9 m: full-screen scare face,
strobe, screech (the single loudest sample in the game), satchel theft.
Escaping past 150 m pays XP and a relief sting — fear resolves into
triumph. Night (19:00–06:00) adds a monster and speeds them up.

**Friendly rivalry (friend codes, raid passes) — serverless.** Wander
Duels: both players pick the same two magic words; for 24 h duel relics
spawn (3x coins) and coins+paces score points. Result codes are CRC-checked
against the shared duel code, so scores can be exchanged verbally and
verified offline. Verified rivals populate the leaderboard page served from
the glasses (`:19111`) — the Tavern Notice Board.

## All-ages calibration

Scares are loud and sudden (as requested) but never gory — the scare faces
are chunky 16-bit monsters, the punishment is coins (recoverable), and a
caught moment ends in a popup, not a game over. The reading level of every
string is kid-friendly. Sessions have no fail state; quitting mid-walk
loses nothing but unbanked satchel risk.

## X3 Pro platform compliance (the guide's commandments)

- Pure black canvas everywhere — black = transparent = projector off.
- One 640x480 logical viewport, dual-drawn by `BinocularSbsLayout`.
- Right-temple click consumed as a KEY event, checked first in
  `dispatchKeyEvent`; left pad (`cyttsp6`) filtered from gestures.
- 30 fps frame cap (vendor thermal guidance); SFX are short SoundPool
  samples, so no decoder to starve (gotcha #7 respected).
- GPS via the RayNeo IPC bridge (no onboard GNSS) — reflective binding,
  Hearth-Mode simulator as fallback and demo mode.
- 3-DoF rotation vector for anchoring; constant mounting error absorbed
  by GPS-course auto-calibration while walking (plus an A/B axis-remap
  setting as insurance). Anchors are GPS-positioned; bearings re-projected
  per frame, so items stay glued to their street corners.
- Stats saved every 20 s and in onPause — Activities die without onDestroy
  on this device.
- No ar_mode meta-data; Mercury meta-data present; AR_APP launcher category.
