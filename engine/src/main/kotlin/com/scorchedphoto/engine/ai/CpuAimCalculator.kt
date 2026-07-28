package com.scorchedphoto.engine.ai

import com.scorchedphoto.engine.combat.WeaponCatalog
import com.scorchedphoto.engine.physics.Projectile
import com.scorchedphoto.engine.physics.Wind
import com.scorchedphoto.engine.physics.launchVelocity
import com.scorchedphoto.engine.physics.maxPowerForHealth
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
            power = (idealPower * (1f + powerNoise)).coerceIn(1f, maxPowerForHealth(shooter.health, Tank.MAX_HEALTH)),
        )
    }

    /**
     * The noise-free power, at a fixed [AIM_ELEVATION_DEG] mirrored toward the target, that
     * lands on the target's x - bounded by [shooter]'s actual power ceiling (see
     * [maxPowerForHealth]) so an injured CPU tank never "solves" for a shot stronger than
     * it can really fire.
     */
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
        var highPower = maxPowerForHealth(shooter.health, Tank.MAX_HEALTH)
        var bestPower = highPower / 2f

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

    /**
     * Known, currently-dormant mismatch: this checks only the single column [Projectile.x]
     * lands in each simulation step, same as [com.scorchedphoto.engine.GameEngine]'s real
     * collision resolution used to before it gained a swept multi-column check
     * ([com.scorchedphoto.engine.GameEngine.findTerrainCrossing], for steep/near-vertical
     * terrain steps a fast shot can cross in one tick). Every current caller only ever solves
     * against flat terrain, where the two approaches agree, so this hasn't mattered in
     * practice - but a future steep-terrain CPU-targeting scenario could see this solver aim
     * for an x the real (swept) engine no longer lands a shot at.
     */
    private fun simulateLandingX(
        shooter: Tank,
        terrain: HeightMap,
        angleDeg: Float,
        power: Float,
        wind: Wind,
    ): Float? {
        val (vx, vy) = launchVelocity(angleDeg, power)
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
