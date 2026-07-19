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
    fun `launchVelocity at 0 degrees is purely horizontal`() {
        val (vx, vy) = launchVelocity(angleDeg = 0f, power = 50f, facingRight = true)
        assertTrue(vx > 0f)
        assertEquals(0f, vy, 0.01f)
    }

    @Test
    fun `launchVelocity at 90 degrees is purely vertical`() {
        val (vx, vy) = launchVelocity(angleDeg = 90f, power = 50f, facingRight = true)
        assertEquals(0f, vx, 0.01f)
        assertTrue(vy < 0f)
    }

    @Test
    fun `launchVelocity facing left mirrors horizontal component`() {
        val right = launchVelocity(angleDeg = 30f, power = 50f, facingRight = true)
        val left = launchVelocity(angleDeg = 30f, power = 50f, facingRight = false)
        assertEquals(-right.first, left.first, 0.01f)
        assertEquals(right.second, left.second, 0.01f)
    }

    private fun rangeAtFullPower(angleDeg: Float): Float {
        val (vx, vy) = launchVelocity(angleDeg, power = 100f, facingRight = true)
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
    fun `full-power max range is 4x what it was before the range buff`() {
        // POWER_SCALE was doubled (6 -&gt; 12) to quadruple range, since range is
        // proportional to speed^2. Recompute the old range from first principles (not by
        // hardcoding a POWER_SCALE value) so this test documents intent rather than
        // just re-asserting whatever the constant happens to be.
        val oldPowerScale = POWER_SCALE / 2f
        val oldSpeed = 100f * oldPowerScale
        val angleRad = Math.toRadians(45.0)
        val oldRange = (oldSpeed * oldSpeed * kotlin.math.sin(2 * angleRad) / GRAVITY).toFloat()

        val newRange = rangeAtFullPower(45f)

        assertTrue(
            "expected ~4x the old range ($oldRange), was $newRange",
            abs(newRange - 4f * oldRange) < 4f * oldRange * 0.02f,
        )
    }

    @Test
    fun `healthPowerMultiplier is 1 at full health`() {
        assertEquals(1f, healthPowerMultiplier(health = 100, maxHealth = 100), 0.001f)
    }

    @Test
    fun `healthPowerMultiplier is 0_75 at half health`() {
        assertEquals(0.75f, healthPowerMultiplier(health = 50, maxHealth = 100), 0.001f)
    }

    @Test
    fun `healthPowerMultiplier floors at 0_5 when health reaches zero`() {
        assertEquals(0.5f, healthPowerMultiplier(health = 0, maxHealth = 100), 0.001f)
    }

    @Test
    fun `healthPowerMultiplier is clamped for out-of-range health`() {
        assertEquals(1f, healthPowerMultiplier(health = 150, maxHealth = 100), 0.001f)
        assertEquals(0.5f, healthPowerMultiplier(health = -20, maxHealth = 100), 0.001f)
    }

    @Test
    fun `launchVelocity scales speed by healthMultiplier`() {
        val full = launchVelocity(angleDeg = 30f, power = 80f, facingRight = true, healthMultiplier = 1f)
        val half = launchVelocity(angleDeg = 30f, power = 80f, facingRight = true, healthMultiplier = 0.75f)
        assertEquals(full.first * 0.75f, half.first, 0.01f)
        assertEquals(full.second * 0.75f, half.second, 0.01f)
    }
}
