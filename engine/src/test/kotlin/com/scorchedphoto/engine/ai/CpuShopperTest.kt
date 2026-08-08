package com.scorchedphoto.engine.ai

import com.scorchedphoto.engine.combat.WeaponCatalog
import com.scorchedphoto.engine.combat.WeaponType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuShopperTest {

    @Test
    fun `zero budget buys nothing`() {
        val loadout = CpuShopper.chooseLoadout(budget = 0)
        assertTrue(loadout.isEmpty())
    }

    @Test
    fun `a budget of exactly one weapon's price buys exactly one unit of it`() {
        val price = WeaponCatalog.STANDARD_SHELL.price
        val loadout = CpuShopper.chooseLoadout(budget = price, catalog = listOf(WeaponCatalog.STANDARD_SHELL))
        assertEquals(mapOf(WeaponType.STANDARD_SHELL to 1), loadout)
    }

    @Test
    fun `never buys past a weapon's own ammoLimit even with an unlimited budget`() {
        val loadout = CpuShopper.chooseLoadout(budget = Int.MAX_VALUE / 2, catalog = listOf(WeaponCatalog.STANDARD_SHELL))
        assertEquals(WeaponCatalog.STANDARD_SHELL.ammoLimit, loadout[WeaponType.STANDARD_SHELL])
    }

    @Test
    fun `never buys Earthmover - it deals no damage`() {
        val loadout = CpuShopper.chooseLoadout(budget = Int.MAX_VALUE / 2)
        assertFalse(loadout.containsKey(WeaponType.EARTHMOVER))
    }

    @Test
    fun `never buys Baby Missile - it's already free and unlimited, not gated by the economy`() {
        val loadout = CpuShopper.chooseLoadout(budget = Int.MAX_VALUE / 2)
        assertFalse(loadout.containsKey(WeaponType.BABY_MISSILE))
    }

    @Test
    fun `leftover budget smaller than every remaining candidate's price stops cleanly`() {
        // Standard Shell is the cheapest purchasable weapon - one less than its price can never
        // buy anything, and must not loop or throw.
        val price = WeaponCatalog.STANDARD_SHELL.price
        val loadout = CpuShopper.chooseLoadout(budget = price - 1, catalog = listOf(WeaponCatalog.STANDARD_SHELL))
        assertTrue(loadout.isEmpty())
    }

    @Test
    fun `buys every purchasable weapon up to its own ammoLimit cap once the budget covers the full arsenal`() {
        val purchasable = WeaponCatalog.all.filter { it.maxDamage > 0 && it.ammoLimit != null }
        val fullArsenalCost = purchasable.sumOf { it.price * it.ammoLimit!! }

        val loadout = CpuShopper.chooseLoadout(budget = fullArsenalCost)

        for (weapon in purchasable) {
            assertEquals("expected ${weapon.displayName} to be bought all the way to its cap", weapon.ammoLimit, loadout[weapon.type])
        }
    }
}
