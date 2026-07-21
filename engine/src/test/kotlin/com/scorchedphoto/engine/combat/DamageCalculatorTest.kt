package com.scorchedphoto.engine.combat

import com.scorchedphoto.engine.tanks.Tank
import com.scorchedphoto.engine.tanks.testTank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DamageCalculatorTest {

    // blastRadius=28, maxDamage=35. Tank.RADIUS=56, so bullseyeRadius=56*0.2=11.2 and
    // maxReach=56+28=84.
    private val weapon = WeaponCatalog.STANDARD_SHELL

    private fun tankAtDistance(distance: Float) = testTank(id = 1, x = 100f + distance, y = 100f)

    @Test
    fun `a dead-center impact is a bullseye instant kill`() {
        val tank = testTank(id = 1, x = 100f, y = 100f)
        val damage = DamageCalculator.computeDamage(weapon, impactX = 100f, impactY = 100f, tank = tank)
        assertNull("expected a bullseye (null) for a dead-center impact", damage)
    }

    @Test
    fun `impacts within the central 20 percent of the tank are still a bullseye`() {
        val bullseyeRadius = Tank.RADIUS * 0.2f
        val tank = tankAtDistance(bullseyeRadius - 0.5f)
        val damage = DamageCalculator.computeDamage(weapon, impactX = 100f, impactY = 100f, tank = tank)
        assertNull(damage)
    }

    @Test
    fun `just outside the bullseye zone is full damage, not an instant kill`() {
        val bullseyeRadius = Tank.RADIUS * 0.2f
        val tank = tankAtDistance(bullseyeRadius + 0.5f)
        val damage = DamageCalculator.computeDamage(weapon, impactX = 100f, impactY = 100f, tank = tank)
        assertEquals(weapon.maxDamage, damage)
    }

    @Test
    fun `anywhere else on the tank's body deals full damage`() {
        val tank = tankAtDistance(30f) // between the bullseye zone and Tank.RADIUS (56)
        val damage = DamageCalculator.computeDamage(weapon, impactX = 100f, impactY = 100f, tank = tank)
        assertEquals(weapon.maxDamage, damage)
    }

    @Test
    fun `exactly at the tank's edge still deals full damage`() {
        val tank = tankAtDistance(Tank.RADIUS)
        val damage = DamageCalculator.computeDamage(weapon, impactX = 100f, impactY = 100f, tank = tank)
        assertEquals(weapon.maxDamage, damage)
    }

    @Test
    fun `just past the tank's edge is graduated, not full damage`() {
        val tank = tankAtDistance(Tank.RADIUS + 1f)
        val damage = DamageCalculator.computeDamage(weapon, impactX = 100f, impactY = 100f, tank = tank)
        assertTrue("expected less than full damage just past the edge, was $damage", damage != null && damage < weapon.maxDamage)
        assertTrue("expected close to full damage just past the edge, was $damage", damage != null && damage > weapon.maxDamage / 2)
    }

    @Test
    fun `damage grades down linearly as the impact moves from the tank's edge to max reach`() {
        val nearEdge = DamageCalculator.computeDamage(weapon, impactX = 100f, impactY = 100f, tank = tankAtDistance(Tank.RADIUS + 5f))
        val midway = DamageCalculator.computeDamage(weapon, impactX = 100f, impactY = 100f, tank = tankAtDistance(Tank.RADIUS + weapon.blastRadius / 2f))
        val nearMaxReach = DamageCalculator.computeDamage(weapon, impactX = 100f, impactY = 100f, tank = tankAtDistance(Tank.RADIUS + weapon.blastRadius - 1f))

        requireNotNull(nearEdge)
        requireNotNull(midway)
        requireNotNull(nearMaxReach)
        assertTrue("expected damage to decrease as distance increases", nearEdge > midway)
        assertTrue("expected damage to decrease as distance increases", midway > nearMaxReach)
    }

    @Test
    fun `damage at exactly max reach is 5 percent of the weapon's max damage`() {
        val maxReach = Tank.RADIUS + weapon.blastRadius
        val tank = tankAtDistance(maxReach)
        val damage = DamageCalculator.computeDamage(weapon, impactX = 100f, impactY = 100f, tank = tank)
        assertEquals((weapon.maxDamage * 0.05f).let { Math.round(it) }, damage)
    }

    @Test
    fun `no damage beyond the tank's radius plus the weapon's blast radius`() {
        val maxReach = Tank.RADIUS + weapon.blastRadius
        val tank = tankAtDistance(maxReach + 1f)
        val damage = DamageCalculator.computeDamage(weapon, impactX = 100f, impactY = 100f, tank = tank)
        assertEquals(0, damage)
    }
}
