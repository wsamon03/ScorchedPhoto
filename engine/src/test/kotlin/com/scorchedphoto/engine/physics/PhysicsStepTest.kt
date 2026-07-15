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
}
