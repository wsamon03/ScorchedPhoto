package com.scorchedphoto.engine.physics

import kotlin.math.PI
import kotlin.math.atan2
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

// One full 0 -> 100 -> 0 sweep of the hold-to-charge power meter. A UX timing constant
// (not physics-derived like POWER_SCALE/GRAVITY) - tune freely by feel.
const val POWER_CHARGE_PERIOD_SECONDS = 1.6f

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
 * angleDeg: full circle, standard math convention - 0 = right, 90 = up, 180 = left,
 * 270 = down, counterclockwise. power: 0..100. healthMultiplier: 0..1, from
 * [healthPowerMultiplier] - defaults to 1 (full power) for callers that don't model
 * tank health, e.g. existing tests. No angle is treated as invalid (e.g. firing
 * downward into the ground right next to the tank is a legal, if usually bad, shot -
 * the existing crater/splash-damage system already handles that case).
 */
fun launchVelocity(angleDeg: Float, power: Float, healthMultiplier: Float = 1f): Pair<Float, Float> {
    val angleRad = Math.toRadians(angleDeg.toDouble())
    val speed = power.coerceIn(0f, 100f) * POWER_SCALE * healthMultiplier.coerceIn(0f, 1f)
    val vx = (cos(angleRad) * speed).toFloat()
    val vy = -(sin(angleRad) * speed).toFloat()
    return vx to vy
}

/** Wraps an angle into `[0, 360)`. */
fun normalizeAngleDeg(angleDeg: Float): Float {
    val wrapped = angleDeg % 360f
    return if (wrapped < 0f) wrapped + 360f else wrapped
}

/**
 * Converts a drag position relative to the tank's screen center into the engine's
 * angle convention. Screen space: x increases rightward, y increases *downward*
 * (standard Canvas/Compose convention) - so "up" is negative dy, which this negates
 * before the standard atan2(y,x) formula to match [launchVelocity]'s convention.
 * dx=dy=0 (no meaningful drag yet) returns 0 (horizontal right).
 */
fun screenOffsetToAngleDeg(dx: Float, dy: Float): Float {
    val angleRad = atan2(-dy.toDouble(), dx.toDouble())
    return normalizeAngleDeg((angleRad * 180.0 / PI).toFloat())
}

/**
 * The hold-to-charge power meter's value at a given elapsed hold time: a smooth
 * 0->100->0 oscillation, exactly 0 at t=0, exactly 100 at the half-period, wrapping
 * cleanly for indefinite holds.
 */
fun oscillatingPower(elapsedSeconds: Float, periodSeconds: Float = POWER_CHARGE_PERIOD_SECONDS): Float {
    val phase = (elapsedSeconds % periodSeconds) / periodSeconds
    return (50f * (1f - cos(2f * PI.toFloat() * phase))).coerceIn(0f, 100f)
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
