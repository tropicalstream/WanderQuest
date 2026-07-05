package com.tropicalstream.wanderquest.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import com.tropicalstream.wanderquest.game.Battle
import com.tropicalstream.wanderquest.game.GameEngine
import com.tropicalstream.wanderquest.game.GameTheme
import com.tropicalstream.wanderquest.game.Gear
import com.tropicalstream.wanderquest.game.ItemType
import com.tropicalstream.wanderquest.game.Loot
import com.tropicalstream.wanderquest.game.Lore
import com.tropicalstream.wanderquest.game.MonsterState
import com.tropicalstream.wanderquest.game.PlaceType
import com.tropicalstream.wanderquest.game.Rarity
import com.tropicalstream.wanderquest.game.Screen
import com.tropicalstream.wanderquest.platform.Geo
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The single logical 640x480 viewport (BinocularSbsLayout duplicates it to
 * both eyes). Pure black canvas = transparent on the waveguide: the real
 * world shows through, and only the bright shapes we draw float over it.
 */
class GameView(context: Context, private val engine: GameEngine) : View(context) {

    companion object {
        const val W = 640f
        const val H = 480f
        const val CX = 320f
        const val CY = 240f
        const val PX_PER_DEG = 20f
        const val RENDER_DIST_M = 130.0
        // 640 px / 20 px-per-deg / 2 — beyond this, a marker is off-canvas,
        // so the edge chevrons take over exactly where the screen ends.
        const val FOV_HALF_DEG = 16.0
    }

    private val sprites = SpriteBank(context)
    private val rng = Random(7)

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = Color.WHITE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sprite = Paint().apply { isFilterBitmap = false }   // crisp pixels
    private val silhouette = Paint().apply {
        isFilterBitmap = false
        colorFilter = PorterDuffColorFilter(0xFF24203A.toInt(), PorterDuff.Mode.SRC_IN)
    }
    private var vignetteShader: RadialGradient? = null
    private val srcRect = Rect()
    private val dstRect = RectF()

    private val gold = 0xFFFFD75E.toInt()
    private val dimGold = 0xFFB89A45.toInt()
    private val panelBg = 0xEE171130.toInt()
    private val panelBorder = 0xFF4A3F7A.toInt()
    private val red = 0xFFFF5252.toInt()
    private val cyan = 0xFF7FE7E0.toInt()
    private val grayText = 0xFF9A93B8.toInt()

    // importance tiers — the colour language of the whole world
    private val MINOR_COLOR = 0xFF6FE07A.toInt()   // green  — herbs / ingredients
    private val MEDIUM_COLOR = 0xFF5BA8FF.toInt()  // blue   — trinkets / tools
    private val HIGH_COLOR = 0xFFFFB02E.toInt()    // orange — vaults / crowns

    private fun bestiaryCount(s: com.tropicalstream.wanderquest.game.StatsStore): Int {
        val names = com.tropicalstream.wanderquest.game.CritterType.entries.map { it.name }.toSet()
        return s.bestiary.keys.count { it in names }
    }

    private fun isHighItem(item: com.tropicalstream.wanderquest.game.WorldItem): Boolean =
        item.type == com.tropicalstream.wanderquest.game.ItemType.CHEST ||
            item.type == com.tropicalstream.wanderquest.game.ItemType.CROWN

    init {
        setBackgroundColor(Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        // Paint-state safety net: clear anything a prior frame may have left
        // (alpha < 255, a shader, a stray align) so nothing leaks between frames.
        text.alpha = 255; text.textAlign = Paint.Align.LEFT; text.letterSpacing = 0f
        fill.alpha = 255; fill.shader = null
        stroke.strokeWidth = 2f
        sprites.ensureTheme(engine.stats.activeTheme.dir)
        val now = SystemClock.uptimeMillis()
        when (engine.screen) {
            Screen.INTRO -> drawIntro(canvas, now)
            Screen.TITLE -> drawTitle(canvas, now)
            Screen.ROAMING -> {
                drawWorld(canvas, now)
                drawHud(canvas, now)
                if (engine.stats.ghost) drawGhostOverlay(canvas, now)
            }
            Screen.SCARE -> drawScare(canvas, now)
            Screen.BATTLE -> drawBattle(canvas, now)
            Screen.DIALOG -> drawDialog(canvas, now)
            Screen.GUILD -> drawGuild(canvas)
            Screen.QUESTS -> drawQuests(canvas)
            Screen.CALIBRATE -> drawCalibrate(canvas, now)
            Screen.GUIDE -> drawGuide(canvas)
            Screen.MENU -> drawMenu(canvas)
            Screen.JOURNAL -> drawJournal(canvas)
            Screen.GRIMOIRE -> drawGrimoire(canvas, now)
            Screen.SETTINGS -> drawSettings(canvas)
            Screen.DUEL -> drawDuel(canvas, now)
        }
        drawWaveFx(canvas, now)
        drawPopups(canvas, now)
        if (engine.vignetteAlpha > 0f) drawVignette(canvas)
        if (engine.stats.debugOverlay) drawDebug(canvas)
    }

    // ------------------------------------------------------------------
    //  World
    // ------------------------------------------------------------------

    private fun drawBitmapAt(canvas: Canvas, bmp: Bitmap?, cx: Float, cy: Float, size: Float, paint: Paint = sprite) {
        if (bmp == null) return
        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
    }

    private fun drawWorld(canvas: Canvas, now: Long) {
        if (!engine.gpsOk) {
            drawGpsSearching(canvas, now)
            return
        }
        val yaw = engine.head.yawDeg.toDouble()
        val headPitch = engine.head.pitchDeg
        val indoor = engine.spawner.indoor
        val floor = engine.currentFloor

        // ambient wireframe skyline — drawn first so all play objects overlay it
        drawBuildings(canvas, yaw, headPitch)

        // evidence trail (behind everything): fading footprints
        val nowWall = System.currentTimeMillis()
        for (t in engine.spawner.traces) {
            if (t.distanceM > RENDER_DIST_M) continue
            if (indoor && t.floor != floor) continue
            val age = (nowWall - t.bornMs).toFloat() / 150_000f
            if (age >= 1f) continue
            val rel = Geo.wrapDeg(t.bearingDeg - yaw)
            if (abs(rel) > FOV_HALF_DEG) continue
            val x = CX + rel.toFloat() * PX_PER_DEG
            val groundPitch = -Math.toDegrees(kotlin.math.atan2(1.5, t.distanceM.coerceAtLeast(2.0))).toFloat()
            val y = (CY - (groundPitch - headPitch) * PX_PER_DEG).coerceIn(60f, H - 40f)
            val s = (240.0 / t.distanceM.coerceAtLeast(4.0)).toFloat().coerceIn(3f, 10f)
            fill.color = 0xFFCC9A55.toInt()
            fill.alpha = ((1f - age) * 170).toInt().coerceIn(0, 255)
            // a clawed paw print: pad + three toes
            canvas.drawOval(x - s * 0.5f, y - s * 0.35f, x + s * 0.5f, y + s * 0.55f, fill)
            canvas.drawCircle(x - s * 0.45f, y - s * 0.65f, s * 0.22f, fill)
            canvas.drawCircle(x, y - s * 0.8f, s * 0.22f, fill)
            canvas.drawCircle(x + s * 0.45f, y - s * 0.65f, s * 0.22f, fill)
            fill.alpha = 255
        }

        // anchored places — the landmarks of the realm
        for (p in engine.worldPlaces()) {
            if (p === engine.destRef) continue   // pinned at center instead
            if (p.distanceM > RENDER_DIST_M + 120) continue
            val glow = when (p.type) {
                PlaceType.POOL -> cyan
                PlaceType.GUILD -> gold
                PlaceType.BEACON -> if (p.lit) 0xFFFFD75E.toInt() else 0xFF6B6390.toInt()
            }
            drawBillboard(
                canvas, now, p.type.sprite, p.bearingDeg, p.distanceM, yaw, headPitch,
                glow = glow,
                label = if (p.distanceM < 70) p.type.label.uppercase() else null
            )
        }
        // herbs underfoot — MINOR importance (green)
        for (h in engine.spawner.herbs) {
            if (h === engine.destRef) continue   // pinned at center instead
            if (h.distanceM > 60.0) continue
            if (indoor && h.floor != floor) continue
            drawBillboard(
                canvas, now, h.type.sprite, h.bearingDeg, h.distanceM, yaw, headPitch,
                glow = if (h.type.rare) 0xFFFFB7E0.toInt() else MINOR_COLOR,
                bobPhase = h.id.toFloat(),
                label = if (h.distanceM < 15) h.type.label.lowercase() else null
            )
        }
        // field foes — they linger; WARDENS loom larger with a name + HP bar
        for (c in engine.spawner.critters) {
            if (c.distanceM > RENDER_DIST_M) continue
            if (indoor && c.floor != floor) continue
            val targeted = engine.targetCritter === c
            if (c.isBoss) {
                // a dark aura halo behind the warden
                val rel = Geo.wrapDeg(c.bearingDeg - yaw)
                if (abs(rel) <= FOV_HALF_DEG) {
                    val bx = CX + rel.toFloat() * PX_PER_DEG
                    fill.color = 0xFFFF5252.toInt()
                    fill.alpha = 50 + (sin(now / 200.0) * 30).toInt()
                    canvas.drawCircle(bx, CY, (1400.0 / c.distanceM.coerceAtLeast(8.0)).toFloat().coerceIn(30f, 120f), fill)
                    fill.alpha = 255
                }
            }
            drawBillboard(
                canvas, now, c.type.sprite, c.bearingDeg, c.distanceM, yaw, headPitch,
                glow = if (targeted) red else (if (c.engagedMs > 0) 0x66FF5252 else if (c.isBoss) 0x66FF5252 else 0),
                bobPhase = c.id.toFloat(),
                shake = c.engagedMs > 0 || c.isBoss,
                bossScale = c.isBoss,
                label = when {
                    c.isBoss -> "WARDEN ${c.type.label}  ${c.hp}/${c.hpMax} HP · ${c.distanceM.toInt()}m"
                    c.distanceM < 30 -> "${c.type.label} ${c.hp}/${c.hpMax} · ${c.distanceM.toInt()}m"
                    else -> "${c.type.label} · ${c.distanceM.toInt()}m"
                }
            )
        }
        // the portal — swirling, blinking out
        engine.spawner.portal?.let { p ->
            if (p !== engine.destRef && p.distanceM < RENDER_DIST_M && (!indoor || p.floor == floor)) {
                val age = System.currentTimeMillis() - p.bornMs
                val fading = age > 80_000L
                if (!fading || (now / 250) % 2 == 0L) {
                    fill.color = 0xFFC98FFF.toInt()
                    fill.alpha = 50 + (sin(now / 180.0) * 40).toInt()
                    val rel = Geo.wrapDeg(p.bearingDeg - yaw)
                    if (abs(rel) <= FOV_HALF_DEG) {
                        val px = CX + rel.toFloat() * PX_PER_DEG
                        canvas.drawCircle(px, CY, (1100.0 / p.distanceM.coerceAtLeast(6.0)).toFloat().coerceIn(24f, 110f), fill)
                    }
                    fill.alpha = 255
                    drawBillboard(
                        canvas, now, "portal", p.bearingDeg, p.distanceM, yaw, headPitch,
                        glow = 0xFFC98FFF.toInt(),
                        label = if (p.distanceM < 40) "PORTAL" else null
                    )
                }
            }
        }
        // Keeper Finn
        engine.spawner.npc?.let { n ->
            if (n !== engine.destRef && n.distanceM < RENDER_DIST_M && (!indoor || n.floor == floor)) {
                drawBillboard(
                    canvas, now, "npc", n.bearingDeg, n.distanceM, yaw, headPitch,
                    glow = 0xFF8FE08F.toInt(), bobPhase = 3f,
                    label = if (n.distanceM < 60) "FINN" else null
                )
            }
        }
        // a roadside signpost
        engine.spawner.sign?.let { sg ->
            if (sg !== engine.destRef && sg.distanceM < RENDER_DIST_M && (!indoor || sg.floor == floor)) {
                drawBillboard(
                    canvas, now, "sign", sg.bearingDeg, sg.distanceM, yaw, headPitch,
                    glow = 0xFFE8D9A0.toInt(),
                    label = if (sg.distanceM < 45) "a signpost" else null
                )
            }
        }
        // the lever...
        engine.spawner.lever?.let { lv ->
            if (lv !== engine.destRef && lv.distanceM < RENDER_DIST_M && (!indoor || lv.floor == floor)) {
                drawBillboard(
                    canvas, now, "lever", lv.bearingDeg, lv.distanceM, yaw, headPitch,
                    glow = 0xFFC98FFF.toInt(),
                    label = if (lv.distanceM < 40) "old lever?" else null
                )
            }
        }
        // shrine (behind items)
        engine.spawner.shrine?.let { s ->
            if (s !== engine.destRef && s.distanceM < RENDER_DIST_M + 60 && (!indoor || s.floor == floor)) {
                drawBillboard(
                    canvas, now, "shrine", s.bearingDeg, s.distanceM, yaw, headPitch,
                    glow = cyan, label = if (s.distanceM < 40) "SHRINE" else null
                )
            }
        }
        for (item in engine.spawner.items) {
            if (item === engine.destRef) continue   // pinned at center instead
            if (item.distanceM > RENDER_DIST_M) continue
            if (indoor && item.floor != floor) continue
            val dropped = item.droppedCoins > 0
            // colour by IMPORTANCE TIER (shiny/duel keep their accent)
            val glow = when {
                dropped -> HIGH_COLOR
                item.shiny -> 0xFFB9F2FF.toInt()
                item.duelRelic -> 0xFFFF9A3D.toInt()
                isHighItem(item) -> HIGH_COLOR
                else -> MEDIUM_COLOR
            }
            drawBillboard(
                canvas, now, if (dropped) "satchel" else item.type.sprite,
                item.bearingDeg, item.distanceM, yaw, headPitch,
                glow = glow, bobPhase = item.id.toFloat(),
                label = if (dropped && item.distanceM < 60) "dropped satchel!" else null
            )
        }
        for (m in engine.spawner.monsters) {
            if (m.distanceM > RENDER_DIST_M) continue
            if (indoor && m.floor != floor) continue
            val hunting = m.state == MonsterState.HUNT
            val thief = m.stolenCoins > 0L
            drawBillboard(
                canvas, now, m.type.sprite, m.bearingDeg, m.distanceM, yaw, headPitch,
                glow = when {
                    thief -> 0xFFFF9A3D.toInt()
                    hunting -> red
                    else -> 0
                },
                bobPhase = m.id.toFloat(),
                shake = hunting && m.distanceM < 40,
                carryIcon = if (thief) "gem" else null,
                label = if (thief) "${m.stolenCoins}✦ chase it! · ${m.distanceM.toInt()}m"
                else "${m.type.label} · ${m.distanceM.toInt()}m"
            )
            // off-screen warnings: red = hunter, orange = escaping thief
            val rel = Geo.wrapDeg(m.bearingDeg - yaw)
            if (abs(rel) > FOV_HALF_DEG) {
                if (thief) drawEdgeChevron(canvas, rel > 0, 0xFFFF9A3D.toInt(), now)
                else if (hunting) drawEdgeChevron(canvas, rel > 0, red, now)
            }
        }
        // in-reach but off-screen item hints
        for (item in engine.spawner.items) {
            if (item.distanceM > 25.0) continue
            if (indoor && item.floor != floor) continue
            val rel = Geo.wrapDeg(item.bearingDeg - yaw)
            if (abs(rel) > FOV_HALF_DEG) drawEdgeChevron(canvas, rel > 0, gold, now)
        }
        // THE MARKED DESTINATION — PINNED TO SCREEN CENTER. Rotate, walk,
        // spin: it stays dead-center while the meters count down.
        engine.destRef?.let { dest ->
            run {
                val dist = engine.destDistM
                val label = engine.destLabel
                val spriteName = engine.destSpriteOf(dest)
                val pulse = (sin(now / 220.0) * 5).toFloat()
                val bob = sin(now / 380.0).toFloat() * 4f
                val size = (900.0 / dist.coerceAtLeast(6.0)).toFloat().coerceIn(26f, 92f)
                val y = CY + 4f + bob
                // gold glow + the object itself, centered
                fill.color = gold
                fill.alpha = 60 + (sin(now / 250.0) * 30).toInt()
                canvas.drawCircle(CX, y, size * 0.75f, fill)
                fill.alpha = 255
                drawBitmapAt(canvas, sprites.get(spriteName ?: ""), CX, y, size)
                // beacon beam + pulsing ring
                stroke.color = gold
                stroke.strokeWidth = 3f
                canvas.drawLine(CX, y - size / 2 - 52f - pulse, CX, y - size / 2 - 8f, stroke)
                canvas.drawCircle(CX, y, size * 0.62f + pulse, stroke)
                text.textAlign = Paint.Align.CENTER
                text.textSize = 14f
                text.color = gold
                canvas.drawText("→ $label  ${dist.toInt()}m", CX, y - size / 2 - 60f - pulse, text)
            }
        }
        drawReticle(canvas, now)
    }

    /**
     * The ambient wireframe SKYLINE: faint outlined boxes anchored in the
     * world so the streets aren't empty. Deliberately DIM (distance-faded,
     * thin strokes) and SPARSE so it frames the scene without ever crowding
     * the bright play objects drawn on top of it. On the waveguide these read
     * as ghostly far-off structures over the real world.
     */
    private fun drawBuildings(canvas: Canvas, yaw: Double, headPitchDeg: Float) {
        if (engine.spawner.indoor) return
        val wire = 0xFF6A7CB8.toInt()
        for (b in engine.spawner.buildings) {
            val dist = b.distanceM
            if (dist < 14.0 || dist > RENDER_DIST_M) continue
            val rel = Geo.wrapDeg(b.bearingDeg - yaw)
            val halfWdeg = Math.toDegrees(kotlin.math.atan2(b.widthM / 2.0, dist))
            // cull only when the whole facade is off-canvas
            if (abs(rel) - halfWdeg > FOV_HALF_DEG + 2.0) continue
            val cx = CX + rel.toFloat() * PX_PER_DEG
            val halfWpx = (halfWdeg * PX_PER_DEG).toFloat()
            val left = cx - halfWpx
            val right = cx + halfWpx
            val groundPitch = -Math.toDegrees(kotlin.math.atan2(1.2, dist)).toFloat()
            val baseY = (CY - (groundPitch - headPitchDeg) * PX_PER_DEG).coerceIn(70f, H - 30f)
            val hDeg = Math.toDegrees(kotlin.math.atan2(b.heightM, dist)).toFloat()
            val topY = (baseY - hDeg * PX_PER_DEG).coerceAtLeast(24f)
            // distance fade — far = faint; capped LOW so it never overwhelms
            val a = ((1.0 - dist / RENDER_DIST_M) * 80.0).toInt().coerceIn(20, 80)
            stroke.color = wire
            stroke.alpha = a
            stroke.strokeWidth = 1.5f
            canvas.drawRect(left, topY, right, baseY, stroke)   // flat-roof box
            // sparse mullions / storey lines, only on "busier" facades
            val cols = if (b.style > 0.55) 2 else 1
            for (i in 1..cols) {
                val x = left + (right - left) * i / (cols + 1)
                canvas.drawLine(x, topY, x, baseY, stroke)
            }
            val rows = if (b.style > 0.75) 2 else 1
            for (j in 1..rows) {
                val y = topY + (baseY - topY) * j / (rows + 1)
                canvas.drawLine(left, y, right, y, stroke)
            }
        }
        stroke.alpha = 255
        stroke.strokeWidth = 2f
    }

    private fun drawBillboard(
        canvas: Canvas, now: Long, spriteName: String,
        bearingDeg: Double, distM: Double, yawDeg: Double, headPitchDeg: Float,
        glow: Int = 0, bobPhase: Float = 0f, label: String? = null, shake: Boolean = false,
        carryIcon: String? = null, bossScale: Boolean = false
    ) {
        val rel = Geo.wrapDeg(bearingDeg - yawDeg)
        if (abs(rel) > FOV_HALF_DEG) return
        var x = CX + rel.toFloat() * PX_PER_DEG
        // Items sit a touch below eye level; counter head pitch so they
        // stay world-anchored as the player looks up/down.
        val groundPitch = -Math.toDegrees(kotlin.math.atan2(1.2, distM.coerceAtLeast(2.0))).toFloat()
        var y = CY - (groundPitch - headPitchDeg) * PX_PER_DEG
        y += sin((now / 420f) + bobPhase) * 4f
        if (shake) {
            x += rng.nextInt(-3, 4)
            y += rng.nextInt(-3, 4)
        }
        y = y.coerceIn(40f, H - 50f)
        val size = (900.0 / distM.coerceAtLeast(6.0)).toFloat()
            .coerceIn(20f, 92f) * (if (bossScale) 1.7f else 1f)
        if (glow != 0) {
            fill.color = glow
            fill.alpha = 70 + (sin(now / 250.0) * 30).toInt()
            canvas.drawCircle(x, y, size * 0.72f, fill)
            fill.alpha = 255
        }
        drawBitmapAt(canvas, sprites.get(spriteName), x, y, size)
        if (carryIcon != null) {
            // The thief visibly carries your loot — chase the sparkle.
            val bob2 = sin(now / 200.0).toFloat() * 3f
            drawBitmapAt(canvas, sprites.get(carryIcon), x + size * 0.4f, y - size * 0.55f + bob2, size * 0.4f)
        }
        if (label != null) {
            text.textSize = 12f
            text.textAlign = Paint.Align.CENTER
            text.color = if (glow != 0) glow else grayText
            canvas.drawText(label, x, y + size / 2 + 14f, text)
        }
    }

    private fun drawEdgeChevron(canvas: Canvas, rightSide: Boolean, color: Int, now: Long) {
        val pulse = (sin(now / 180.0) * 6).toFloat()
        val x = if (rightSide) W - 26f - pulse else 26f + pulse
        val dir = if (rightSide) 1f else -1f
        stroke.color = color
        stroke.strokeWidth = 4f
        val cy = CY
        canvas.drawLine(x, cy - 14f, x + 10f * dir, cy, stroke)
        canvas.drawLine(x + 10f * dir, cy, x, cy + 14f, stroke)
    }

    private fun drawReticle(canvas: Canvas, now: Long) {
        val target = engine.targetItem
        val herb = engine.targetHerb
        val foe = engine.targetCritter
        val thief = engine.targetMonster
        val portal = engine.targetPortal
        val place = engine.targetPlace
        val npc = engine.targetNpc
        val lever = engine.targetLever
        val sign = engine.targetSign
        val shrineHit = engine.shrineTargeted
        if (target != null || herb != null || foe != null || thief != null || shrineHit ||
            place != null || npc != null || lever != null || portal != null || sign != null
        ) {
            val pulse = 10f + (sin(now / 150.0) * 4).toFloat()
            stroke.color = when {
                foe != null -> red                       // RED = strike!
                target != null -> gold
                herb != null -> 0xFF9FE0A8.toInt()
                thief != null -> 0xFFFF9A3D.toInt()
                portal != null -> 0xFFC98FFF.toInt()
                npc != null -> 0xFF8FE08F.toInt()
                sign != null -> 0xFFE8D9A0.toInt()
                lever != null -> 0xFFC98FFF.toInt()
                place != null -> cyan
                else -> cyan
            }
            stroke.strokeWidth = 3f
            canvas.drawCircle(CX, CY, pulse + 8f, stroke)
            if (foe != null) {
                // crosshair ticks for the combat reticle
                stroke.strokeWidth = 2f
                canvas.drawLine(CX - pulse - 16f, CY, CX - pulse - 4f, CY, stroke)
                canvas.drawLine(CX + pulse + 4f, CY, CX + pulse + 16f, CY, stroke)
                canvas.drawLine(CX, CY - pulse - 16f, CX, CY - pulse - 4f, stroke)
                canvas.drawLine(CX, CY + pulse + 4f, CX, CY + pulse + 16f, stroke)
            }
            text.textSize = 16f
            text.textAlign = Paint.Align.CENTER
            text.color = stroke.color
            val verb = "TAP"
            // every prompt NAMES its target, so it's always clear what you
            // are about to do — and that a foe is a creature, not an object.
            val itemName = target?.let { if (it.droppedCoins > 0) "dropped satchel" else it.type.label } ?: ""
            canvas.drawText(
                when {
                    foe != null -> "$verb to FIGHT the ${foe.type.label}!"
                    target != null -> "$verb to take the $itemName"
                    herb != null -> "$verb to pick the ${herb.type.label}"
                    thief != null -> "$verb to catch the ${thief.type.label}!"
                    portal != null -> "$verb to step through the portal..."
                    npc != null -> if (engine.spawner.npcIsVillager) "$verb to greet the traveler" else "$verb to greet Finn"
                    sign != null -> "$verb to read the signpost"
                    lever != null -> "$verb to pull the vault lever"
                    place != null -> when (place.type) {
                        PlaceType.POOL -> "$verb to heal at the pool"
                        PlaceType.GUILD -> "$verb to enter the Guild shop"
                        PlaceType.BEACON -> "$verb at the Beacon"
                    }
                    else -> "$verb to bank your coins"
                },
                CX, CY + 42f, text
            )
        } else if (engine.farTarget != null) {
            // something sighted in the distance — tap to mark it
            val isMarked = engine.farTarget === engine.destRef
            stroke.color = gold
            stroke.strokeWidth = 2f
            val pulse = (sin(now / 200.0) * 3).toFloat()
            canvas.drawCircle(CX, CY, 14f + pulse, stroke)
            text.textSize = 14f
            text.textAlign = Paint.Align.CENTER
            text.color = gold
            val info = engine.destInfoOf(engine.farTarget)
            canvas.drawText(
                if (isMarked) "TAP to clear destination"
                else "TAP to set destination: ${info?.third ?: ""} ${info?.second?.toInt() ?: 0}m",
                CX, CY + 38f, text
            )
        } else {
            stroke.color = 0x88FFFFFF.toInt()
            stroke.strokeWidth = 2f
            canvas.drawLine(CX - 7f, CY, CX + 7f, CY, stroke)
            canvas.drawLine(CX, CY - 7f, CX, CY + 7f, stroke)
        }
    }

    private fun drawGpsSearching(canvas: Canvas, now: Long) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = 20f
        text.color = cyan
        canvas.drawText("Waking the world...", CX, CY - 30f, text)
        text.textSize = 13f
        text.color = grayText
        canvas.drawText("Take a step to begin — your feet power the journey", CX, CY - 8f, text)
        canvas.drawText("(grant Physical Activity if it asks)", CX, CY + 12f, text)
        // spinning compass rose
        val ang = (now / 12) % 360
        stroke.color = dimGold
        stroke.strokeWidth = 3f
        canvas.drawCircle(CX, CY + 70f, 24f, stroke)
        val rad = Math.toRadians(ang.toDouble())
        canvas.drawLine(
            CX, CY + 70f,
            CX + (cos(rad) * 20).toFloat(), CY + 70f + (sin(rad) * 20).toFloat(), stroke
        )
    }

    // ------------------------------------------------------------------
    //  HUD
    // ------------------------------------------------------------------

    private fun drawHearts(canvas: Canvas, x: Float, y: Float, size: Float) {
        val s = engine.stats
        for (i in 0 until s.maxHearts) {
            drawBitmapAt(
                canvas, sprites.get("heart"),
                x + i * (size + 3f), y, size,
                if (i < s.hearts) sprite else silhouette
            )
        }
    }

    private val tmpPath = android.graphics.Path()

    /** A small blue mana crystal sigil. */
    private fun drawManaCrystal(canvas: Canvas, cx: Float, cy: Float) {
        fill.color = 0xFF6FB8FF.toInt()
        tmpPath.rewind()
        tmpPath.moveTo(cx, cy - 7f); tmpPath.lineTo(cx + 5f, cy)
        tmpPath.lineTo(cx, cy + 7f); tmpPath.lineTo(cx - 5f, cy); tmpPath.close()
        canvas.drawPath(tmpPath, fill)
        fill.color = 0xFFBFE0FF.toInt()
        canvas.drawCircle(cx - 1.5f, cy - 2f, 1.5f, fill)
    }

    private fun drawManaBar(canvas: Canvas, x: Float, y: Float, w: Float) {
        val s = engine.stats
        stroke.color = 0xFF6FB8FF.toInt()
        stroke.strokeWidth = 2f
        canvas.drawRect(x, y, x + w, y + 8f, stroke)
        fill.color = 0xFF6FB8FF.toInt()
        if (s.maxMana > 0) {
            canvas.drawRect(x + 1f, y + 1f, x + 1f + (w - 2f) * s.mana / s.maxMana, y + 7f, fill)
        }
    }

    private fun drawHud(canvas: Canvas, now: Long) {
        if (!engine.gpsOk) return
        drawRadar(canvas, now)

        // ---- symbolic meter column, top-left — each meter LABELLED beneath ----
        val lx = 30f          // left margin clear of the lens edge
        fun caption(s: String, x: Float, y: Float) {
            text.textAlign = Paint.Align.LEFT
            text.textSize = 8.5f
            text.color = 0xFF8A82B0.toInt()
            canvas.drawText(s, x, y, text)
        }

        // HP — heart icons, "LIFE" below
        drawHearts(canvas, lx + 6f, 24f, 14f)
        caption("LIFE", lx, 39f)

        // MANA — blue crystal + bar, "MAGIC (MANA)" below
        drawManaCrystal(canvas, lx, 54f)
        drawManaBar(canvas, lx + 14f, 50f, 96f)
        caption("MAGIC", lx, 67f)

        // LEVEL — star sigil + green bar, "LEVEL n" below
        text.textAlign = Paint.Align.LEFT
        text.textSize = 13f
        text.color = 0xFF8FE08F.toInt()
        canvas.drawText("★", lx - 2f, 86f, text)
        stroke.color = 0xFF8FE08F.toInt()
        stroke.strokeWidth = 2f
        canvas.drawRect(lx + 14f, 78f, lx + 110f, 86f, stroke)
        fill.color = 0xFF8FE08F.toInt()
        canvas.drawRect(lx + 15f, 79f, lx + 15f + 94f * Loot.levelProgress(engine.stats.xp), 85f, fill)
        caption("LEVEL ${engine.stats.level} (XP)", lx, 97f)

        // WANDER POWER — boot sigil + pips, label below
        val tier = Loot.wanderTier(engine.steps.sessionSteps)
        val wfrac = (engine.steps.sessionSteps % Loot.STEPS_PER_TIER).toFloat() / Loot.STEPS_PER_TIER
        drawBitmapAt(canvas, sprites.get("boots"), lx + 4f, 110f, 14f)
        for (i in 0 until Loot.MAX_TIER) {
            val x = lx + 14f + i * 14f
            stroke.color = 0xFFC98FFF.toInt()
            stroke.strokeWidth = 1.5f
            canvas.drawRect(x, 105f, x + 10f, 115f, stroke)
            if (i < tier || i == tier) {
                fill.color = 0xFFC98FFF.toInt()
                fill.alpha = if (i < tier) 230 else (60 + 140 * wfrac).toInt()
                val h = if (i < tier) 10f else 10f * wfrac
                canvas.drawRect(x + 1f, 115f - h, x + 9f, 114f, fill)
                fill.alpha = 255
            }
        }
        caption("WANDER POWER (walk = better loot)", lx, 127f)

        // SATCHEL — coin sigil + amount, then the labelled bank line
        drawBitmapAt(canvas, sprites.get("gem"), lx + 6f, 142f, 16f)
        text.textAlign = Paint.Align.LEFT
        text.textSize = 14f
        text.color = gold
        canvas.drawText("${engine.stats.satchelCoins}", lx + 18f, 147f, text)
        caption("COINS CARRIED", lx, 160f)
        text.textSize = 10f
        text.color = grayText
        canvas.drawText("bank ${engine.stats.bankCoins}  ·  keys ${engine.stats.keys}  ·  shards ${engine.stats.shards}", lx - 2f, 174f, text)

        // the CALLING — there is always a task (bottom-center, clipped to fit)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 13f
        text.color = if (engine.activeQuest != null) 0xFF8FE08f.toInt() else 0xFFC9C2E8.toInt()
        canvas.drawText(ellipsize(engine.currentCalling(), 600f), CX, H - 38f, text)

        // held slot (bottom-right): weapon or wonder, swipe ⇄ to switch
        val loadout = engine.heldLoadout()
        val held = loadout.getOrNull(engine.stats.heldIndex.coerceIn(0, (loadout.size - 1).coerceAtLeast(0)))
        if (held != null) {
            drawBitmapAt(canvas, sprites.get(held.sprite), W - 30f, H - 64f, 26f)
            if (!held.isWeapon) {
                text.textAlign = Paint.Align.RIGHT
                text.textSize = 10f
                text.color = gold
                canvas.drawText("x${held.count}", W - 24f, H - 46f, text)
            }
        }
        if (engine.weaponNoticeActive(now)) {
            text.textAlign = Paint.Align.RIGHT
            text.textSize = 12f
            text.color = gold
            canvas.drawText(ellipsize(engine.weaponNotice, 300f), W - 24f, H - 88f, text)
        }
        // lucky charm shimmer
        if (engine.luckyActive()) {
            text.textAlign = Paint.Align.RIGHT
            text.textSize = 11f
            text.color = gold
            canvas.drawText("LUCKY!", W - 24f, H - 102f, text)
        }

        // golden hour banner
        if (engine.goldenActive) {
            val left = (engine.goldenEndsMs - now) / 1000
            text.textAlign = Paint.Align.CENTER
            text.textSize = 14f
            text.color = gold
            val blink = if ((now / 400) % 2 == 0L) "✦" else "·"
            canvas.drawText("$blink GILDED HOUR ${left / 60}:${(left % 60).toString().padStart(2, '0')} $blink", CX, 64f, text)
        }

        // duel chip (bottom-right)
        if (engine.duelActive()) {
            text.textAlign = Paint.Align.RIGHT
            text.textSize = 12f
            text.color = 0xFFFF9A3D.toInt()
            canvas.drawText("⚔ ${engine.stats.duelCode} ${engine.stats.duelScore}pts ${engine.duelTimeLeft()}", W - 24f, H - 22f, text)
        }

        // night marker — a drawn crescent (no font glyph to tofu)
        if (engine.night) {
            val mx = W - 30f
            val my = 66f
            fill.color = 0xFF9AD1FF.toInt()
            canvas.drawCircle(mx, my, 7f, fill)
            fill.color = Color.BLACK
            canvas.drawCircle(mx + 3f, my - 2f, 6f, fill)
        }

        // Hearth: hall size + floor (stairs change the floor via barometer)
        if (engine.spawner.indoor) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = 11f
            text.color = cyan
            val hallFt = (engine.hearthHalfM * 2 * 3.28).toInt()
            val floorLabel = if (engine.floors.hasBarometer) {
                when {
                    engine.currentFloor > 0 -> " · floor +${engine.currentFloor}"
                    engine.currentFloor < 0 -> " · cellar ${engine.currentFloor}"
                    else -> ""
                }
            } else ""
            canvas.drawText("⌂ ${hallFt}ft hall$floorLabel", CX, H - 22f, text)
        }
    }

    private fun drawRadar(canvas: Canvas, now: Long) {
        val cx = 556f   // pulled in from the lens edge (was 574, clipped)
        val cy = 74f
        val r = 50f
        val indoor = engine.spawner.indoor
        val floor = engine.currentFloor
        // Indoors the radar auto-scales to the hall; outdoors it uses the zoom setting.
        val range = if (indoor) (engine.hearthHalfM * 1.4).coerceAtLeast(12.0).toFloat()
        else engine.stats.radarRangeM.toFloat()
        fill.color = 0x99080614.toInt()
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = 0xFF4A3F7A.toInt()
        stroke.strokeWidth = 2f
        canvas.drawCircle(cx, cy, r, stroke)
        stroke.strokeWidth = 1f
        stroke.color = 0x554A3F7A
        canvas.drawCircle(cx, cy, r / 2, stroke)
        if (indoor) {
            // hall boundary ring
            val boundR = (engine.hearthHalfM / range * (r - 5)).toFloat().coerceAtMost(r - 2f)
            stroke.color = 0x667FE7E0
            canvas.drawCircle(cx, cy, boundR, stroke)
        }
        // sweep
        val sweep = ((now / 9) % 360).toFloat()
        val sweepRad = Math.toRadians(sweep.toDouble())
        stroke.color = 0x3340FF80
        stroke.strokeWidth = 2f
        canvas.drawLine(cx, cy, cx + (sin(sweepRad) * r).toFloat(), cy - (cos(sweepRad) * r).toFloat(), stroke)

        val yaw = engine.head.yawDeg
        fun blip(
            bearing: Double, dist: Double, color: Int,
            big: Boolean = false, blink: Boolean = false, floorDelta: Int = 0
        ) {
            if (dist > range) return
            if (blink && (now / 260) % 2 == 0L) return
            val rel = Math.toRadians(Geo.wrapDeg(bearing - yaw).toDouble())
            val rr = (dist / range * (r - 5)).toFloat()
            val bx = cx + (sin(rel) * rr).toFloat()
            val by = cy - (cos(rel) * rr).toFloat()
            if (floorDelta != 0) {
                // treasure on another storey: hollow blip + stair arrow
                stroke.color = color
                stroke.strokeWidth = 1.5f
                canvas.drawCircle(bx, by, 3f, stroke)
                text.textSize = 9f
                text.textAlign = Paint.Align.CENTER
                text.color = color
                canvas.drawText(if (floorDelta > 0) "▲" else "▼", bx, by - 4f, text)
            } else {
                fill.color = color
                canvas.drawCircle(bx, by, if (big) 4f else 2.6f, fill)
            }
        }
        // footprints: faint trail dots
        val nowWall = System.currentTimeMillis()
        for (t in engine.spawner.traces) {
            if (t.distanceM > range) continue
            val age = (nowWall - t.bornMs).toFloat() / 150_000f
            if (age >= 1f) continue
            val rel = Math.toRadians(Geo.wrapDeg(t.bearingDeg - yaw).toDouble())
            val rr = (t.distanceM / range * (r - 5)).toFloat()
            fill.color = 0xFFCC9A55.toInt()
            fill.alpha = ((1f - age) * 120).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx + (sin(rel) * rr).toFloat(), cy - (cos(rel) * rr).toFloat(), 1.5f, fill)
            fill.alpha = 255
        }
        // items, coloured by IMPORTANCE TIER
        for (item in engine.spawner.items) {
            blip(
                item.bearingDeg, item.distanceM,
                when {
                    item.droppedCoins > 0 -> HIGH_COLOR
                    item.shiny -> 0xFFB9F2FF.toInt()
                    isHighItem(item) -> HIGH_COLOR
                    else -> MEDIUM_COLOR
                },
                big = isHighItem(item) || item.droppedCoins > 0,
                blink = item.droppedCoins > 0,
                floorDelta = if (indoor) item.floor - floor else 0
            )
        }
        engine.spawner.shrine?.let {
            blip(it.bearingDeg, it.distanceM, cyan, big = true,
                floorDelta = if (indoor) it.floor - floor else 0)
        }
        // anchored places, Finn, the lever
        for (p in engine.worldPlaces()) {
            blip(
                p.bearingDeg, p.distanceM,
                when (p.type) {
                    PlaceType.POOL -> 0xFF6FE0D8.toInt()
                    PlaceType.GUILD -> 0xFFE8C66A.toInt()
                    PlaceType.BEACON -> if (p.lit) gold else 0xFF8A82B0.toInt()
                },
                big = true,
                blink = p.type == PlaceType.BEACON && !p.lit && engine.stats.shards >= 3
            )
        }
        engine.spawner.npc?.let { blip(it.bearingDeg, it.distanceM, 0xFF8FE08F.toInt(), big = true, blink = true) }
        engine.spawner.lever?.let { blip(it.bearingDeg, it.distanceM, 0xFFC98FFF.toInt()) }
        engine.spawner.sign?.let { blip(it.bearingDeg, it.distanceM, 0xFFE8D9A0.toInt()) }
        engine.spawner.portal?.let { blip(it.bearingDeg, it.distanceM, 0xFFC98FFF.toInt(), big = true, blink = true) }
        for (c in engine.spawner.critters) {
            blip(c.bearingDeg, c.distanceM, 0xFFCC4444.toInt(),
                floorDelta = if (indoor) c.floor - floor else 0)
        }
        for (h in engine.spawner.herbs) {
            blip(h.bearingDeg, h.distanceM,
                if (h.type.rare) 0xFFFFB7E0.toInt() else 0xFF6FA86F.toInt(),
                floorDelta = if (indoor) h.floor - floor else 0)
        }
        for (m in engine.spawner.monsters) {
            val thief = m.stolenCoins > 0L
            blip(
                m.bearingDeg, m.distanceM,
                if (thief) 0xFFFF9A3D.toInt() else red,
                big = true,
                blink = m.state == MonsterState.HUNT || thief,
                floorDelta = if (indoor) m.floor - floor else 0
            )
        }
        // player chevron (heading-up)
        fill.color = Color.WHITE
        canvas.drawCircle(cx, cy, 2.5f, fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 2f
        canvas.drawLine(cx - 4f, cy + 4f, cx, cy - 6f, stroke)
        canvas.drawLine(cx + 4f, cy + 4f, cx, cy - 6f, stroke)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 10f
        text.color = grayText
        canvas.drawText(
            if (indoor) "${(range * 3.28).toInt()}ft" else "${range.toInt()}m",
            cx, cy + r + 12f, text
        )
    }

    // ------------------------------------------------------------------
    //  Battle — JRPG confrontation
    // ------------------------------------------------------------------

    private fun messageBox(canvas: Canvas, msg: String) {
        // the classic blue JRPG window
        fill.color = 0xF01A2A6B.toInt()
        dstRect.set(70f, H - 92f, W - 70f, H - 36f)
        canvas.drawRoundRect(dstRect, 8f, 8f, fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 2f
        canvas.drawRoundRect(dstRect, 8f, 8f, stroke)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 14f
        text.color = Color.WHITE
        canvas.drawText(msg, CX, H - 60f, text)
    }

    private fun drawBattle(canvas: Canvas, now: Long) {
        val b = engine.battle
        val m = b.monster
        when (b.phase) {
            Battle.Phase.INTRO -> {
                // the scare IS the battle intro
                if ((now / 90) % 2 == 0L) canvas.drawColor(0x66CC1111)
                val name = m?.type?.scareSprite ?: return
                val t = (now - b.phaseStartMs).toFloat() / Battle.INTRO_MS
                val zoom = 200f + 320f * min(1f, t * 1.6f)
                drawBitmapAt(
                    canvas, sprites.get(name),
                    CX + rng.nextInt(-12, 13), CY - 30f + rng.nextInt(-12, 13), zoom
                )
                messageBox(canvas, b.message)
            }
            Battle.Phase.FIGHT -> {
                if (m == null) return
                // hurt flash
                if (now - b.hurtFlashMs < 220L) canvas.drawColor(0x55CC1111)
                if (now - b.starfallFlashMs < 500L) canvas.drawColor(0x33FFD75E)
                // the foe
                var size = 200f + sin(now / 300.0).toFloat() * 8f
                var my = CY - 30f
                var mx = CX
                if (b.telegraphing) {
                    size *= 1.18f
                    mx += rng.nextInt(-5, 6)
                    my += rng.nextInt(-5, 6)
                    fill.color = red
                    fill.alpha = 60 + (sin(now / 90.0) * 40).toInt()
                    canvas.drawCircle(CX, my, size * 0.62f, fill)
                    fill.alpha = 255

                    // ---- the DUCK! timer — drains as the lunge comes in ----
                    val frac = b.duckTimeFraction(now)
                    val dw = 260f
                    val dx = CX - dw / 2
                    val dy = CY + 96f
                    text.textAlign = Paint.Align.CENTER
                    text.textSize = 24f
                    text.color = if ((now / 140) % 2 == 0L) red else 0xFFFFD75E.toInt()
                    canvas.drawText("!! MOVE TO DUCK !!", CX, dy - 10f, text)
                    stroke.color = Color.WHITE
                    stroke.strokeWidth = 2f
                    canvas.drawRect(dx, dy, dx + dw, dy + 14f, stroke)
                    fill.color = when {
                        frac > 0.5f -> 0xFFFFD75E.toInt()
                        frac > 0.25f -> 0xFFFF9A3D.toInt()
                        else -> red
                    }
                    canvas.drawRect(dx + 1f, dy + 1f, dx + 1f + (dw - 2f) * frac, dy + 13f, fill)
                }
                if (m.isBoss) {
                    fill.color = 0x33C98FFF
                    canvas.drawCircle(CX, my, size * 0.7f, fill)
                }
                drawBitmapAt(canvas, sprites.get(m.type.sprite), mx, my, size)
                // swing slash
                if (now - b.swingFlashMs < 160L) {
                    stroke.color = if (now - b.critFlashMs < 300L) gold else Color.WHITE
                    stroke.strokeWidth = 5f
                    val o = (now - b.swingFlashMs).toFloat() / 160f * 60f
                    canvas.drawLine(CX - 90f + o, my - 80f + o, CX + 30f + o, my + 40f + o, stroke)
                }
                // foe name + HP pips
                text.textAlign = Paint.Align.CENTER
                text.textSize = 16f
                text.color = if (m.isBoss) 0xFFC98FFF.toInt() else Color.WHITE
                canvas.drawText(
                    (if (m.isBoss) "SHARDKEEPER " else "") + m.type.label, CX, 56f, text
                )
                val pipW = 14f
                val total = b.monsterMaxHp * pipW
                for (i in 0 until b.monsterMaxHp) {
                    val x = CX - total / 2 + i * pipW
                    fill.color = if (i < b.monsterHp) red else 0xFF3A2430.toInt()
                    canvas.drawRect(x, 66f, x + pipW - 3f, 76f, fill)
                }
                // player vitals
                drawHearts(canvas, 24f, 26f, 18f)
                drawManaBar(canvas, 16f, 40f, 110f)
                val weapon = Gear.weaponOf(engine.stats)
                drawBitmapAt(canvas, sprites.get(weapon.sprite), W - 30f, 34f, 28f)
                text.textAlign = Paint.Align.RIGHT
                text.textSize = 10f
                text.color = grayText
                canvas.drawText(weapon.label, W - 14f, 56f, text)
                // STAR GAUGE
                val gw = 150f
                stroke.color = gold
                stroke.strokeWidth = 2f
                canvas.drawRect(CX - gw / 2, H - 116f, CX + gw / 2, H - 106f, stroke)
                fill.color = gold
                canvas.drawRect(CX - gw / 2 + 1f, H - 115f, CX - gw / 2 + 1f + (gw - 2f) * b.starGauge, H - 107f, fill)
                if (b.starGauge >= 1f && (now / 300) % 2 == 0L) {
                    text.textAlign = Paint.Align.CENTER
                    text.textSize = 12f
                    text.color = gold
                    canvas.drawText("★ HOLD to STARFALL ★", CX, H - 122f, text)
                }
                // damage pops
                text.textAlign = Paint.Align.CENTER
                for ((i, p) in b.pops.withIndex()) {
                    val age = (now - p.bornMs).toFloat() / 1400f
                    text.textSize = if (p.big) 24f else 17f
                    text.color = p.color
                    text.alpha = ((1f - age) * 255).toInt().coerceIn(0, 255)
                    canvas.drawText(p.text, CX + (i % 3 - 1) * 70f, my - 90f - age * 40f, text)
                }
                text.alpha = 255
                messageBox(canvas, b.message)
                text.textSize = 10f
                text.color = grayText
                text.textAlign = Paint.Align.CENTER
                canvas.drawText("move head/tap=attack · move during lunge=duck · ⇄=weapon · hold=starfall · double-tap=flee", CX, H - 24f, text)
            }
            Battle.Phase.VICTORY -> {
                if (m != null) {
                    drawBitmapAt(canvas, sprites.get(m.type.sprite), CX, CY - 60f, 120f, silhouette)
                }
                text.textAlign = Paint.Align.CENTER
                text.textSize = 34f
                text.color = gold
                canvas.drawText("VICTORY!", CX, CY - 10f, text)
                text.textSize = 14f
                text.color = Color.WHITE
                canvas.drawText("The Gloom thins a little...", CX, CY + 20f, text)
                drawHearts(canvas, CX - engine.stats.maxHearts * 10f, CY + 50f, 18f)
            }
            Battle.Phase.NONE -> Unit
        }
    }

    /** Air-flow streaks — you SEE the gesture the gyro felt. */
    private fun drawWaveFx(canvas: Canvas, now: Long) {
        val age = now - engine.waveFxMs
        if (age !in 0..450) return
        val t = age / 450f
        val sweep = -120f + t * 880f   // streaks race across the view
        stroke.strokeWidth = 3f
        for (i in 0 until 4) {
            val y = 140f + i * 60f + sin(i * 1.7).toFloat() * 18f
            stroke.color = Color.WHITE
            stroke.alpha = ((1f - t) * (160 - i * 25)).toInt().coerceIn(0, 255)
            val x0 = sweep + i * 36f
            // a curved whoosh: three short joined arcs
            canvas.drawLine(x0, y, x0 + 80f, y - 10f, stroke)
            canvas.drawLine(x0 + 80f, y - 10f, x0 + 150f, y - 6f, stroke)
            canvas.drawLine(x0 + 30f, y + 12f, x0 + 110f, y + 4f, stroke)
        }
        stroke.alpha = 255
    }

    /** How to Play — defines every HUD meter icon and the world's colors. */
    private fun drawGuide(canvas: Canvas) {
        panel(canvas, "HOW TO PLAY")
        text.textAlign = Paint.Align.LEFT
        var y = 96f
        val lh = 26f

        fun iconRow(sprite: String?, dot: Int, title: String, desc: String) {
            if (sprite != null) drawBitmapAt(canvas, sprites.get(sprite), 92f, y - 4f, 20f)
            else if (dot != 0) { fill.color = dot; canvas.drawCircle(92f, y - 4f, 7f, fill) }
            text.textSize = 13f
            text.color = Color.WHITE
            canvas.drawText(title, 110f, y - 6f, text)
            text.textSize = 11f
            text.color = grayText
            canvas.drawText(desc, 110f, y + 8f, text)
            y += lh
        }

        // --- the meters (top-left of the play screen) ---
        text.textSize = 12f
        text.color = gold
        canvas.drawText("YOUR METERS (top-left while playing)", 84f, y, text)
        y += 20f
        iconRow("heart", 0, "Hearts", "your life — lose them all and you become a ghost")
        // mana crystal drawn inline
        run {
            drawManaCrystal(canvas, 92f, y - 4f)
            text.textSize = 13f; text.color = Color.WHITE
            canvas.drawText("Mana (blue bar)", 110f, y - 6f, text)
            text.textSize = 11f; text.color = grayText
            canvas.drawText("magic for stronger weapons — refills as you walk", 110f, y + 8f, text)
            y += lh
        }
        run {
            text.textSize = 16f; text.color = 0xFF8FE08F.toInt(); canvas.drawText("★", 86f, y, text)
            text.textSize = 13f; text.color = Color.WHITE
            canvas.drawText("Star bar", 110f, y - 6f, text)
            text.textSize = 11f; text.color = grayText
            canvas.drawText("your level — fills with XP from everything you do", 110f, y + 8f, text)
            y += lh
        }
        iconRow("boots", 0, "Boot pips", "Wander Power — the more you walk, the better the loot")
        iconRow("gem", 0, "Coins", "what you carry; bank at shrines so foes can't steal them")
        text.textSize = 11f; text.color = grayText
        canvas.drawText("bank = coins kept safe · keys = open vaults · shards = trophies", 84f, y, text)
        y += lh

        // --- the world's color language ---
        text.textSize = 12f; text.color = gold
        canvas.drawText("OUT IN THE WORLD", 84f, y, text)
        y += 20f
        iconRow(null, 0xFF6FE07A.toInt(), "Green glow", "herbs & flowers to gather (brew into potions)")
        iconRow(null, 0xFF5BA8FF.toInt(), "Blue glow", "treasures — gems, scrolls, keys")
        iconRow(null, 0xFFFFB02E.toInt(), "Orange glow", "great prizes — vaults & crowns")
        iconRow(null, red, "RED crosshair", "a CREATURE, not an object — tap to fight it")
        iconRow(null, gold, "Gold beacon", "your marked destination — just walk toward it")

        hint(canvas, "tap or double-tap = back to menu")
    }

    private fun drawCalibrate(canvas: Canvas, now: Long) {
        var y = panel(canvas, "CALIBRATE THE WANDERER") + 8f
        text.textAlign = Paint.Align.CENTER

        // ---- the motion gesture: head-shake, pure gyro. No camera, ever. ----
        text.textSize = 13f
        text.color = 0xFF8FE08F.toInt()
        canvas.drawText("— HEAD-SHAKE (gyro · no camera) —", CX, y, text)
        y += 17f
        text.textSize = 11f
        text.color = grayText
        canvas.drawText("in combat: ANY head flick = strike · flick during a lunge = duck", CX, y, text)
        y += 22f

        // live head-motion meter (0..400 °/s) with trigger markers
        val headDps = engine.head.angularSpeedDps
        val meterW = 320f
        val mx = CX - meterW / 2
        stroke.color = panelBorder
        stroke.strokeWidth = 2f
        canvas.drawRect(mx, y, mx + meterW, y + 22f, stroke)
        val level = headDps.coerceIn(0f, 400f) / 400f
        fill.color = if (headDps >= engine.stats.shakeDps) 0xFF8FE08F.toInt() else 0xFF6FB8FF.toInt()
        canvas.drawRect(mx + 1f, y + 1f, mx + 1f + (meterW - 2f) * level, y + 21f, fill)
        val thrX = mx + meterW * (engine.stats.shakeDps.coerceIn(0f, 400f) / 400f)
        stroke.color = gold
        canvas.drawLine(thrX, y - 4f, thrX, y + 26f, stroke)
        if (engine.suggestedThreshold > 0f && engine.calibWaves >= 2) {
            val sX = mx + meterW * (engine.suggestedThreshold.coerceIn(0f, 400f) / 400f)
            stroke.color = 0xFF8FE08F.toInt()
            canvas.drawLine(sX, y - 4f, sX, y + 26f, stroke)
        }
        y += 40f
        text.color = Color.WHITE
        text.textSize = 13f
        canvas.drawText("flicks felt: ${engine.calibWaves}", CX, y, text)
        y += 20f
        text.textSize = 11f
        text.color = grayText
        canvas.drawText(
            "head ${"%.0f".format(headDps)}°/s   ·   trigger ${"%.0f".format(engine.stats.shakeDps)}°/s" +
                if (engine.calibWaves >= 2) "  →  suggested ${"%.0f".format(engine.suggestedThreshold)}°/s" else "",
            CX, y, text
        )
        y += 30f

        // ---- compass: the other half of "calibrating the player" ----
        text.color = 0xFF9AD1FF.toInt()
        canvas.drawText("— COMPASS —  facing ${"%.0f".format(engine.head.yawDeg)}°", CX, y, text)
        canvas.drawText("triple-tap while roaming to reset, then walk straight", CX, y + 15f, text)
        hint(canvas, "flick 3+ times · tap = save · double-tap = back")
    }

    private fun drawGhostOverlay(canvas: Canvas, now: Long) {
        canvas.drawColor(0x553A66AA)
        val bob = sin(now / 500.0).toFloat() * 6f
        drawBitmapAt(canvas, sprites.get("ghost"), 60f, 120f + bob, 44f)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 14f
        text.color = 0xFF9AD1FF.toInt()
        if ((now / 700) % 2 == 0L) {
            canvas.drawText("You are a GHOST — reach a Healing Pool", CX, 96f, text)
        }
    }

    // ------------------------------------------------------------------
    //  Dialog / Guild / Quests / Intro
    // ------------------------------------------------------------------

    private fun drawDialog(canvas: Canvas, now: Long) {
        // ---- a wandering villager (no quest) — a held, readable chat ----
        val villager = engine.dialogVillager
        if (villager != null) {
            panel(canvas, "A TRAVELER")
            val vbob = sin(now / 400.0).toFloat() * 4f
            drawBitmapAt(canvas, sprites.get("npc"), 150f, 210f + vbob, 110f)
            text.textAlign = Paint.Align.LEFT
            var y = 140f
            text.textSize = 14f
            text.color = Color.WHITE
            for (ln in wrapText(villager, 360f)) { canvas.drawText(ln, 226f, y, text); y += 22f }
            engine.dialogVillagerGift?.let {
                text.color = gold; text.textSize = 12f
                y += 6f
                for (ln in wrapText(it, 360f)) { canvas.drawText(ln, 226f, y, text); y += 18f }
            }
            hint(canvas, "tap or double-tap = farewell")
            return
        }
        panel(canvas, "KEEPER FINN")
        val bob = sin(now / 400.0).toFloat() * 4f
        drawBitmapAt(canvas, sprites.get("npc"), 150f, 200f + bob, 110f)
        text.textAlign = Paint.Align.LEFT
        text.textSize = 13f
        text.color = 0xFF8FE08F.toInt()
        canvas.drawText(engine.dialogGreeting, 230f, 130f, text)
        text.color = Color.WHITE
        val quest = engine.dialogQuest
        if (quest != null) {
            var y = 158f
            for (ln in wrapText(quest.line, 380f)) {
                canvas.drawText(ln, 230f, y, text); y += 20f
            }
            text.color = gold
            canvas.drawText("Reward: ${quest.rewardCoins} coins" + if (quest.rewardShard) " + SHARD" else "", 230f, y + 14f, text)
        }
        text.textSize = 12f
        text.color = 0xFF9AD1FF.toInt()
        text.textAlign = Paint.Align.CENTER
        for ((i, ln) in wrapText(Lore.chapterStory(engine.stats.chapter), 500f).withIndex()) {
            canvas.drawText(ln, CX, H - 92f + i * 16f, text)
        }
        hint(canvas, "tap = accept quest · double-tap = not now")
    }

    private fun drawGuild(canvas: Canvas) {
        var y = panel(canvas, "LANTERN GUILD") + 6f
        text.textAlign = Paint.Align.CENTER
        text.textSize = 12f
        text.color = grayText
        canvas.drawText("\"Spend banked coin, Wanderer — ${engine.stats.bankCoins} on the ledger\"", CX, y, text)
        y += 28f
        val rows = Gear.shopFor(engine.stats)
        if (rows.isEmpty()) {
            text.color = Color.WHITE
            text.textSize = 15f
            canvas.drawText("You carry our finest already.", CX, y + 30f, text)
        }
        for ((i, row) in rows.withIndex()) {
            val selected = i == engine.guildIndex
            text.textAlign = Paint.Align.LEFT
            text.textSize = if (selected) 15f else 13f
            text.color = if (selected) Color.WHITE else grayText
            canvas.drawText((if (selected) "► " else "  ") + row.label, 95f, y, text)
            text.textAlign = Paint.Align.RIGHT
            text.color = if (engine.stats.bankCoins >= row.price) gold else 0xFF8A6A4A.toInt()
            canvas.drawText("${row.price}✦", W - 95f, y, text)
            if (selected) {
                text.textAlign = Paint.Align.LEFT
                text.textSize = 11f
                text.color = 0xFF9AD1FF.toInt()
                canvas.drawText("   " + row.desc, 95f, y + 16f, text)
                y += 16f
            }
            y += 26f
        }
        hint(canvas, "swipe ⇅ = browse · tap = buy · double-tap = leave")
    }

    private fun drawQuests(canvas: Canvas) {
        var y = panel(canvas, "QUEST & CHRONICLE") + 8f
        text.textAlign = Paint.Align.CENTER
        text.textSize = 15f
        text.color = 0xFFC98FFF.toInt()
        canvas.drawText(Lore.chapterTitle(engine.stats.chapter), CX, y, text)
        y += 30f
        val q = engine.activeQuest
        text.textSize = 14f
        if (q != null) {
            text.color = 0xFF8FE08F.toInt()
            canvas.drawText("◆ " + q.describe(), CX, y, text)
            y += 22f
            text.textSize = 11f
            text.color = grayText
            canvas.drawText("Reward: ${q.rewardCoins} coins" + if (q.rewardShard) " + CROWN SHARD" else "", CX, y, text)
        } else {
            text.color = grayText
            canvas.drawText("No guild quest — find Keeper Finn on the roads", CX, y, text)
        }
        y += 36f
        val s = engine.stats
        val rows = listOf(
            "Crown Shards" to "★ ${s.shards}",
            "Battles won" to "${s.battlesWon}",
            "Foes bested afield" to "${s.crittersSlain}",
            "Bestiary discovered" to "${bestiaryCount(s)} / 30",
            "Quests done" to "${s.questsDone}",
            "Wander Keys" to "⚷ ${s.keys}",
            "Gear" to "${Gear.weaponOf(s).label} · ${Gear.armorOf(s).label}" + if (s.bootsOwned) " · Boots" else ""
        )
        text.textSize = 13f
        for ((k, v) in rows) {
            text.textAlign = Paint.Align.LEFT
            text.color = grayText
            canvas.drawText(k, 100f, y, text)
            text.textAlign = Paint.Align.RIGHT
            text.color = Color.WHITE
            canvas.drawText(ellipsize(v, 300f), W - 100f, y, text)
            y += 20f
        }
        // the alchemy pouch
        val pouch = com.tropicalstream.wanderquest.game.HerbType.entries.joinToString("  ") {
            "${it.label.take(4)}:${s.herbs[it.name] ?: 0}"
        }
        text.textAlign = Paint.Align.CENTER
        text.textSize = 11f
        text.color = 0xFF9FE0A8.toInt()
        canvas.drawText("Pouch: $pouch", CX, y + 2f, text)
        val wonders = com.tropicalstream.wanderquest.game.SpecialItem.entries
            .filter { (s.specialItems[it.name] ?: 0L) > 0 }
            .joinToString("  ") { "${it.label} x${s.specialItems[it.name]}" }
        if (wonders.isNotEmpty()) {
            text.color = gold
            canvas.drawText("Wonders: $wonders", CX, y + 18f, text)
        }
        hint(canvas, "tap or double-tap = back")
    }

    private fun drawIntro(canvas: Canvas, now: Long) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = 20f
        text.letterSpacing = 0.1f
        text.color = gold
        canvas.drawText("THE SUNDERED CROWN", CX, 80f, text)
        text.letterSpacing = 0f
        val page = Lore.intro.getOrNull(engine.introPage) ?: return
        text.textSize = 15f
        text.color = Color.WHITE
        var y = 150f
        for (line in page.split("\n")) {
            canvas.drawText(line, CX, y, text)
            y += 26f
        }
        if ((now / 600) % 2 == 0L) {
            text.textSize = 12f
            text.color = grayText
            canvas.drawText("tap to continue (${engine.introPage + 1}/${Lore.intro.size})", CX, H - 60f, text)
        }
    }

    // ------------------------------------------------------------------
    //  Scare
    // ------------------------------------------------------------------

    private fun drawScare(canvas: Canvas, now: Long) {
        val t = (now - engine.scareStartMs).toFloat() / GameEngine.SCARE_DURATION_MS
        // red strobe flash early on
        if (t < 0.45f && (now / 90) % 2 == 0L) {
            canvas.drawColor(0x66CC1111)
        }
        val name = engine.scareMonster?.scareSprite ?: return
        val zoom = 240f + 340f * min(1f, t * 2.2f)
        val sx = CX + rng.nextInt(-14, 15)
        val sy = CY + rng.nextInt(-14, 15)
        drawBitmapAt(canvas, sprites.get(name), sx, sy, zoom)
        if (t > 0.55f) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = 26f
            text.color = red
            canvas.drawText("CAUGHT!", CX, H - 70f, text)
        }
    }

    private fun drawVignette(canvas: Canvas) {
        if (vignetteShader == null) {
            vignetteShader = RadialGradient(
                CX, CY, 330f,
                intArrayOf(0x00000000, 0x00CC1111, 0xAACC1111.toInt()),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
        }
        fill.shader = vignetteShader
        fill.alpha = (engine.vignetteAlpha * 255).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, W, H, fill)
        fill.shader = null
        fill.alpha = 255
    }

    // ------------------------------------------------------------------
    //  Title + panels
    // ------------------------------------------------------------------

    private fun drawTitle(canvas: Canvas, now: Long) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = 46f
        text.letterSpacing = 0.12f
        text.color = gold
        canvas.drawText("WANDERQUEST", CX, 150f, text)
        text.letterSpacing = 0f
        text.textSize = 14f
        text.color = 0xFFC98FFF.toInt()
        canvas.drawText("the world is the dungeon", CX, 176f, text)

        // bobbing trinket row
        val names = listOf("gem", "potion", "key", "crown", "shroom", "ring")
        for ((i, n) in names.withIndex()) {
            val x = CX - 125f + i * 50f
            val y = 230f + sin(now / 380.0 + i).toFloat() * 6f
            drawBitmapAt(canvas, sprites.get(n), x, y, 34f)
        }
        // ... and what lurks
        drawBitmapAt(canvas, sprites.get("ghoul"), CX + 180f, 232f + sin(now / 300.0).toFloat() * 8f, 30f)

        if ((now / 600) % 2 == 0L) {
            text.textSize = 18f
            text.color = Color.WHITE
            canvas.drawText("tap the temple to begin", CX, 310f, text)
        }
        text.textSize = 12f
        text.color = grayText
        canvas.drawText("tap=pick up & interact · double-tap=menu / exit · ⇄=held item · LEFT tap=use item", CX, 358f, text)
        text.color = 0xFF6B6390.toInt()
        canvas.drawText("LV ${engine.stats.level}  ·  bank ${engine.stats.bankCoins}  ·  streak ${engine.stats.streakDays}d", CX, 378f, text)
        // live input trace — instantly shows what the temple pad sends
        if (engine.inputDebug.isNotEmpty()) {
            text.textSize = 10f
            text.color = 0xFF4A8A5A.toInt()
            canvas.drawText("input: ${engine.inputDebug}", CX, 430f, text)
        }
    }

    private fun panel(canvas: Canvas, title: String): Float {
        fill.color = panelBg
        dstRect.set(60f, 36f, W - 60f, H - 36f)
        canvas.drawRoundRect(dstRect, 14f, 14f, fill)
        stroke.color = panelBorder
        stroke.strokeWidth = 3f
        canvas.drawRoundRect(dstRect, 14f, 14f, stroke)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 22f
        text.color = gold
        canvas.drawText(title, CX, 72f, text)
        return 100f
    }

    private fun hint(canvas: Canvas, s: String) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = 11f
        text.color = grayText
        canvas.drawText(s, CX, H - 48f, text)
    }

    private fun drawMenu(canvas: Canvas) {
        var y = panel(canvas, "— WANDERQUEST —") + 16f
        val items = engine.menuItems()
        text.textAlign = Paint.Align.CENTER
        for ((i, label) in items.withIndex()) {
            val selected = i == engine.menuIndex
            text.textSize = if (selected) 20f else 17f
            text.color = if (selected) Color.WHITE else grayText
            canvas.drawText(if (selected) "► $label ◄" else label, CX, y, text)
            y += 34f
        }
        hint(canvas, "swipe ⇅ = move · tap = choose · double-tap = back to the hunt")
    }

    private fun drawJournal(canvas: Canvas) {
        var y = panel(canvas, "ADVENTURER'S JOURNAL") + 10f
        val s = engine.stats
        val leagues = s.totalDistanceM / 4828.0
        val rows = listOf(
            "Paces wandered" to "${s.totalSteps}",
            "Leagues traveled" to String.format("%.2f", leagues),
            "Coins banked" to "${s.bankCoins}  (satchel ${s.satchelCoins})",
            "Level" to "${s.level}   (XP ${s.xp})",
            "Trinkets found" to "${s.itemsCollected}  ·  shiny ${s.shiniesFound}",
            "Chests opened" to "${s.chestsOpened}",
            "Monsters escaped" to "${s.monstersEscaped}",
            "Times caught" to "${s.scaresSuffered}",
            "Loot taken back" to "${s.lootRecovered}",
            "Shrines banked" to "${s.shrinesBanked}",
            "Gilded hours seen" to "${s.goldenHoursSeen}",
            "Daily streak" to "${s.streakDays} days",
            "Duels" to "${s.duelWins}W - ${s.duelLosses}L"
        )
        text.textSize = 14f
        for ((k, v) in rows) {
            text.textAlign = Paint.Align.LEFT
            text.color = grayText
            canvas.drawText(k, 90f, y, text)
            text.textAlign = Paint.Align.RIGHT
            text.color = Color.WHITE
            canvas.drawText(v, W - 90f, y, text)
            y += 22f
        }
        if (engine.leaderboardUrl.isNotEmpty()) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = 11f
            text.color = cyan
            canvas.drawText("Leaderboard (phone browser): ${engine.leaderboardUrl}", CX, y + 4f, text)
        }
        hint(canvas, "tap or double-tap = back")
    }

    private fun drawGrimoire(canvas: Canvas, now: Long) {
        panel(canvas, "GRIMOIRE")
        val types = ItemType.entries
        for ((i, t) in types.withIndex()) {
            val col = i % 4
            val row = i / 4
            val x = 150f + col * 115f
            val y = 140f + row * 95f
            val entry = engine.stats.grimoire[t.name]
            val found = entry != null && entry[0] > 0
            val selected = i == engine.grimoireIndex
            if (selected) {
                stroke.color = gold
                stroke.strokeWidth = 2f
                dstRect.set(x - 34f, y - 34f, x + 34f, y + 34f)
                canvas.drawRoundRect(dstRect, 8f, 8f, stroke)
            }
            drawBitmapAt(canvas, sprites.get(t.sprite), x, y, 52f, if (found) sprite else silhouette)
            if (found && entry != null && entry[2] > 0L) {
                text.textAlign = Paint.Align.LEFT
                text.textSize = 14f
                text.color = 0xFFB9F2FF.toInt()
                canvas.drawText("✦", x + 22f, y - 18f, text)
            }
        }
        val sel = types[engine.grimoireIndex]
        val e = engine.stats.grimoire[sel.name]
        text.textAlign = Paint.Align.CENTER
        text.textSize = 15f
        if (e != null && e[0] > 0) {
            text.color = Color.WHITE
            val best = Rarity.entries[e[1].toInt().coerceIn(0, 4)].label
            val shiny = if (e[2] > 0L) " · SHINY found" else ""
            canvas.drawText("${sel.label} — found ${e[0]} · best: $best$shiny", CX, H - 80f, text)
        } else {
            text.color = grayText
            canvas.drawText("???  — keep wandering...", CX, H - 80f, text)
        }
        hint(canvas, "swipe = browse · tap = back")
    }

    private fun drawSettings(canvas: Canvas) {
        var y = panel(canvas, "SETTINGS") + 14f
        val rows = engine.settingsRows()
        for ((i, row) in rows.withIndex()) {
            val selected = i == engine.settingsIndex
            text.textSize = 14f
            text.textAlign = Paint.Align.LEFT
            text.color = if (selected) Color.WHITE else grayText
            canvas.drawText((if (selected) "► " else "  ") + row.first, 90f, y, text)
            text.textAlign = Paint.Align.RIGHT
            text.color = if (selected) gold else grayText
            canvas.drawText(row.second, W - 90f, y, text)
            y += 30f
        }
        // locked themes teaser (the unlock itch)
        val locked = GameTheme.entries.filter { !engine.stats.unlockedThemes.contains(it) }
        if (locked.isNotEmpty()) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = 11f
            text.color = 0xFF6B6390.toInt()
            canvas.drawText(
                locked.joinToString("  ·  ") { "${it.label} unlocks at LV ${it.unlockLevel}" },
                CX, y + 6f, text
            )
        }
        hint(canvas, "swipe ⇅ = choose · swipe ⇄ / tap = change · double-tap = back")
    }

    private fun drawDuel(canvas: Canvas, now: Long) {
        var y = panel(canvas, "WANDER DUEL ⚔") + 8f
        text.textAlign = Paint.Align.CENTER
        text.textSize = 12f
        text.color = grayText
        when {
            engine.duelEntryMode -> {
                text.color = Color.WHITE
                text.textSize = 15f
                canvas.drawText("Enter your rival's result code:", CX, y + 8f, text)
                val chars = engine.duelEntryChars
                val boxW = 34f
                val total = chars.size * boxW + 2 * 26f
                var x = CX - total / 2
                text.textSize = 22f
                // prefix
                text.color = grayText
                canvas.drawText("WQ-", x - 4f, y + 66f, text)
                x += 26f
                for ((i, c) in chars.withIndex()) {
                    val selected = i == engine.duelEntryCursor
                    stroke.color = if (selected) gold else panelBorder
                    stroke.strokeWidth = if (selected) 3f else 2f
                    dstRect.set(x, y + 36f, x + boxW - 6f, y + 76f)
                    canvas.drawRoundRect(dstRect, 6f, 6f, stroke)
                    text.color = if (selected) gold else Color.WHITE
                    canvas.drawText(c.toString(), x + (boxW - 6f) / 2f, y + 66f, text)
                    x += boxW
                    if (i == 4) {
                        text.color = grayText
                        canvas.drawText("-", x - 1f, y + 66f, text)
                        x += 12f
                    }
                }
                hint(canvas, "swipe ⇅ = letter · swipe ⇄ = position · tap = confirm · double-tap = cancel")
            }
            else -> {
                canvas.drawText(
                    when {
                        engine.duelActive() -> "Duel \"${engine.stats.duelCode}\" — relics ⚔ are worth 3x!"
                        engine.duelAwaitingResult() -> "Swap result codes with your rival to settle it!"
                        else -> "Pick the SAME two words as your rival to duel for 24h"
                    },
                    CX, y + 6f, text
                )
                y += 40f
                for ((i, row) in engine.duelRows().withIndex()) {
                    val selected = i == engine.duelIndex
                    text.textSize = if (selected) 17f else 15f
                    text.color = if (selected) Color.WHITE else grayText
                    canvas.drawText(if (selected) "► $row ◄" else row, CX, y, text)
                    y += 34f
                }
                if (engine.duelVerdict.isNotEmpty()) {
                    text.textSize = 15f
                    text.color = if (engine.duelVerdict.startsWith("VICTORY")) gold else red
                    canvas.drawText(engine.duelVerdict, CX, y + 14f, text)
                }
                text.textSize = 11f
                text.color = 0xFF6B6390.toInt()
                canvas.drawText("W ${engine.stats.duelWins} — L ${engine.stats.duelLosses}", CX, H - 86f, text)
                hint(canvas, "swipe ⇅ = move · swipe ⇄ = change word · tap = choose · double-tap = back")
            }
        }
    }

    // ------------------------------------------------------------------
    //  Popups + debug
    // ------------------------------------------------------------------

    private fun drawPopups(canvas: Canvas, now: Long) {
        text.textAlign = Paint.Align.CENTER
        // Each popup is laid out as a BLOCK (its own wrapped lines together),
        // stacked top-down. The whole block shares one age/alpha/rise, so
        // popups never cross-fade through each other.
        var blockTop = 128f
        for (p in engine.popups) {
            val age = (now - p.startMs).toFloat()
            val life = if (p.big) 5000f else 2400f
            val frac = (age / life).coerceIn(0f, 1f)
            val rise = 18f * frac
            val alpha = when {
                frac < 0.06f -> frac / 0.06f
                frac > 0.82f -> (1f - frac) / 0.18f
                else -> 1f
            }
            text.textSize = if (p.big) 18f else 15f
            text.color = p.color
            text.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            val lines = wrapText(p.text, 560f)
            val lineH = if (p.big) 22f else 19f
            var y = blockTop - rise
            for (ln in lines) {
                canvas.drawText(ln, CX, y, text)
                y += lineH
            }
            blockTop += lines.size * lineH + 4f   // next block below this one
        }
        text.alpha = 255
    }

    /** Truncate to one line with an ellipsis so it never runs off the lens. */
    private fun ellipsize(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var t = s
        while (t.length > 1 && text.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    /** Greedy word-wrap so long story beats never run off the lens. */
    private fun wrapText(s: String, maxW: Float): List<String> {
        if (text.measureText(s) <= maxW) return listOf(s)
        val words = s.split(" ")
        val out = ArrayList<String>()
        val line = StringBuilder()
        for (w in words) {
            val trial = if (line.isEmpty()) w else "$line $w"
            if (text.measureText(trial) <= maxW) {
                line.clear(); line.append(trial)
            } else {
                if (line.isNotEmpty()) out.add(line.toString())
                line.clear(); line.append(w)
            }
        }
        if (line.isNotEmpty()) out.add(line.toString())
        return out
    }

    private fun drawDebug(canvas: Canvas) {
        val fix = engine.lastFix
        text.textAlign = Paint.Align.LEFT
        text.textSize = 10f
        text.color = 0xFF66FF99.toInt()
        val lines = listOf(
            "yaw ${"%.1f".format(engine.head.yawDeg)} pitch ${"%.1f".format(engine.head.pitchDeg)} off ${"%.1f".format(engine.head.yawOffsetDeg)} axis ${engine.head.axisMode}",
            "fix ${fix?.let { "%.5f,%.5f v=%.1f".format(it.lat, it.lon, it.speedMps) } ?: "none"} ok=${engine.gpsOk}",
            "steps ${engine.steps.sessionSteps} (${engine.steps.source}) items ${engine.spawner.items.size} mon ${engine.spawner.monsters.size}"
        )
        for ((i, l) in lines.withIndex()) {
            canvas.drawText(l, 12f, 96f + i * 13f, text)
        }
    }
}
