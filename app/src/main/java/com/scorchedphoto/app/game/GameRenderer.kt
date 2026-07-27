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
import com.scorchedphoto.engine.BounceEffect
import com.scorchedphoto.engine.EdgeType
import com.scorchedphoto.engine.ImpactEffect
import com.scorchedphoto.engine.MatchPhase
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
 *
 * Split into a cached **static layer** (background, crater scars, horizon line, tank
 * bodies/barrels, ash piles) and a **dynamic overlay** drawn fresh every frame on top
 * (projectiles, impact flashes, burning-tank fire/bubble, the pre-fire speech bubble) - see
 * [redrawStaticLayer]/[staticLayerDirty] doc for why: none of the static content can
 * actually change while [MatchPhase.FIRING] (verified against every mutation site in
 * `GameEngine`, including MIRV's split-child impacts, which always land during
 * `RESOLVING`), so re-clearing the canvas, redrawing the background photo, and rebuilding
 * the crater/horizon paths every single frame during a shot's flight was pure waste.
 */
class GameRenderer(
    private val context: Context,
    private val photo: Bitmap?,
    private val originalGroundY: IntArray,
    private val deathPhrases: List<String>,
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
    private val edgeMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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

    // Reused every call rather than allocated fresh - drawSpeechBubble runs every frame for
    // the full ~2s duration of every pre-fire/death taunt, so this is a hot path. Safe to
    // share: each call fully finishes drawing (paths are consumed synchronously by
    // canvas.drawPath) before the next call - possibly for a second burning tank - reuses them.
    private val speechBubbleFontMetrics = Paint.FontMetrics()
    private val speechBubbleRect = RectF()
    private val speechBubblePath = Path()
    private val speechBubbleTailFillPath = Path()
    private val speechBubbleTailOutlinePath = Path()

    // Reused every frame rather than allocated fresh - both rebuild from the full terrain
    // width unconditionally every frame, so a fresh Path() here was a real per-frame cost.
    private val craterPath = Path()
    private val horizonPath = Path()

    // Reused every frame for the same reason - see the loop above.
    private val backgroundRect = RectF()

    // Reused across every bounce-mark draw call rather than allocated fresh - see
    // drawPaddedMark/drawSpringMark, which run every frame for the ~0.25s life of each
    // wall/ceiling bounce effect.
    private val edgeMarkRect = RectF()
    private val edgeMarkPath = Path()

    // Each tank's body Path, created once per tank id and reused every frame (rebuilt via
    // .reset() since a tank's cx/cy/slope-rotation genuinely can change frame to frame) -
    // see tankBodyPathFor. Avoids allocating a fresh Path per tank per frame.
    private val tankBodyPaths = mutableMapOf<Int, Path>()

    private val srcRect = photo?.let { Rect(0, 0, it.width, it.height) }

    // Decoded once and reused every frame - see assets/fire/, extracted from the source
    // GIF (48 frames @ 80ms) down to every other frame @ 160ms, same total loop length.
    private val fireFrames: List<Bitmap> by lazy {
        (0 until FIRE_FRAME_COUNT).map { i ->
            context.assets.open("fire/frame_%02d.png".format(i)).use { BitmapFactory.decodeStream(it) }
        }
    }

    // A tank's taunt is picked once, the first time it's drawn burning, and kept for the
    // whole burn rather than re-rolled every frame - see burnMessageFor. A null value means
    // "already checked, nothing to say" (deathPhrases was empty/all-disabled) - getOrPut
    // caches that too, so it's never re-checked for the rest of this tank's burn.
    private val burnMessages = mutableMapOf<Int, String?>()

    // Each ash pile's speck layout, generated once per tank (deterministically, from its
    // own id) and reused every frame - see ashSpecksFor. Without caching, redrawing fresh
    // random specks every frame would flicker.
    private val ashSpecks = mutableMapOf<Int, List<AshSpeck>>()

    // Each ash pile's mound RectF objects, created once per tank and reused every frame - see
    // ashMoundRectsFor. Unlike ashSpecks these are screen-space (built from cx/cy, which are
    // WorldTransform-derived), and the canvas can resize mid-match (configChanges handles
    // rotation without recreating the Activity), so only the objects are cached - their
    // bounds are still updated via .set(...) every frame in drawAshPile.
    private val ashMoundRects = mutableMapOf<Int, List<RectF>>()

    // Tracks how long the current tank's turn has been active, purely for the
    // start-of-turn color flash below - reset (via wall-clock nanoTime, not engine dt)
    // whenever the id passed in as currentTankId changes, so it's independent of the
    // engine's own tick cadence and keeps flashing even while the engine is paused on
    // AIMING (during which GameLoopThread doesn't call engine.tick at all).
    private var flashTankId: Int? = null
    private var flashStartNanos: Long = 0L

    // The cached static-layer bitmap/canvas (background, crater scars, horizon line, tank
    // bodies/barrels, ash piles) plus the WorldTransform it was drawn with - recreated only
    // when the real canvas's size changes (WorldTransform depends solely on canvas size and
    // terrain size, and terrain size is fixed for a match). staticLayerDirty forces a redraw
    // whenever any of that content could have changed - see draw()'s doc for the exact
    // conditions. IMPORTANT for future maintainers: any new mutation that changes a tank's
    // drawn appearance (or the terrain) outside of MatchPhase.FIRING is already covered by
    // the phase check below, but anything that could change *during* FIRING needs to either
    // avoid doing so or explicitly set staticLayerDirty = true.
    private var staticLayerBitmap: Bitmap? = null
    private var staticLayerCanvas: Canvas? = null
    private var cachedTransform: WorldTransform? = null
    private var cachedCanvasWidth = -1
    private var cachedCanvasHeight = -1
    private var staticLayerDirty = true

    /** The [WorldTransform] most recently used to draw the world (see [ensureStaticLayer]) -
     * exposed so a caller holding the real canvas dimensions but no drawing responsibilities
     * of its own (see [GameLoopThread]) can convert world coordinates to screen coordinates
     * exactly as this frame actually rendered them, without recomputing [WorldTransform.fit]
     * a second time. */
    val currentTransform: WorldTransform
        get() = cachedTransform ?: WorldTransform(1f, 0f, 0f)

    fun draw(
        canvas: Canvas,
        terrain: HeightMap,
        tanks: List<Tank>,
        projectiles: List<Projectile>,
        impactEffects: List<ImpactEffect>,
        bounceEffects: List<BounceEffect>,
        currentTankId: Int?,
        phase: MatchPhase,
        firingTankId: Int? = null,
        firingMessage: String? = null,
    ) {
        ensureStaticLayer(canvas.width, canvas.height, terrain)
        val transform = cachedTransform!!

        if (currentTankId != flashTankId) {
            flashTankId = currentTankId
            flashStartNanos = System.nanoTime()
        }
        val flashElapsedSeconds = (System.nanoTime() - flashStartNanos) / 1_000_000_000f
        // Whether the current tank's start-of-turn flash could still be playing - driven by
        // wall-clock time, not engine ticks, so it isn't itself gated by phase (a shot fired
        // within ~0.8s of the turn starting can still be mid-flash during FIRING).
        val flashWindowActive = flashTankId != null && flashElapsedSeconds < TURN_FLASH_TOTAL_SECONDS
        val flashColor = if (flashWindowActive) {
            turnStartFlashColor(tanks.firstOrNull { it.id == flashTankId }, flashElapsedSeconds)
        } else {
            null
        }

        // Nothing the static layer draws can change while phase == FIRING - verified against
        // every mutation site in GameEngine, including MIRV's split-child impacts, which
        // always land during RESOLVING, never FIRING - so it only needs redrawing on the
        // first frame, whenever the canvas resizes, whenever phase isn't FIRING (AIMING's
        // aim/turn-flash changes, RESOLVING's impacts/falls/burns/ash), or while the
        // wall-clock turn-flash above is still active.
        if (staticLayerDirty || phase != MatchPhase.FIRING || flashWindowActive) {
            redrawStaticLayer(terrain, tanks, transform, currentTankId, flashColor)
            staticLayerDirty = false
        }
        canvas.drawBitmap(staticLayerBitmap!!, 0f, 0f, null)

        drawDynamicOverlay(canvas, tanks, projectiles, impactEffects, bounceEffects, transform, firingTankId, firingMessage)
    }

    /** (Re)creates the cached static-layer bitmap/canvas/transform whenever the real canvas's
     * size differs from what's cached, and marks the layer dirty so it's redrawn at its new
     * size on the next call - handles both the very first frame (cachedCanvasWidth starts at
     * -1, guaranteed to differ) and any later resize (e.g. rotation). */
    private fun ensureStaticLayer(canvasWidth: Int, canvasHeight: Int, terrain: HeightMap) {
        if (staticLayerBitmap != null && cachedCanvasWidth == canvasWidth && cachedCanvasHeight == canvasHeight) return
        val bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        staticLayerBitmap = bitmap
        staticLayerCanvas = Canvas(bitmap)
        cachedCanvasWidth = canvasWidth
        cachedCanvasHeight = canvasHeight
        cachedTransform = WorldTransform.fit(canvasWidth.toFloat(), canvasHeight.toFloat(), terrain.width.toFloat(), terrain.height.toFloat())
        staticLayerDirty = true
    }

    /** Draws everything that doesn't change while a shot is purely in flight - see [draw]'s
     * doc - into [staticLayerCanvas]: the background photo, crater scars, horizon line, and
     * every tank's body/barrel or ash pile. */
    private fun redrawStaticLayer(terrain: HeightMap, tanks: List<Tank>, transform: WorldTransform, currentTankId: Int?, flashColor: Int?) {
        val staticCanvas = staticLayerCanvas ?: return
        staticCanvas.drawColor(Color.BLACK)

        if (photo != null && srcRect != null) {
            backgroundRect.set(
                transform.screenX(0f),
                transform.screenY(0f),
                transform.screenX(terrain.width.toFloat()),
                transform.screenY(terrain.height.toFloat()),
            )
            staticCanvas.drawBitmap(photo, srcRect, backgroundRect, backgroundPaint)
        }

        drawCraterScars(staticCanvas, terrain, transform)
        drawHorizonLine(staticCanvas, terrain, transform)

        for (tank in tanks) {
            if (tank.isAsh) {
                drawAshPile(staticCanvas, tank, transform)
                continue
            }
            val colorOverride = if (tank.id == currentTankId) flashColor else null
            drawTank(staticCanvas, tank, terrain, transform, colorOverride)
        }
    }

    /** Draws everything that's either inherently per-frame or only ever active outside
     * [MatchPhase.FIRING] (per [draw]'s doc) directly onto the real [canvas], on top of the
     * blitted static layer: impact flashes, in-flight projectiles, burning-tank fire/bubble,
     * and the pre-fire speech bubble. */
    private fun drawDynamicOverlay(
        canvas: Canvas,
        tanks: List<Tank>,
        projectiles: List<Projectile>,
        impactEffects: List<ImpactEffect>,
        bounceEffects: List<BounceEffect>,
        transform: WorldTransform,
        firingTankId: Int?,
        firingMessage: String?,
    ) {
        drawImpactEffects(canvas, impactEffects, transform)
        drawBounceEffects(canvas, bounceEffects, transform)

        for (projectile in projectiles) {
            canvas.drawCircle(
                transform.screenX(projectile.x),
                transform.screenY(projectile.y),
                PROJECTILE_RADIUS * transform.scale,
                projectilePaint,
            )
        }

        for (tank in tanks) {
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
        craterPath.reset()
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
                craterPath.moveTo(px, py)
                started = true
            } else {
                craterPath.lineTo(px, py)
            }
        }
        craterPaint.strokeWidth = CRATER_STROKE_WIDTH * transform.scale
        canvas.drawPath(craterPath, craterPaint)
    }

    private fun drawHorizonLine(canvas: Canvas, terrain: HeightMap, transform: WorldTransform) {
        horizonPath.reset()
        var started = false
        for (x in 0 until terrain.width) {
            val groundY = terrain.groundY[x]
            val px = transform.screenX(x.toFloat())
            val py = transform.screenY(groundY.toFloat())
            if (!started) {
                horizonPath.moveTo(px, py)
                started = true
            } else {
                horizonPath.lineTo(px, py)
            }
        }
        horizonPaint.strokeWidth = HORIZON_STROKE_WIDTH * transform.scale
        canvas.drawPath(horizonPath, horizonPaint)
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

            val red = 255
            val green: Int
            val blue: Int
            when {
                fraction > 0.66f -> {
                    // Red to yellow: interpolate green from 0 to 255
                    val colorFraction = (fraction - 0.66f) / 0.34f
                    green = (colorFraction * 200).toInt()
                    blue = 0
                }
                fraction > 0.33f -> {
                    // Yellow to white: interpolate green and blue from yellow towards white
                    val colorFraction = (fraction - 0.33f) / 0.33f
                    green = (200 + colorFraction * 55).toInt()
                    blue = (colorFraction * 55).toInt()
                }
                else -> {
                    // White center, fading to yellow: already white
                    green = 255
                    blue = 255
                }
            }

            impactPaint.color = Color.argb((alphaMult * 255).toInt(), red, green, blue)
            canvas.drawCircle(cx, cy, r, impactPaint)
        }
    }

    /** Short-lived marker at a wall/ceiling bounce point - see [BounceEffect] - dispatched
     * to a per-[EdgeType] shape/color/animation below so each bounce type reads as visually
     * distinct on the game canvas. Never called for [EdgeType.NONE]/[EdgeType.BLAST_STEEL]
     * (the latter produces an [ImpactEffect] instead, drawn like a real explosion). */
    private fun drawBounceEffects(canvas: Canvas, bounceEffects: List<BounceEffect>, transform: WorldTransform) {
        for (bounce in bounceEffects) {
            if (bounce.age >= BOUNCE_EFFECT_LIFETIME_SECONDS) continue

            val screenX = transform.screenX(bounce.x)
            val screenY = transform.screenY(bounce.y)
            val lifeFraction = bounce.age / BOUNCE_EFFECT_LIFETIME_SECONDS
            val growFraction = (lifeFraction / BOUNCE_GROW_FRACTION).coerceAtMost(1f)
            val fadeAlpha = if (lifeFraction < BOUNCE_GROW_FRACTION) {
                1f
            } else {
                1f - (lifeFraction - BOUNCE_GROW_FRACTION) / (1f - BOUNCE_GROW_FRACTION)
            }
            val radius = BOUNCE_MARK_RADIUS * transform.scale

            when (bounce.edgeType) {
                EdgeType.PADDED -> drawPaddedMark(canvas, screenX, screenY, radius, growFraction, fadeAlpha)
                EdgeType.RUBBER -> drawRubberMark(canvas, screenX, screenY, radius, growFraction, fadeAlpha)
                EdgeType.SPRING -> drawSpringMark(canvas, screenX, screenY, radius, growFraction, fadeAlpha)
                EdgeType.REFLECTIVE -> drawReflectiveMark(canvas, screenX, screenY, radius, growFraction, fadeAlpha)
                EdgeType.WRAP -> drawWrapMark(canvas, screenX, screenY, radius, growFraction, fadeAlpha)
                EdgeType.NONE, EdgeType.BLAST_STEEL -> Unit
            }
        }
    }

    /** Soft green squash: starts wide-and-flat, relaxes toward round as it fades. */
    private fun drawPaddedMark(canvas: Canvas, cx: Float, cy: Float, radius: Float, growFraction: Float, alphaMult: Float) {
        edgeMarkPaint.color = Color.argb((alphaMult * 255).toInt(), 0x66, 0xBB, 0x6A)
        val squash = 1f - growFraction * 0.6f
        edgeMarkRect.set(cx - radius * 2f, cy - radius * squash, cx + radius * 2f, cy + radius * squash)
        canvas.drawOval(edgeMarkRect, edgeMarkPaint)
    }

    /** Orange starburst: 6 lines snap outward from the point, then fade. */
    private fun drawRubberMark(canvas: Canvas, cx: Float, cy: Float, radius: Float, growFraction: Float, alphaMult: Float) {
        edgeMarkPaint.color = Color.argb((alphaMult * 255).toInt(), 0xFF, 0xA7, 0x26)
        edgeMarkPaint.strokeWidth = 2f
        val length = radius * 1.5f * growFraction
        for (i in 0 until 6) {
            val angleRad = Math.toRadians((i * 60).toDouble())
            val dx = (cos(angleRad) * length).toFloat()
            val dy = (sin(angleRad) * length).toFloat()
            canvas.drawLine(cx, cy, cx + dx, cy + dy, edgeMarkPaint)
        }
    }

    /** Cyan zigzag coil that scales up from the point, then fades. */
    private fun drawSpringMark(canvas: Canvas, cx: Float, cy: Float, radius: Float, growFraction: Float, alphaMult: Float) {
        edgeMarkPaint.color = Color.argb((alphaMult * 255).toInt(), 0x29, 0xB6, 0xF6)
        edgeMarkPaint.style = Paint.Style.STROKE
        edgeMarkPaint.strokeWidth = 2f
        val size = radius * growFraction
        edgeMarkPath.reset()
        edgeMarkPath.moveTo(cx - size, cy - size)
        edgeMarkPath.lineTo(cx - size / 2f, cy + size)
        edgeMarkPath.lineTo(cx, cy - size)
        edgeMarkPath.lineTo(cx + size / 2f, cy + size)
        edgeMarkPath.lineTo(cx + size, cy - size)
        canvas.drawPath(edgeMarkPath, edgeMarkPaint)
        edgeMarkPaint.style = Paint.Style.FILL
    }

    /** White sparkle/asterisk - near-instant flash, fades quickly (a mirror glint). */
    private fun drawReflectiveMark(canvas: Canvas, cx: Float, cy: Float, radius: Float, growFraction: Float, alphaMult: Float) {
        edgeMarkPaint.color = Color.argb((alphaMult * 255).toInt(), 0xFF, 0xFF, 0xFF)
        edgeMarkPaint.strokeWidth = 2f
        canvas.drawLine(cx - radius, cy, cx + radius, cy, edgeMarkPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, edgeMarkPaint)
        val diag = radius * 0.7f
        canvas.drawLine(cx - diag, cy - diag, cx + diag, cy + diag, edgeMarkPaint)
        canvas.drawLine(cx - diag, cy + diag, cx + diag, cy - diag, edgeMarkPaint)
    }

    /** Violet stroked ring that pops (grows then shrinks) at both the exit and entry point
     * of a teleport - [BounceEffect] doesn't distinguish which end this is, so both use the
     * same simple pop animation. */
    private fun drawWrapMark(canvas: Canvas, cx: Float, cy: Float, radius: Float, growFraction: Float, alphaMult: Float) {
        edgeMarkPaint.color = Color.argb((alphaMult * 255).toInt(), 0x7E, 0x57, 0xC2)
        edgeMarkPaint.style = Paint.Style.STROKE
        edgeMarkPaint.strokeWidth = 3f
        val r = radius * (0.3f + growFraction * 0.7f)
        canvas.drawCircle(cx, cy, r, edgeMarkPaint)
        edgeMarkPaint.style = Paint.Style.FILL
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
        canvas.drawPath(tankBodyPathFor(tank, cx, cy, halfWidth), tankBodyPaint)
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

        burnMessageFor(tank)?.let { drawSpeechBubble(canvas, cx, dst.top, it) }
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
     * only ever runs on first insert) so the caller can speak it via TTS. Null when
     * [deathPhrases] is empty (every death phrase disabled or none exist) - the tank then
     * shows no bubble and says nothing, rather than falling back to some hardcoded line. */
    private fun burnMessageFor(tank: Tank): String? =
        burnMessages.getOrPut(tank.id) { deathPhrases.randomOrNull()?.also { onBurnMessageAssigned(tank.id, it) } }

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
        val moundRects = ashMoundRectsFor(tank.id)
        for ((index, layer) in ASH_MOUND_LAYERS.withIndex()) {
            val (widthFraction, heightFraction, riseFraction) = layer
            val moundWidth = pileWidth * widthFraction
            val moundHeight = pileHeight * heightFraction
            val moundBottom = cy - pileHeight * riseFraction
            val moundRect = moundRects[index]
            moundRect.set(cx - moundWidth / 2f, moundBottom - moundHeight, cx + moundWidth / 2f, moundBottom)
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

    /** This tank's 3 mound [RectF]s - created once per tank id and cached, since allocating
     * fresh ones every frame for the rest of the match (ash piles never animate) is pure
     * waste. See [ashMoundRects] doc for why only the objects, not their bounds, are cached. */
    private fun ashMoundRectsFor(tankId: Int): List<RectF> =
        ashMoundRects.getOrPut(tankId) { ASH_MOUND_LAYERS.map { RectF() } }

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
        speechBubbleTextPaint.getFontMetrics(speechBubbleFontMetrics)
        val fm = speechBubbleFontMetrics
        val textHeight = fm.descent - fm.ascent
        val boxWidth = speechBubbleTextPaint.measureText(message) + BUBBLE_PADDING_X * 2f
        val boxHeight = textHeight + BUBBLE_PADDING_Y * 2f
        val boxBottom = tailTipY - BUBBLE_TAIL_HEIGHT
        val boxTop = boxBottom - boxHeight
        speechBubbleRect.set(cx - boxWidth / 2f, boxTop, cx + boxWidth / 2f, boxBottom)

        speechBubblePath.reset()
        speechBubblePath.addRoundRect(speechBubbleRect, BUBBLE_CORNER_RADIUS, BUBBLE_CORNER_RADIUS, Path.Direction.CW)
        speechBubbleTailFillPath.reset()
        speechBubbleTailFillPath.moveTo(cx - BUBBLE_TAIL_WIDTH / 2f, boxBottom)
        speechBubbleTailFillPath.lineTo(cx + BUBBLE_TAIL_WIDTH / 2f, boxBottom)
        speechBubbleTailFillPath.lineTo(cx, tailTipY)
        speechBubbleTailFillPath.close()
        // Open path (no closing top edge) so the tail's outline doesn't draw a stray
        // line across the bubble's own bottom border where the two shapes meet.
        speechBubbleTailOutlinePath.reset()
        speechBubbleTailOutlinePath.moveTo(cx - BUBBLE_TAIL_WIDTH / 2f, boxBottom)
        speechBubbleTailOutlinePath.lineTo(cx, tailTipY)
        speechBubbleTailOutlinePath.lineTo(cx + BUBBLE_TAIL_WIDTH / 2f, boxBottom)

        canvas.drawPath(speechBubbleTailFillPath, speechBubblePaint)
        canvas.drawPath(speechBubblePath, speechBubblePaint)
        canvas.drawPath(speechBubblePath, speechBubbleBorderPaint)
        canvas.drawPath(speechBubbleTailOutlinePath, speechBubbleBorderPaint)

        val baselineY = (boxTop + boxBottom) / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(message, cx, baselineY, speechBubbleTextPaint)
    }

    /** [tank]'s body outline (see [TankShape]) built into a screen-space [Path] - the [Path]
     * object is created once per tank id and cached, but still rebuilt via [Path.reset] every
     * call, since [cx]/[cy] (the tank's own position) and the canvas-rotation this is drawn
     * under can genuinely change frame to frame. */
    private fun tankBodyPathFor(tank: Tank, cx: Float, cy: Float, halfWidth: Float): Path {
        val path = tankBodyPaths.getOrPut(tank.id) { Path() }
        path.reset()
        tank.shape.outline.forEachIndexed { index, (nx, ny) ->
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
        // GROWTH_SECONDS also mirrors GameEngine's own DEATH_EXPLOSION_GROWTH_SECONDS
        // constant (kept separate since this is a rendering concern, not engine state) -
        // GameEngine uses that same duration to decide when a dying tank's body switches
        // over to Tank.isAsh.
        private const val GROWTH_SECONDS = 0.125f
        private const val HOLD_SECONDS = 0.25f
        private const val FADE_SECONDS = 0.25f
        private const val IMPACT_EFFECT_LIFETIME_SECONDS = GROWTH_SECONDS + HOLD_SECONDS + FADE_SECONDS

        // Wall/ceiling bounce marks: grow over the first 30% of a short 0.25s life, then
        // fade over the rest - see drawBounceEffects. Mirrors GameEngine's own
        // BOUNCE_EFFECT_LIFETIME_SECONDS constant (kept separate since this is a rendering
        // concern, not engine state).
        private const val BOUNCE_EFFECT_LIFETIME_SECONDS = 0.25f
        private const val BOUNCE_GROW_FRACTION = 0.3f
        private const val BOUNCE_MARK_RADIUS = 10f

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

        private const val BUBBLE_PADDING_X = 12f
        private const val BUBBLE_PADDING_Y = 8f
        private const val BUBBLE_CORNER_RADIUS = 10f
        private const val BUBBLE_TAIL_WIDTH = 14f
        private const val BUBBLE_TAIL_HEIGHT = 10f
    }
}
