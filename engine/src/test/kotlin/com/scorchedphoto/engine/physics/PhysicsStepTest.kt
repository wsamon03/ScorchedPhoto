package com.scorchedphoto.engine.physics

import com.scorchedphoto.engine.combat.WeaponCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PhysicsStepTest {

    private fun projectile(vx: Float, vy: Float) =
        Projectile(x = 0f, y = 0f, vx = vx, vy = vy, weapon = WeaponCatalog.STANDARD_SHELL, ownerTankId = 1)

    @Test
    fun `zero-wind trajectory matches the closed-form parabola`() {
        val p = projectile(vx = 100f, vy = -200f)
        val wind = Wind(0f)
        // Fine timestep: semi-implicit Euler (velocity updated before position, as here)
        // has a systematic bias of 0.5*g*dt*t versus the true continuous parabola, so a
        // small dt keeps that bias well under the assertion tolerance below.
        val dt = 1f / 1000f
        var t = 0f
        val totalTime = 1.0f
        while (t < totalTime) {
            stepProjectile(p, wind, dt)
            t += dt
        }
        val expectedX = 100f * t
        val expectedY = -200f * t + 0.5f * GRAVITY * t * t
        assertTrue("x expected ~$expectedX was ${p.x}", abs(p.x - expectedX) < 1f)
        assertTrue("y expected ~$expectedY was ${p.y}", abs(p.y - expectedY) < 1f)
    }

    @Test
    fun `wind adds horizontal drift proportional to its velocity`() {
        val noWind = projectile(vx = 0f, vy = -50f)
        val withWind = projectile(vx = 0f, vy = -50f)
        val wind = Wind(10f)
        val dt = 1f / 120f
        repeat(60) {
            stepProjectile(noWind, Wind(0f), dt)
            stepProjectile(withWind, wind, dt)
        }
        assertTrue(withWind.x > noWind.x)
    }

    @Test
    fun `apex flag flips exactly when vertical velocity crosses zero`() {
        val p = projectile(vx = 0f, vy = -100f)
        val wind = Wind(0f)
        val dt = 1f / 60f
        var flippedAt = -1
        for (i in 0 until 600) {
            stepProjectile(p, wind, dt)
            if (p.hasPassedApex) {
                flippedAt = i
                break
            }
        }
        assertTrue("apex flag never flipped", flippedAt >= 0)
        assertTrue(p.vy >= 0f)
    }

    @Test
    fun `apex flag stays false while still rising`() {
        val p = projectile(vx = 0f, vy = -100f)
        val wind = Wind(0f)
        stepProjectile(p, wind, 1f / 60f)
        assertFalse(p.hasPassedApex)
    }

    @Test
    fun `launchVelocity at 0 degrees is purely horizontal, pointing right`() {
        val (vx, vy) = launchVelocity(angleDeg = 0f, power = 50f)
        assertTrue(vx > 0f)
        assertEquals(0f, vy, 0.01f)
    }

    @Test
    fun `launchVelocity at 90 degrees is purely vertical, pointing up`() {
        val (vx, vy) = launchVelocity(angleDeg = 90f, power = 50f)
        assertEquals(0f, vx, 0.01f)
        assertTrue(vy < 0f)
    }

    @Test
    fun `launchVelocity at 180 degrees mirrors the horizontal component of 0 degrees`() {
        val right = launchVelocity(angleDeg = 0f, power = 50f)
        val left = launchVelocity(angleDeg = 180f, power = 50f)
        assertEquals(-right.first, left.first, 0.01f)
        assertEquals(right.second, left.second, 0.01f)
    }

    @Test
    fun `launchVelocity at 270 degrees is purely vertical, pointing down`() {
        val (vx, vy) = launchVelocity(angleDeg = 270f, power = 50f)
        assertEquals(0f, vx, 0.01f)
        assertTrue(vy > 0f)
    }

    @Test
    fun `launchVelocity at 135 degrees fires up and to the left`() {
        val (vx, vy) = launchVelocity(angleDeg = 135f, power = 50f)
        assertTrue(vx < 0f)
        assertTrue(vy < 0f)
    }

    private fun rangeAtFullPower(angleDeg: Float): Float {
        val (vx, vy) = launchVelocity(angleDeg, power = 100f)
        val p = projectile(vx, vy)
        val wind = Wind(0f)
        val dt = 1f / 1000f
        var t = 0f
        // y=0 at launch and increases downward, so the projectile starts *at* the y<0f
        // boundary - step at least once before using that as the "landed" condition.
        do {
            stepProjectile(p, wind, dt)
            t += dt
        } while (p.y < 0f && t < 30f)
        return p.x
    }

    @Test
    fun `full-power max range matches the closed-form projectile range formula`() {
        // Derived directly from POWER_SCALE/GRAVITY rather than hardcoding a historical
        // range value, so this test stays valid across future range tuning instead of
        // needing to be rewritten every time POWER_SCALE changes.
        val speed = 100f * POWER_SCALE
        val angleRad = Math.toRadians(45.0)
        val expectedRange = (speed * speed * kotlin.math.sin(2 * angleRad) / GRAVITY).toFloat()

        val actualRange = rangeAtFullPower(45f)

        assertTrue(
            "expected range ~$expectedRange, was $actualRange",
            abs(actualRange - expectedRange) < expectedRange * 0.02f,
        )
    }

    @Test
    fun `healthPowerMultiplier is 1 at full health`() {
        assertEquals(1f, healthPowerMultiplier(health = 100, maxHealth = 100), 0.001f)
    }

    @Test
    fun `healthPowerMultiplier is 0_625 at half health`() {
        assertEquals(0.625f, healthPowerMultiplier(health = 50, maxHealth = 100), 0.001f)
    }

    @Test
    fun `healthPowerMultiplier floors at 0_25 when health reaches zero`() {
        assertEquals(0.25f, healthPowerMultiplier(health = 0, maxHealth = 100), 0.001f)
    }

    @Test
    fun `healthPowerMultiplier is clamped for out-of-range health`() {
        assertEquals(1f, healthPowerMultiplier(health = 150, maxHealth = 100), 0.001f)
        assertEquals(0.25f, healthPowerMultiplier(health = -20, maxHealth = 100), 0.001f)
    }

    @Test
    fun `maxPowerForHealth is 100 at full health`() {
        assertEquals(100f, maxPowerForHealth(health = 100, maxHealth = 100), 0.01f)
    }

    @Test
    fun `maxPowerForHealth is 62_5 at half health`() {
        assertEquals(62.5f, maxPowerForHealth(health = 50, maxHealth = 100), 0.01f)
    }

    @Test
    fun `maxPowerForHealth floors at 25 when health reaches zero`() {
        assertEquals(25f, maxPowerForHealth(health = 0, maxHealth = 100), 0.01f)
    }

    @Test
    fun `normalizeAngleDeg wraps into 0-360`() {
        assertEquals(0f, normalizeAngleDeg(0f), 0.001f)
        assertEquals(0f, normalizeAngleDeg(360f), 0.001f)
        assertEquals(10f, normalizeAngleDeg(370f), 0.001f)
        assertEquals(330f, normalizeAngleDeg(-30f), 0.001f)
        assertEquals(180f, normalizeAngleDeg(-180f), 0.001f)
    }

    @Test
    fun `screenOffsetToAngleDeg maps the four cardinal drag directions`() {
        // Screen space: y increases downward, so "up" is negative dy.
        assertEquals(0f, screenOffsetToAngleDeg(dx = 10f, dy = 0f), 0.01f)
        assertEquals(90f, screenOffsetToAngleDeg(dx = 0f, dy = -10f), 0.01f)
        assertEquals(180f, screenOffsetToAngleDeg(dx = -10f, dy = 0f), 0.01f)
        assertEquals(270f, screenOffsetToAngleDeg(dx = 0f, dy = 10f), 0.01f)
    }

    @Test
    fun `screenOffsetToAngleDeg maps a diagonal drag`() {
        // Up and to the right, equal magnitude -> 45 degrees.
        assertEquals(45f, screenOffsetToAngleDeg(dx = 10f, dy = -10f), 0.01f)
    }

    @Test
    fun `screenOffsetToAngleDeg does not crash for a zero-length drag`() {
        assertEquals(0f, screenOffsetToAngleDeg(dx = 0f, dy = 0f), 0.01f)
    }

    @Test
    fun `oscillatingPower starts at 0, peaks at the half period, and wraps`() {
        val period = 2f
        assertEquals(0f, oscillatingPower(0f, period), 0.01f)
        assertEquals(100f, oscillatingPower(period / 2f, period), 0.5f)
        assertEquals(0f, oscillatingPower(period, period), 0.5f)
        assertEquals(50f, oscillatingPower(period / 4f, period), 0.5f)
        // Held well past one period: the oscillation keeps going, not stuck at an edge.
        assertEquals(100f, oscillatingPower(period * 2.5f, period), 0.5f)
    }

    @Test
    fun `oscillatingPower caps its peak at maxPower instead of always reaching 100`() {
        val period = 2f
        assertEquals(0f, oscillatingPower(0f, period, maxPower = 75f), 0.01f)
        assertEquals(75f, oscillatingPower(period / 2f, period, maxPower = 75f), 0.5f)
        assertEquals(0f, oscillatingPower(period, period, maxPower = 75f), 0.5f)
        // Never overshoots the cap on the way up or back down.
        var t = 0f
        while (t < period * 3f) {
            assertTrue(oscillatingPower(t, period, maxPower = 75f) <= 75.01f)
            t += 0.01f
        }
    }

    @Test
    fun `oscillatingPower stays within 0-100 across a dense sweep`() {
        val period = POWER_CHARGE_PERIOD_SECONDS
        var t = 0f
        while (t < period * 5f) {
            val value = oscillatingPower(t, period)
            assertTrue("oscillatingPower($t) = $value out of [0,100]", value in 0f..100f)
            t += 0.01f
        }
    }
}
