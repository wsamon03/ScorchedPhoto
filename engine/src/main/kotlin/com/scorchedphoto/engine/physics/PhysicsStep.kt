package com.scorchedphoto.engine.physics

import kotlin.math.cos
import kotlin.math.sin

// Tuned for working-image-resolution pixel scale, not real-world units.
const val GRAVITY = 400f
const val WIND_SCALE = 8f
// Range (no wind) is v^2*sin(2*angle)/GRAVITY, i.e. proportional to POWER_SCALE^2 - so
// doubling this constant, not quadrupling it, gives 4x the range at the same power/angle.
const val POWER_SCALE = 12f

// A tank's usable power falls off as it takes damage: at 0 health it can still fire, but
// at only (1 - INJURED_POWER_PENALTY) of a full-health tank's power.
const val INJURED_POWER_PENALTY = 0.5f

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

/**
 * angleDeg: 0 = horizontal, 90 = straight up. power: 0..100. healthMultiplier: 0..1,
 * from [healthPowerMultiplier] - defaults to 1 (full power) for callers that don't
 * model tank health, e.g. existing tests.
 */
fun launchVelocity(angleDeg: Float, power: Float, facingRight: Boolean, healthMultiplier: Float = 1f): Pair<Float, Float> {
    val angleRad = Math.toRadians(angleDeg.toDouble())
    val speed = power.coerceIn(0f, 100f) * POWER_SCALE * healthMultiplier.coerceIn(0f, 1f)
    val direction = if (facingRight) 1f else -1f
    val vx = (cos(angleRad) * speed).toFloat() * direction
    val vy = -(sin(angleRad) * speed).toFloat()
    return vx to vy
}

/**
 * A tank's maximum power decreases as it loses health: the drop is
 * [INJURED_POWER_PENALTY] of the percentage of health it has lost, so a tank at 50%
 * health fires at 1 - 0.5*50% = 75% of full-health power/range, and a tank at 0 health
 * still fires at 1 - 0.5*100% = 50% power rather than being unable to fire at all.
 */
fun healthPowerMultiplier(health: Int, maxHealth: Int): Float {
    val healthFraction = (health.toFloat() / maxHealth).coerceIn(0f, 1f)
    return 1f - INJURED_POWER_PENALTY * (1f - healthFraction)
}
