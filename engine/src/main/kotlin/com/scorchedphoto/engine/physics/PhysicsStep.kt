package com.scorchedphoto.engine.physics

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// Tuned for working-image-resolution pixel scale, not real-world units.
const val GRAVITY = 400f
// Wind's contribution to horizontal drift is linear in this constant, so halving it
// (was 8f) directly halves wind's effect on trajectories.
const val WIND_SCALE = 4f
// Range (no wind) is v^2*sin(2*angle)/GRAVITY, i.e. proportional to POWER_SCALE^2 - so
// scaling this by sqrt(0.67) (was 12f) gives ~33% less range at the same power/angle.
const val POWER_SCALE = 9.8f

// A tank's usable power falls off as it takes damage: at 0 health it can still fire, but
// at only (1 - INJURED_POWER_PENALTY) of a full-health tank's power.
const val INJURED_POWER_PENALTY = 0.5f

// One full 0 -> 100 -> 0 sweep of the hold-to-charge power meter. A UX timing constant
// (not physics-derived like POWER_SCALE/GRAVITY) - tune freely by feel. Doubled (was
// 1.6f) to move the meter at half speed.
const val POWER_CHARGE_PERIOD_SECONDS = 3.2f

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
 * 270 = down, counterclockwise. power: 0..100 - callers are responsible for capping this
 * to whatever the shooter can actually reach (see [maxPowerForHealth]) before calling;
 * this function just turns whatever power it's given into a speed, no further discount
 * applied. No angle is treated as invalid (e.g. firing downward into the ground right
 * next to the tank is a legal, if usually bad, shot - the existing crater/splash-damage
 * system already handles that case).
 */
fun launchVelocity(angleDeg: Float, power: Float): Pair<Float, Float> {
    val angleRad = Math.toRadians(angleDeg.toDouble())
    val speed = power.coerceIn(0f, 100f) * POWER_SCALE
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
 * 0->maxPower->0 oscillation, exactly 0 at t=0, exactly [maxPower] at the half-period,
 * wrapping cleanly for indefinite holds. [maxPower] caps how high the meter can climb (see
 * [maxPowerForHealth]) - an injured tank's bar rises only to that reduced ceiling and back
 * down again, so the player sees their actual firing limit rather than having whatever
 * they release silently discounted afterward.
 */
fun oscillatingPower(elapsedSeconds: Float, periodSeconds: Float = POWER_CHARGE_PERIOD_SECONDS, maxPower: Float = 100f): Float {
    val phase = (elapsedSeconds % periodSeconds) / periodSeconds
    return (maxPower / 2f * (1f - cos(2f * PI.toFloat() * phase))).coerceIn(0f, maxPower)
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

/**
 * The actual power ceiling (0..100, same scale as [Tank.power]/the charge meter) a tank
 * at [health] can reach - [healthPowerMultiplier] expressed directly as a power cap rather
 * than a post-hoc speed discount, so it can be enforced at the source: the charge meter
 * stops rising here (see [oscillatingPower]), and [com.scorchedphoto.engine.GameEngine.fire]
 * clamps to it rather than scaling down whatever power was requested.
 */
fun maxPowerForHealth(health: Int, maxHealth: Int): Float = 100f * healthPowerMultiplier(health, maxHealth)
