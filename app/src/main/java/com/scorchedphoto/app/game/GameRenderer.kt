package com.scorchedphoto.app.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.scorchedphoto.engine.ImpactEffect
import com.scorchedphoto.engine.physics.Projectile
import com.scorchedphoto.engine.tanks.Tank
import com.scorchedphoto.engine.tanks.TankShape
import com.scorchedphoto.terrain.HeightMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws one frame onto the [GameSurfaceView]'s [Canvas]: the photo as a backdrop, a dark
 * scar over any column craters have carved lower than the original terrain, projectiles,
 * fading impact flashes, and tanks (body tilted to the local slope, with a barrel
 * indicating aim - health is shown only in the HUD's [com.scorchedphoto.app.game.hud.HealthBarRow],
 * not repeated next to each tank here). Every world-space position and size is mapped
 * through a single [WorldTransform.fit] (see its doc for why: independent x/y scale
 * factors distort launch angles and motion).
 */
class GameRenderer(
    private val context: Context,
    private val photo: Bitmap?,
    private val originalGroundY: IntArray,
    private val onBurnMessageAssigned: (tankId: Int, spokenText: String) -> Unit = { _, _ -> },
) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val craterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 40, 30, 20)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tankBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barrelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
    }
    private val projectilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val impactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 255, 160, 40) }
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fireFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true; alpha = FIRE_ALPHA }
    private val ashPilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ashSpeckPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val speechBubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val speechBubbleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val speechBubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    private val srcRect = photo?.let { Rect(0, 0, it.width, it.height) }

    // Decoded once and reused every frame - see assets/fire/, extracted from the source
    // GIF (48 frames @ 80ms) down to every other frame @ 160ms, same total loop length.
    private val fireFrames: List<Bitmap> by lazy {
        (0 until FIRE_FRAME_COUNT).map { i ->
            context.assets.open("fire/frame_%02d.png".format(i)).use { BitmapFactory.decodeStream(it) }
        }
    }

    // A tank's taunt is picked once, the first time it's drawn burning, and kept for the
    // whole burn rather than re-rolled every frame - see burnMessageFor.
    private val burnMessages = mutableMapOf<Int, BurnTaunt>()

    // Each ash pile's speck layout, generated once per tank (deterministically, from its
    // own id) and reused every frame - see ashSpecksFor. Without caching, redrawing fresh
    // random specks every frame would flicker.
    private val ashSpecks = mutableMapOf<Int, List<AshSpeck>>()

    // Tracks how long the current tank's turn has been active, purely for the
    // start-of-turn color flash below - reset (via wall-clock nanoTime, not engine dt)
    // whenever the id passed in as currentTankId changes, so it's independent of the
    // engine's own tick cadence and keeps flashing even while the engine is paused on
    // AIMING (during which GameLoopThread doesn't call engine.tick at all).
    private var flashTankId: Int? = null
    private var flashStartNanos: Long = 0L

    fun draw(
        canvas: Canvas,
        terrain: HeightMap,
        tanks: List<Tank>,
        projectiles: List<Projectile>,
        impactEffects: List<ImpactEffect>,
        currentTankId: Int?,
        firingTankId: Int? = null,
        firingMessage: String? = null,
    ) {
        canvas.drawColor(Color.BLACK)
        val transform = WorldTransform.fit(
            canvas.width.toFloat(),
            canvas.height.toFloat(),
            terrain.width.toFloat(),
            terrain.height.toFloat(),
        )

        if (photo != null && srcRect != null) {
            val dst = RectF(
                transform.screenX(0f),
                transform.screenY(0f),
                transform.screenX(terrain.width.toFloat()),
                transform.screenY(terrain.height.toFloat()),
            )
            canvas.drawBitmap(photo, srcRect, dst, backgroundPaint)
        }

        drawCraterScars(canvas, terrain, transform)
        drawHorizonLine(canvas, terrain, transform)
        drawImpactEffects(canvas, impactEffects, transform)

        for (projectile in projectiles) {
            canvas.drawCircle(
                transform.screenX(projectile.x),
                transform.screenY(projectile.y),
                PROJECTILE_RADIUS * transform.scale,
                projectilePaint,
            )
        }

        if (currentTankId != flashTankId) {
            flashTankId = currentTankId
            flashStartNanos = System.nanoTime()
        }
        val flashColor = currentTankId?.let { id ->
            turnStartFlashColor(tanks.firstOrNull { it.id == id }, (System.nanoTime() - flashStartNanos) / 1_000_000_000f)
        }

        for (tank in tanks) {
            if (tank.isAsh) {
                drawAshPile(canvas, tank, transform)
                continue
            }
            // Neither alive nor burning also covers the silent beat between the burn
            // animation ending and the death explosion (Tank.awaitingExplosion) - nothing
            // is drawn there on purpose, so the explosion reads as its own distinct event.
            if (!tank.alive && !tank.burning) continue
            val colorOverride = if (tank.id == currentTankId) flashColor else null
            drawTank(canvas, tank, terrain, transform, colorOverride)
            if (tank.burning) {
                drawBurningTank(canvas, tank, transform)
            } else if (tank.id == firingTankId && firingMessage != null) {
                drawFiringSpeechBubble(canvas, tank, transform, firingMessage)
            }
        }
    }

    /**
     * The current tank's body color while its start-of-turn flash is still playing:
     * alternates white/normal (or magenta/normal if the tank's own color is already
     * white) starting on a flash, [TURN_FLASH_COUNT] flashes total, each flash and each
     * intervening normal-color gap lasting [TURN_FLASH_SEGMENT_SECONDS] - null once the
     * flash sequence has finished (or there's no current tank), meaning "draw normally".
     */
    private fun turnStartFlashColor(tank: Tank?, elapsedSeconds: Float): Int? {
        if (tank == null || elapsedSeconds >= TURN_FLASH_TOTAL_SECONDS) return null
        val segmentIndex = (elapsedSeconds / TURN_FLASH_SEGMENT_SECONDS).toInt().coerceIn(0, TURN_FLASH_SEGMENTS - 1)
        val isFlashSegment = segmentIndex % 2 == 0
        if (!isFlashSegment) return null
        return if (tank.color == Color.WHITE) Color.MAGENTA else Color.WHITE
    }

    private fun drawCraterScars(canvas: Canvas, terrain: HeightMap, transform: WorldTransform) {
        val path = Path()
        var started = false
        for (x in 0 until terrain.width) {
            val original = originalGroundY.getOrElse(x) { terrain.groundY[x] }
            val current = terrain.groundY[x]
            if (current <= original) {
                started = false
                continue
            }
            val px = transform.screenX(x.toFloat())
            val py = transform.screenY(current.toFloat())
            if (!started) {
                path.moveTo(px, py)
                started = true
            } else {
                path.lineTo(px, py)
            }
        }
        craterPaint.strokeWidth = CRATER_STROKE_WIDTH * transform.scale
        canvas.drawPath(path, craterPaint)
    }

    private fun drawHorizonLine(canvas: Canvas, terrain: HeightMap, transform: WorldTransform) {
        val path = Path()
        var started = false
        for (x in 0 until terrain.width) {
            val groundY = terrain.groundY[x]
            val px = transform.screenX(x.toFloat())
            val py = transform.screenY(groundY.toFloat())
            if (!started) {
                path.moveTo(px, py)
                started = true
            } else {
                path.lineTo(px, py)
            }
        }
        horizonPaint.strokeWidth = HORIZON_STROKE_WIDTH * transform.scale
        canvas.drawPath(path, horizonPaint)
    }

    private fun drawImpactEffects(canvas: Canvas, impactEffects: List<ImpactEffect>, transform: WorldTransform) {
        for (impact in impactEffects) {
            if (impact.age >= IMPACT_EFFECT_LIFETIME_SECONDS) continue

            val screenX = transform.screenX(impact.x)
            val screenY = transform.screenY(impact.y)
            val maxRadius = impact.blastRadius * transform.scale

            when {
                impact.age < GROWTH_SECONDS -> {
                    // Phase 1: grow from 0 to full size over 0.5 seconds
                    val growthFraction = impact.age / GROWTH_SECONDS
                    drawGradientCircle(canvas, screenX, screenY, maxRadius * growthFraction, growthFraction)
                }
                impact.age < GROWTH_SECONDS + HOLD_SECONDS -> {
                    // Phase 2: hold at full size for 1 second
                    drawGradientCircle(canvas, screenX, screenY, maxRadius, 1f)
                }
                else -> {
                    // Phase 3: fade away over 1 second
                    val fadeFraction = (IMPACT_EFFECT_LIFETIME_SECONDS - impact.age) / FADE_SECONDS
                    drawGradientCircle(canvas, screenX, screenY, maxRadius, fadeFraction)
                }
            }
        }
        impactPaint.alpha = 255
    }

    private fun drawGradientCircle(canvas: Canvas, cx: Float, cy: Float, radius: Float, alphaMult: Float) {
        if (radius <= 0f) return

        // Draw from outside to inside for proper gradient layering: red -> yellow -> white
        val steps = 20
        for (i in steps downTo 1) {
            val fraction = i.toFloat() / steps
            val r = radius * fraction

            val (red, green, blue) = when {
                fraction > 0.66f -> {
                    // Red to yellow: interpolate green from 0 to 255
                    val colorFraction = (fraction - 0.66f) / 0.34f
                    Triple(255, (colorFraction * 200).toInt(), 0)
                }
                fraction > 0.33f -> {
                    // Yellow to white: interpolate green and blue from yellow towards white
                    val colorFraction = (fraction - 0.33f) / 0.33f
                    Triple(255, (200 + colorFraction * 55).toInt(), (colorFraction * 55).toInt())
                }
                else -> {
                    // White center, fading to yellow: already white
                    Triple(255, 255, 255)
                }
            }

            impactPaint.color = Color.argb((alphaMult * 255).toInt(), red, green, blue)
            canvas.drawCircle(cx, cy, r, impactPaint)
        }
    }

    private fun drawTank(canvas: Canvas, tank: Tank, terrain: HeightMap, transform: WorldTransform, colorOverride: Int? = null) {
        val cx = transform.screenX(tank.x)
        val cy = transform.screenY(tank.y)
        val halfWidth = TANK_HALF_WIDTH * transform.scale

        val leftX = (tank.x - SLOPE_SAMPLE_OFFSET).toInt()
        val rightX = (tank.x + SLOPE_SAMPLE_OFFSET).toInt()
        val riseTerrain = (terrain.heightAt(rightX) - terrain.heightAt(leftX)).toFloat()
        // Both the rise and run are world-space distances scaled by the same uniform
        // factor, so it cancels out of the ratio - this angle is just as valid computed
        // in world space directly, but staying in already-scaled screen units here avoids
        // a second unit system for no benefit.
        val slopeDeg = Math.toDegrees(
            atan2(riseTerrain.toDouble(), (2 * SLOPE_SAMPLE_OFFSET).toDouble()),
        ).toFloat()

        tankBodyPaint.color = colorOverride ?: tank.color
        canvas.save()
        canvas.rotate(slopeDeg, cx, cy)
        canvas.drawPath(tankBodyPath(tank.shape, cx, cy, halfWidth), tankBodyPaint)
        canvas.restore()

        // Barrel angle is an absolute aim reference, so it's drawn unrotated by slope.
        // Full-circle convention matches PhysicsStep.launchVelocity exactly - no separate
        // facing flag, cos/sin alone cover all four quadrants.
        val angleRad = Math.toRadians(tank.angleDeg.toDouble())
        val barrelLength = BARREL_LENGTH * transform.scale
        val endX = cx + (cos(angleRad) * barrelLength).toFloat()
        val endY = cy - (sin(angleRad) * barrelLength).toFloat()
        barrelPaint.strokeWidth = BARREL_STROKE_WIDTH * transform.scale
        canvas.drawLine(cx, cy, endX, endY, barrelPaint)
    }

    private fun drawBurningTank(canvas: Canvas, tank: Tank, transform: WorldTransform) {
        if (fireFrames.isEmpty()) return
        val frameIndex = ((tank.burningElapsed * 1000).toLong() / FIRE_FRAME_DURATION_MS % fireFrames.size).toInt()
        val frame = fireFrames[frameIndex]

        val cx = transform.screenX(tank.x)
        val cy = transform.screenY(tank.y)
        val halfWidth = TANK_HALF_WIDTH * transform.scale

        // Anchored low - just above the tank's base - so most of the flame overlaps the
        // tank's own body (reading as fire coming out of the tank, not floating above
        // it), sized well past the tank's own height so it's clearly visible licking up
        // past the turret. Drawn after the tank body at FIRE_ALPHA so the tank itself
        // stays visible through/around it rather than being hidden.
        val flameBottom = cy - halfWidth * 0.2f
        val flameHeight = halfWidth * FIRE_HEIGHT_MULTIPLIER
        val flameWidth = flameHeight * frame.width / frame.height
        val dst = RectF(cx - flameWidth / 2f, flameBottom - flameHeight, cx + flameWidth / 2f, flameBottom)
        canvas.drawBitmap(frame, null, dst, fireFramePaint)

        drawSpeechBubble(canvas, cx, dst.top, burnMessageFor(tank).displayText)
    }

    /** Shows a tank's pre-fire taunt - see [GameLoopThread.beginFireSequence], which picks
     * the line and passes it down through [draw] as `firingMessage` - in the same bubble
     * style and at the same height above the tank as [drawBurningTank]'s death taunt, so
     * the two read as the same kind of moment. */
    private fun drawFiringSpeechBubble(canvas: Canvas, tank: Tank, transform: WorldTransform, message: String) {
        val cx = transform.screenX(tank.x)
        val cy = transform.screenY(tank.y)
        val halfWidth = TANK_HALF_WIDTH * transform.scale
        val bubbleTailTipY = cy - halfWidth * 0.2f - halfWidth * FIRE_HEIGHT_MULTIPLIER
        drawSpeechBubble(canvas, cx, bubbleTailTipY, message)
    }

    /** The taunt a burning tank is showing - assigned once (the first time this tank is
     * drawn burning) and held for the rest of its burn, since only actively-burning tanks
     * ever reach this call. A tank dies at most once per match, so the entry is simply
     * left behind afterward - harmless, and gone once this renderer's match ends. Fires
     * [onBurnMessageAssigned] exactly once per death (inside [getOrPut]'s lambda, which
     * only ever runs on first insert) so the caller can speak it via TTS. */
    private fun burnMessageFor(tank: Tank): BurnTaunt =
        burnMessages.getOrPut(tank.id) { BURN_TAUNTS.random().also { onBurnMessageAssigned(tank.id, it.spokenText) } }

    /** What's left where a tank died - a squat mound in the tank's own color muddied
     * toward grey, with a scatter of black/grey specks on top. Permanent for the rest of
     * the match (see [Tank.isAsh]). */
    private fun drawAshPile(canvas: Canvas, tank: Tank, transform: WorldTransform) {
        val cx = transform.screenX(tank.x)
        val cy = transform.screenY(tank.y)
        val halfWidth = TANK_HALF_WIDTH * transform.scale
        val pileWidth = halfWidth * ASH_PILE_WIDTH_MULTIPLIER
        val pileHeight = halfWidth * ASH_PILE_HEIGHT_MULTIPLIER

        ashPilePaint.color = mutedAshColor(tank.color)
        // A wide, short base mound with progressively narrower, taller ones stacked
        // (overlapping) on top of it - a single flat oval read as a puddle, not a heap.
        for ((widthFraction, heightFraction, riseFraction) in ASH_MOUND_LAYERS) {
            val moundWidth = pileWidth * widthFraction
            val moundHeight = pileHeight * heightFraction
            val moundBottom = cy - pileHeight * riseFraction
            val moundRect = RectF(cx - moundWidth / 2f, moundBottom - moundHeight, cx + moundWidth / 2f, moundBottom)
            canvas.drawOval(moundRect, ashPilePaint)
        }

        for (speck in ashSpecksFor(tank)) {
            ashSpeckPaint.color = if (speck.isBlack) Color.BLACK else Color.DKGRAY
            val sx = cx + speck.nx * pileWidth / 2f
            val sy = cy + speck.ny * pileHeight
            canvas.drawCircle(sx, sy, ASH_SPECK_RADIUS * transform.scale, ashSpeckPaint)
        }
    }

    /** Blends [originalColor] toward a muddy grey rather than replacing it outright, so
     * the ash pile still visibly traces back to which tank it was. Generated once per
     * tank id and cached - see [ashSpecks] - so the speckle pattern doesn't flicker by
     * being re-randomized every frame. */
    private fun ashSpecksFor(tank: Tank): List<AshSpeck> = ashSpecks.getOrPut(tank.id) {
        val rng = kotlin.random.Random(tank.id * 7919 + 13)
        (0 until ASH_SPECK_COUNT).map {
            AshSpeck(
                nx = rng.nextFloat() * 2f - 1f,
                ny = -rng.nextFloat() * 0.7f,
                isBlack = rng.nextBoolean(),
            )
        }
    }

    private fun mutedAshColor(originalColor: Int): Int {
        fun mute(channel: Int) = (channel + ASH_GREY_LEVEL * 2) / 3
        return Color.rgb(
            mute(Color.red(originalColor)),
            mute(Color.green(originalColor)),
            mute(Color.blue(originalColor)),
        )
    }

    /** One fleck's position within an ash pile, normalized to the pile's own half-width
     * ([nx] in [-1, 1]) and height ([ny] in [-1, 0], 0 = the pile's base). */
    private data class AshSpeck(val nx: Float, val ny: Float, val isBlack: Boolean)

    private fun drawSpeechBubble(canvas: Canvas, cx: Float, tailTipY: Float, message: String) {
        val fm = speechBubbleTextPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val boxWidth = speechBubbleTextPaint.measureText(message) + BUBBLE_PADDING_X * 2f
        val boxHeight = textHeight + BUBBLE_PADDING_Y * 2f
        val boxBottom = tailTipY - BUBBLE_TAIL_HEIGHT
        val boxTop = boxBottom - boxHeight
        val rect = RectF(cx - boxWidth / 2f, boxTop, cx + boxWidth / 2f, boxBottom)

        val bubblePath = Path().apply { addRoundRect(rect, BUBBLE_CORNER_RADIUS, BUBBLE_CORNER_RADIUS, Path.Direction.CW) }
        val tailFill = Path().apply {
            moveTo(cx - BUBBLE_TAIL_WIDTH / 2f, boxBottom)
            lineTo(cx + BUBBLE_TAIL_WIDTH / 2f, boxBottom)
            lineTo(cx, tailTipY)
            close()
        }
        // Open path (no closing top edge) so the tail's outline doesn't draw a stray
        // line across the bubble's own bottom border where the two shapes meet.
        val tailOutline = Path().apply {
            moveTo(cx - BUBBLE_TAIL_WIDTH / 2f, boxBottom)
            lineTo(cx, tailTipY)
            lineTo(cx + BUBBLE_TAIL_WIDTH / 2f, boxBottom)
        }

        canvas.drawPath(tailFill, speechBubblePaint)
        canvas.drawPath(bubblePath, speechBubblePaint)
        canvas.drawPath(bubblePath, speechBubbleBorderPaint)
        canvas.drawPath(tailOutline, speechBubbleBorderPaint)

        val baselineY = (boxTop + boxBottom) / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(message, cx, baselineY, speechBubbleTextPaint)
    }

    /** Builds [shape]'s normalized outline (see [TankShape]) into a screen-space [Path]. */
    private fun tankBodyPath(shape: TankShape, cx: Float, cy: Float, halfWidth: Float): Path {
        val path = Path()
        shape.outline.forEachIndexed { index, (nx, ny) ->
            val x = cx + nx * halfWidth
            val y = cy + ny * halfWidth
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    companion object {
        private const val CRATER_STROKE_WIDTH = 10f
        private const val HORIZON_STROKE_WIDTH = 3f
        private const val PROJECTILE_RADIUS = 5f
        private const val BARREL_STROKE_WIDTH = 1.25f

        // Tank body half-width shares Tank.RADIUS with GameEngine's hit-detection radius,
        // so the visual size and the actual collision size never drift apart. The rest of
        // these scale proportionally with it (4x the old 14f-radius tuning, then halved
        // back down to 28f, halved to 14f, halved again to 7f: barrel 13->52->26->13->9.75,
        // slope sample offset 6->24->12->6). All are world-space units, scaled by
        // WorldTransform.scale like every other size in this file.
        private const val TANK_HALF_WIDTH = Tank.RADIUS
        private const val BARREL_LENGTH = 9.75f
        private const val SLOPE_SAMPLE_OFFSET = 6

        // Explosion animation phases: grow from 0 to full over 0.125s, hold for 0.25s, fade for 0.25s.
        private const val GROWTH_SECONDS = 0.125f
        private const val HOLD_SECONDS = 0.25f
        private const val FADE_SECONDS = 0.25f
        private const val IMPACT_EFFECT_LIFETIME_SECONDS = GROWTH_SECONDS + HOLD_SECONDS + FADE_SECONDS

        // Start-of-turn color flash: 4 white (or magenta, for an already-white tank)
        // flashes, each lasting 0.1s, separated by 0.1s back at the normal color.
        private const val TURN_FLASH_SEGMENT_SECONDS = 0.1f
        private const val TURN_FLASH_COUNT = 4
        private const val TURN_FLASH_SEGMENTS = TURN_FLASH_COUNT * 2
        private const val TURN_FLASH_TOTAL_SECONDS = TURN_FLASH_SEGMENT_SECONDS * TURN_FLASH_SEGMENTS

        // assets/fire/frame_00.png..frame_23.png: every other frame of the source 48-frame
        // @80ms GIF, so 24 frames @160ms reproduces the same ~3.84s loop.
        private const val FIRE_FRAME_COUNT = 24
        private const val FIRE_FRAME_DURATION_MS = 160L
        private const val FIRE_HEIGHT_MULTIPLIER = 2.2f
        private const val FIRE_ALPHA = (0.75f * 255).toInt()

        private const val ASH_PILE_WIDTH_MULTIPLIER = 1.6f
        private const val ASH_PILE_HEIGHT_MULTIPLIER = 0.7f
        // Each mound layer, base first: (width fraction of the pile's full width, height
        // fraction of the pile's full height, how far up from the base its bottom edge
        // sits, as a fraction of the pile's full height) - see drawAshPile.
        private val ASH_MOUND_LAYERS = listOf(
            Triple(1f, 0.5f, 0f),
            Triple(0.65f, 0.65f, 0.28f),
            Triple(0.35f, 0.8f, 0.5f),
        )
        private const val ASH_SPECK_COUNT = 10
        private const val ASH_SPECK_RADIUS = 1.2f
        // Mostly grey, with a hint of the tank's own color still showing through.
        private const val ASH_GREY_LEVEL = 130

        // spokenText differs from displayText only for the censored line: the bubble
        // still shows the symbols, but TTS reads a natural stand-in instead of literally
        // sounding out "hash dollar at exclamation mark".
        private data class BurnTaunt(val displayText: String, val spokenText: String = displayText)
        private val BURN_TAUNTS = listOf(
            BurnTaunt("Not again!"),
            BurnTaunt("#\$@!", spokenText = "Argh!!"),
            BurnTaunt("Ouch! That hurts!"),
            BurnTaunt("I'll get you next time!"),
            BurnTaunt("Why me?"),
        )
        private const val BUBBLE_PADDING_X = 12f
        private const val BUBBLE_PADDING_Y = 8f
        private const val BUBBLE_CORNER_RADIUS = 10f
        private const val BUBBLE_TAIL_WIDTH = 14f
        private const val BUBBLE_TAIL_HEIGHT = 10f
    }
}
