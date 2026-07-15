package com.scorchedphoto.engine.physics

import kotlin.math.cos
import kotlin.math.sin

// Tuned for working-image-resolution pixel scale, not real-world units.
const val GRAVITY = 400f
const val WIND_SCALE = 8f
const val POWER_SCALE = 6f

/**
 * Fixed-timestep Euler integration, shared verbatim by real gameplay and the CPU aim
 * solver in :engine.ai, so AI aiming is guaranteed consistent with real physics.
 */
fun stepProjectile(projectile: Projectile, wind: Wind, dt: Float) {
    val wasRising = projectile.vy < 0f
    projectile.vx += wind.velocity * WIND_SCALE * dt
    projectile.vy += GRAVITY * dt
    projectile.x += projectile.vx * dt
    projectile.y += projectile.vy * dt
    if (wasRising && projectile.vy >= 0f) {
        projectile.hasPassedApex = true
    }
}

/** angleDeg: 0 = horizontal, 90 = straight up. power: 0..100. */
fun launchVelocity(angleDeg: Float, power: Float, facingRight: Boolean): Pair<Float, Float> {
    val angleRad = Math.toRadians(angleDeg.toDouble())
    val speed = power.coerceIn(0f, 100f) * POWER_SCALE
    val direction = if (facingRight) 1f else -1f
    val vx = (cos(angleRad) * speed).toFloat() * direction
    val vy = -(sin(angleRad) * speed).toFloat()
    return vx to vy
}
