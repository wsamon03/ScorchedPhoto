package com.scorchedphoto.engine.combat

import com.scorchedphoto.engine.tanks.testTank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DamageCalculatorTest {

    private val weapon = WeaponCatalog.STANDARD_SHELL // blastRadius=28, maxDamage=35, minDamageIfInRadius=5

    @Test
    fun `direct hit at blast center deals max damage`() {
        val tank = testTank(id = 1, x = 100f, y = 100f)
        val damage = DamageCalculator.computeDamage(weapon, blastX = 100f, blastY = 100f, tank = tank)
        assertEquals(weapon.maxDamage, damage)
    }

    @Test
    fun `damage falls off linearly with distance`() {
        val near = testTank(id = 1, x = 105f, y = 100f)
        val far = testTank(id = 2, x = 120f, y = 100f)
        val nearDamage = DamageCalculator.computeDamage(weapon, blastX = 100f, blastY = 100f, tank = near)
        val farDamage = DamageCalculator.computeDamage(weapon, blastX = 100f, blastY = 100f, tank = far)
        assertTrue(nearDamage > farDamage)
    }

    @Test
    fun `damage at exactly the blast radius is the minimum floor`() {
        val tank = testTank(id = 1, x = 100f + weapon.blastRadius, y = 100f)
        val damage = DamageCalculator.computeDamage(weapon, blastX = 100f, blastY = 100f, tank = tank)
        assertEquals(weapon.minDamageIfInRadius, damage)
    }

    @Test
    fun `no damage beyond the blast radius`() {
        val tank = testTank(id = 1, x = 100f + weapon.blastRadius + 1f, y = 100f)
        val damage = DamageCalculator.computeDamage(weapon, blastX = 100f, blastY = 100f, tank = tank)
        assertEquals(0, damage)
    }
}
