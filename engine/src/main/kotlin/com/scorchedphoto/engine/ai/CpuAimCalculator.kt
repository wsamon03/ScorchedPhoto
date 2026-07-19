package com.scorchedphoto.engine.ai

import com.scorchedphoto.engine.combat.WeaponCatalog
import com.scorchedphoto.engine.physics.Projectile
import com.scorchedphoto.engine.physics.Wind
import com.scorchedphoto.engine.physics.healthPowerMultiplier
import com.scorchedphoto.engine.physics.launchVelocity
import com.scorchedphoto.engine.physics.stepProjectile
import com.scorchedphoto.engine.tanks.Tank
import com.scorchedphoto.terrain.HeightMap
import kotlin.math.abs
import kotlin.random.Random

data class AimResult(val angleDeg: Float, val power: Float)

/**
 * Aims by binary-searching the *real* [stepProjectile] physics (including current wind)
 * for the power that lands a fixed-angle shot at the target - so AI aiming is guaranteed
 * consistent with actual gameplay physics, and difficulty is applied afterward as noise
 * on top of that ideal solve.
 */
object CpuAimCalculator {

    private const val AIM_ELEVATION_DEG = 45f
    private const val MAX_SIMULATION_SECONDS = 8f
    private const val SIMULATION_DT = 1f / 120f
    private const val BINARY_SEARCH_ITERATIONS = 24

    /** Elevation above horizontal, mirrored to face the target (see [Tank.angleDeg]'s full-circle convention). */
    private fun towardTargetAngleDeg(shooter: Tank, target: Tank, elevationDeg: Float): Float =
        if (target.x >= shooter.x) elevationDeg else 180f - elevationDeg

    fun computeAim(
        shooter: Tank,
        target: Tank,
        terrain: HeightMap,
        wind: Wind,
        difficulty: Difficulty,
        rng: Random = Random.Default,
    ): AimResult {
        val idealPower = solveIdealPower(shooter, target, terrain, wind)
        val baseAngle = towardTargetAngleDeg(shooter, target, AIM_ELEVATION_DEG)

        val angleNoise = difficulty.angleNoiseDegrees * (rng.nextFloat() * 2f - 1f)
        val powerNoise = difficulty.powerNoisePercent * (rng.nextFloat() * 2f - 1f)
        val (minAngle, maxAngle) = if (target.x >= shooter.x) 5f to 85f else 95f to 175f
        return AimResult(
            angleDeg = (baseAngle + angleNoise).coerceIn(minAngle, maxAngle),
            power = (idealPower * (1f + powerNoise)).coerceIn(1f, 100f),
        )
    }

    /** The noise-free power, at a fixed [AIM_ELEVATION_DEG] mirrored toward the target, that lands on the target's x. */
    fun solveIdealPower(
        shooter: Tank,
        target: Tank,
        terrain: HeightMap,
        wind: Wind,
        elevationDeg: Float = AIM_ELEVATION_DEG,
    ): Float {
        val angleDeg = towardTargetAngleDeg(shooter, target, elevationDeg)
        val desiredRange = abs(target.x - shooter.x)

        var lowPower = 1f
        var highPower = 100f
        var bestPower = 50f

        repeat(BINARY_SEARCH_ITERATIONS) {
            val midPower = (lowPower + highPower) / 2f
            bestPower = midPower
            val landingX = simulateLandingX(shooter, terrain, angleDeg, midPower, wind)
            val range = landingX?.let { abs(it - shooter.x) }
            when {
                range == null -> highPower = midPower
                range < desiredRange -> lowPower = midPower
                else -> highPower = midPower
            }
        }
        return bestPower
    }

    private fun simulateLandingX(
        shooter: Tank,
        terrain: HeightMap,
        angleDeg: Float,
        power: Float,
        wind: Wind,
    ): Float? {
        val healthMultiplier = healthPowerMultiplier(shooter.health, Tank.MAX_HEALTH)
        val (vx, vy) = launchVelocity(angleDeg, power, healthMultiplier)
        val projectile = Projectile(shooter.x, shooter.y, vx, vy, WeaponCatalog.STANDARD_SHELL, shooter.id)
        var elapsed = 0f
        while (elapsed < MAX_SIMULATION_SECONDS) {
            stepProjectile(projectile, wind, SIMULATION_DT)
            elapsed += SIMULATION_DT
            val column = projectile.x.toInt().coerceIn(0, terrain.width - 1)
            if (projectile.y >= terrain.heightAt(column)) {
                return projectile.x
            }
            if (projectile.x < -terrain.width || projectile.x > 2 * terrain.width) {
                return null
            }
        }
        return null
    }
}
