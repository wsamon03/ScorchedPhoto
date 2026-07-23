package com.scorchedphoto.app.game

import android.graphics.Bitmap
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
class GameRenderer(private val photo: Bitmap?, private val originalGroundY: IntArray) {

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

    private val srcRect = photo?.let { Rect(0, 0, it.width, it.height) }

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
            if (!tank.alive && !tank.burning) continue
            val colorOverride = if (tank.id == currentTankId) flashColor else null
            drawTank(canvas, tank, terrain, transform, colorOverride)
            if (tank.burning) {
                drawBurningTank(canvas, tank, transform)
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
        val cx = transform.screenX(tank.x)
        val cy = transform.screenY(tank.y)
        val burnRadius = (TANK_HALF_WIDTH + 2f) * transform.scale
        val burningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 100, 0)
        }
        canvas.drawCircle(cx, cy, burnRadius, burningPaint)
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
    }
}
