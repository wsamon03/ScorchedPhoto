package com.scorchedphoto.engine.ai

import com.scorchedphoto.engine.combat.WeaponCatalog
import com.scorchedphoto.engine.physics.Projectile
import com.scorchedphoto.engine.physics.Wind
import com.scorchedphoto.engine.physics.launchVelocity
import com.scorchedphoto.engine.physics.stepProjectile
import com.scorchedphoto.engine.tanks.Tank
import com.scorchedphoto.engine.tanks.testTank
import com.scorchedphoto.terrain.HeightMap
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class CpuAimCalculatorTest {

    private fun flatTerrain(width: Int, groundY: Int) = HeightMap(width, groundY + 200, IntArray(width) { groundY })

    private fun replay(
        shooter: Tank,
        terrain: HeightMap,
        angleDeg: Float,
        power: Float,
        wind: Wind,
    ): Float {
        val (vx, vy) = launchVelocity(angleDeg, power)
        val p = Projectile(shooter.x, shooter.y, vx, vy, WeaponCatalog.STANDARD_SHELL, shooter.id)
        var t = 0f
        while (t < 10f) {
            stepProjectile(p, wind, 1f / 120f)
            t += 1f / 120f
            val column = p.x.toInt().coerceIn(0, terrain.width - 1)
            if (p.y >= terrain.heightAt(column)) return p.x
        }
        return p.x
    }

    @Test
    fun `ideal solve lands near the target across a grid of distances and winds`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        for (distance in listOf(100f, 250f, 400f)) {
            for (windVelocity in listOf(-10f, 0f, 10f)) {
                for (facingRight in listOf(true, false)) {
                    val shooterX = 500f
                    val targetX = if (facingRight) shooterX + distance else shooterX - distance
                    val shooter = testTank(id = 1, x = shooterX, y = 500f)
                    val target = testTank(id = 2, x = targetX, y = 500f)
                    val wind = Wind(windVelocity)

                    val power = CpuAimCalculator.solveIdealPower(shooter, target, terrain, wind)
                    val angleDeg = if (facingRight) 45f else 135f
                    val landingX = replay(shooter, terrain, angleDeg, power, wind)

                    assertTrue(
                        "distance=$distance wind=$windVelocity facingRight=$facingRight " +
                            "expected landing near $targetX, got $landingX (power=$power)",
                        abs(landingX - targetX) < 15f,
                    )
                }
            }
        }
    }

    @Test
    fun `difficulty noise stays within its configured bounds`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val shooter = testTank(id = 1, x = 400f, y = 500f)
        val target = testTank(id = 2, x = 700f, y = 500f)
        val wind = Wind(0f)
        val idealPower = CpuAimCalculator.solveIdealPower(shooter, target, terrain, wind)

        repeat(100) { seed ->
            val result = CpuAimCalculator.computeAim(shooter, target, terrain, wind, Difficulty.EASY, Random(seed.toLong()))
            val maxPowerNoise = idealPower * Difficulty.EASY.powerNoisePercent
            assertTrue(abs(result.power - idealPower) <= maxPowerNoise + 0.01f)
            assertTrue(abs(result.angleDeg - 45f) <= Difficulty.EASY.angleNoiseDegrees + 0.01f)
        }
    }

    @Test
    fun `harder difficulty is more accurate on average than easier difficulty`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val shooter = testTank(id = 1, x = 300f, y = 500f)
        val target = testTank(id = 2, x = 800f, y = 500f)
        val wind = Wind(5f)

        val easyErrors = (0 until 50).map { seed ->
            val aim = CpuAimCalculator.computeAim(shooter, target, terrain, wind, Difficulty.EASY, Random(seed.toLong()))
            abs(replay(shooter, terrain, aim.angleDeg, aim.power, wind) - target.x)
        }
        val hardErrors = (0 until 50).map { seed ->
            val aim = CpuAimCalculator.computeAim(shooter, target, terrain, wind, Difficulty.HARD, Random(seed.toLong()))
            abs(replay(shooter, terrain, aim.angleDeg, aim.power, wind) - target.x)
        }

        assertTrue(hardErrors.average() < easyErrors.average())
    }

    @Test
    fun `ideal solve still lands on target when the shooter is injured`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val shooter = testTank(id = 1, x = 400f, y = 500f, health = 40)
        // 300 units away: within the health-40 shooter's power ceiling (55, i.e. a max
        // range of ~319 at this POWER_SCALE/GRAVITY) so the solve can actually land here,
        // unlike the shooter's un-capped, full-health range which reaches much further.
        val target = testTank(id = 2, x = 700f, y = 500f)
        val wind = Wind(0f)

        // The solve is already bounded by the shooter's actual power ceiling (see
        // maxPowerForHealth), so replaying its result needs no extra discount applied.
        val power = CpuAimCalculator.solveIdealPower(shooter, target, terrain, wind)
        val landingX = replay(shooter, terrain, 45f, power, wind = wind)

        assertTrue(
            "expected landing near ${target.x}, got $landingX (power=$power)",
            abs(landingX - target.x) < 15f,
        )
    }

    @Test
    fun `aiming at a target to the shooter's left mirrors into the 95-175 degree range`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val shooter = testTank(id = 1, x = 700f, y = 500f)
        val target = testTank(id = 2, x = 300f, y = 500f)
        val wind = Wind(0f)

        repeat(20) { seed ->
            val aim = CpuAimCalculator.computeAim(shooter, target, terrain, wind, Difficulty.EASY, Random(seed.toLong()))
            assertTrue(
                "expected a leftward-mirrored angle in (95,175), was ${aim.angleDeg}",
                aim.angleDeg in 95f..175f,
            )
        }
    }
}
