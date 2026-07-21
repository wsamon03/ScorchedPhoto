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
import com.scorchedphoto.terrain.HeightMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws one frame onto the [GameSurfaceView]'s [Canvas]: the photo as a backdrop, a dark
 * scar over any column craters have carved lower than the original terrain, projectiles,
 * fading impact flashes, and tanks (body tilted to the local slope, a barrel indicating
 * aim, and a small health bar).
 */
class GameRenderer(private val photo: Bitmap?, private val originalGroundY: IntArray) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val craterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 40, 30, 20)
        style = Paint.Style.STROKE
        strokeWidth = CRATER_STROKE_WIDTH
        strokeCap = Paint.Cap.ROUND
    }
    private val tankBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barrelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        strokeWidth = 5f
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
        val scaleX = canvas.width.toFloat() / terrain.width
        val scaleY = canvas.height.toFloat() / terrain.height

        if (photo != null && srcRect != null) {
            val dst = RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
            canvas.drawBitmap(photo, srcRect, dst, backgroundPaint)
        }

        drawCraterScars(canvas, terrain, scaleX, scaleY)
        drawImpactEffects(canvas, impactEffects, scaleX, scaleY)

        for (projectile in projectiles) {
            canvas.drawCircle(projectile.x * scaleX, projectile.y * scaleY, PROJECTILE_RADIUS, projectilePaint)
        }

        for (tank in tanks) {
            if (!tank.alive) continue
            drawTank(canvas, tank, terrain, scaleX, scaleY)
        }
    }

    private fun drawCraterScars(canvas: Canvas, terrain: HeightMap, scaleX: Float, scaleY: Float) {
        val path = Path()
        var started = false
        for (x in 0 until terrain.width) {
            val original = originalGroundY.getOrElse(x) { terrain.groundY[x] }
            val current = terrain.groundY[x]
            if (current <= original) {
                started = false
                continue
            }
            val px = x * scaleX
            if (!started) {
                path.moveTo(px, current * scaleY)
                started = true
            } else {
                path.lineTo(px, current * scaleY)
            }
        }
        canvas.drawPath(path, craterPaint)
    }

    private fun drawImpactEffects(canvas: Canvas, impactEffects: List<ImpactEffect>, scaleX: Float, scaleY: Float) {
        for (impact in impactEffects) {
            val fadeFraction = (1f - impact.age / IMPACT_EFFECT_LIFETIME_SECONDS).coerceIn(0f, 1f)
            if (fadeFraction <= 0f) continue
            impactPaint.alpha = (fadeFraction * 255).toInt()
            // Sized to the weapon's actual blast radius (the same radius CraterCarver and
            // DamageCalculator use, kept unscaled like TANK_HALF_WIDTH below) so the flash
            // visually matches the area that's actually affected, shrinking slightly as it
            // fades rather than starting from a fixed size.
            val radius = impact.blastRadius * (1f - fadeFraction * 0.5f)
            canvas.drawCircle(impact.x * scaleX, impact.y * scaleY, radius, impactPaint)
        }
        impactPaint.alpha = 255
    }

    private fun drawTank(canvas: Canvas, tank: Tank, terrain: HeightMap, scaleX: Float, scaleY: Float) {
        val cx = tank.x * scaleX
        val cy = tank.y * scaleY

        val leftX = (tank.x - SLOPE_SAMPLE_OFFSET).toInt()
        val rightX = (tank.x + SLOPE_SAMPLE_OFFSET).toInt()
        val riseTerrain = (terrain.heightAt(rightX) - terrain.heightAt(leftX)).toFloat()
        val slopeDeg = Math.toDegrees(
            atan2((riseTerrain * scaleY).toDouble(), (2 * SLOPE_SAMPLE_OFFSET * scaleX).toDouble()),
        ).toFloat()

        tankBodyPaint.color = tank.color
        canvas.save()
        canvas.rotate(slopeDeg, cx, cy)
        canvas.drawRect(cx - TANK_HALF_WIDTH, cy - TANK_HALF_WIDTH, cx + TANK_HALF_WIDTH, cy, tankBodyPaint)
        canvas.restore()

        // Barrel angle is an absolute aim reference, so it's drawn unrotated by slope.
        // Full-circle convention matches PhysicsStep.launchVelocity exactly - no separate
        // facing flag, cos/sin alone cover all four quadrants.
        val angleRad = Math.toRadians(tank.angleDeg.toDouble())
        val endX = cx + (cos(angleRad) * BARREL_LENGTH).toFloat()
        val endY = cy - (sin(angleRad) * BARREL_LENGTH).toFloat()
        canvas.drawLine(cx, cy, endX, endY, barrelPaint)

        val barTop = cy - TANK_HALF_WIDTH - HEALTH_BAR_GAP
        canvas.drawRect(cx - HEALTH_BAR_WIDTH / 2, barTop, cx + HEALTH_BAR_WIDTH / 2, barTop + HEALTH_BAR_HEIGHT, healthBarBackPaint)
        val healthFraction = (tank.health.toFloat() / Tank.MAX_HEALTH).coerceIn(0f, 1f)
        canvas.drawRect(
            cx - HEALTH_BAR_WIDTH / 2,
            barTop,
            cx - HEALTH_BAR_WIDTH / 2 + HEALTH_BAR_WIDTH * healthFraction,
            barTop + HEALTH_BAR_HEIGHT,
            healthBarFillPaint,
        )
    }

    companion object {
        private const val CRATER_STROKE_WIDTH = 40f
        private const val PROJECTILE_RADIUS = 5f

        // Tank body half-width shares Tank.RADIUS with GameEngine's hit-detection radius,
        // so the visual size and the actual collision size never drift apart. The rest of
        // these scale proportionally with it (4x the old 14f-radius tuning: barrel 26->104,
        // health bar 32x5->128x20, gap 14->56, slope sample offset 12->48).
        private const val TANK_HALF_WIDTH = Tank.RADIUS
        private const val BARREL_LENGTH = 104f
        private const val HEALTH_BAR_WIDTH = 128f
        private const val HEALTH_BAR_HEIGHT = 20f
        private const val HEALTH_BAR_GAP = 56f
        private const val SLOPE_SAMPLE_OFFSET = 48

        private const val IMPACT_EFFECT_LIFETIME_SECONDS = 0.4f
    }
}
