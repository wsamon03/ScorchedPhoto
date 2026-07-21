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
 * fading impact flashes, and tanks (body tilted to the local slope, a barrel indicating
 * aim, and a small health bar). Every world-space position and size is mapped through a
 * single [WorldTransform.fit] (see its doc for why: independent x/y scale factors distort
 * launch angles and motion).
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
    private val healthBarBackPaint = Paint().apply { color = Color.argb(180, 0, 0, 0) }
    private val healthBarFillPaint = Paint().apply { color = Color.argb(220, 60, 200, 60) }

    private val srcRect = photo?.let { Rect(0, 0, it.width, it.height) }

    fun draw(
        canvas: Canvas,
        terrain: HeightMap,
        tanks: List<Tank>,
        projectiles: List<Projectile>,
        impactEffects: List<ImpactEffect>,
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
        drawImpactEffects(canvas, impactEffects, transform)

        for (projectile in projectiles) {
            canvas.drawCircle(
                transform.screenX(projectile.x),
                transform.screenY(projectile.y),
                PROJECTILE_RADIUS * transform.scale,
                projectilePaint,
            )
        }

        for (tank in tanks) {
            if (!tank.alive) continue
            drawTank(canvas, tank, terrain, transform)
        }
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

    private fun drawImpactEffects(canvas: Canvas, impactEffects: List<ImpactEffect>, transform: WorldTransform) {
        for (impact in impactEffects) {
            val fadeFraction = (1f - impact.age / IMPACT_EFFECT_LIFETIME_SECONDS).coerceIn(0f, 1f)
            if (fadeFraction <= 0f) continue
            impactPaint.alpha = (fadeFraction * 255).toInt()
            // Sized to the weapon's actual blast radius (the same radius CraterCarver and
            // DamageCalculator use, in world space like everything else here) so the flash
            // visually matches the area that's actually affected, shrinking slightly as it
            // fades rather than starting from a fixed size.
            val radius = impact.blastRadius * transform.scale * (1f - fadeFraction * 0.5f)
            canvas.drawCircle(transform.screenX(impact.x), transform.screenY(impact.y), radius, impactPaint)
        }
        impactPaint.alpha = 255
    }

    private fun drawTank(canvas: Canvas, tank: Tank, terrain: HeightMap, transform: WorldTransform) {
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

        tankBodyPaint.color = tank.color
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

        val healthBarWidth = HEALTH_BAR_WIDTH * transform.scale
        val healthBarHeight = HEALTH_BAR_HEIGHT * transform.scale
        val barTop = cy - halfWidth - HEALTH_BAR_GAP * transform.scale
        canvas.drawRect(cx - healthBarWidth / 2, barTop, cx + healthBarWidth / 2, barTop + healthBarHeight, healthBarBackPaint)
        val healthFraction = (tank.health.toFloat() / Tank.MAX_HEALTH).coerceIn(0f, 1f)
        canvas.drawRect(
            cx - healthBarWidth / 2,
            barTop,
            cx - healthBarWidth / 2 + healthBarWidth * healthFraction,
            barTop + healthBarHeight,
            healthBarFillPaint,
        )
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
        private const val CRATER_STROKE_WIDTH = 40f
        private const val PROJECTILE_RADIUS = 5f
        private const val BARREL_STROKE_WIDTH = 5f

        // Tank body half-width shares Tank.RADIUS with GameEngine's hit-detection radius,
        // so the visual size and the actual collision size never drift apart. The rest of
        // these scale proportionally with it (4x the old 14f-radius tuning: barrel 26->104,
        // health bar 32x5->128x20, gap 14->56, slope sample offset 12->48). All are world-
        // space units, scaled by WorldTransform.scale like every other size in this file.
        private const val TANK_HALF_WIDTH = Tank.RADIUS
        private const val BARREL_LENGTH = 104f
        private const val HEALTH_BAR_WIDTH = 128f
        private const val HEALTH_BAR_HEIGHT = 20f
        private const val HEALTH_BAR_GAP = 56f
        private const val SLOPE_SAMPLE_OFFSET = 48

        private const val IMPACT_EFFECT_LIFETIME_SECONDS = 0.4f
    }
}
