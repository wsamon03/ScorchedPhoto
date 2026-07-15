package com.scorchedphoto.app.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.scorchedphoto.engine.physics.Projectile
import com.scorchedphoto.engine.tanks.Tank
import com.scorchedphoto.terrain.HeightMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws one frame onto the [GameSurfaceView]'s [Canvas]: the photo as a backdrop, a dark
 * scar over any column craters have carved lower than the original terrain, projectiles,
 * and tanks with a barrel indicating aim and a small health bar.
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
    private val healthBarBackPaint = Paint().apply { color = Color.argb(180, 0, 0, 0) }
    private val healthBarFillPaint = Paint().apply { color = Color.argb(220, 60, 200, 60) }

    private val srcRect = photo?.let { Rect(0, 0, it.width, it.height) }

    fun draw(canvas: Canvas, terrain: HeightMap, tanks: List<Tank>, projectiles: List<Projectile>) {
        canvas.drawColor(Color.BLACK)
        val scaleX = canvas.width.toFloat() / terrain.width
        val scaleY = canvas.height.toFloat() / terrain.height

        if (photo != null && srcRect != null) {
            val dst = RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
            canvas.drawBitmap(photo, srcRect, dst, backgroundPaint)
        }

        drawCraterScars(canvas, terrain, scaleX, scaleY)

        for (projectile in projectiles) {
            canvas.drawCircle(projectile.x * scaleX, projectile.y * scaleY, PROJECTILE_RADIUS, projectilePaint)
        }

        for (tank in tanks) {
            if (!tank.alive) continue
            drawTank(canvas, tank, scaleX, scaleY)
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

    private fun drawTank(canvas: Canvas, tank: Tank, scaleX: Float, scaleY: Float) {
        val cx = tank.x * scaleX
        val cy = tank.y * scaleY

        tankBodyPaint.color = tank.color
        canvas.drawRect(cx - TANK_HALF_WIDTH, cy - TANK_HALF_WIDTH, cx + TANK_HALF_WIDTH, cy, tankBodyPaint)

        val angleRad = Math.toRadians(tank.angleDeg.toDouble())
        val direction = if (tank.facingRight) 1f else -1f
        val endX = cx + (cos(angleRad) * BARREL_LENGTH).toFloat() * direction
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
        private const val TANK_HALF_WIDTH = 14f
        private const val BARREL_LENGTH = 26f
        private const val HEALTH_BAR_WIDTH = 32f
        private const val HEALTH_BAR_HEIGHT = 5f
        private const val HEALTH_BAR_GAP = 14f
    }
}
