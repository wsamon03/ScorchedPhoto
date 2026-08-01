package com.scorchedphoto.engine.ai

import com.scorchedphoto.engine.combat.WeaponType
import com.scorchedphoto.engine.tanks.testTank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CpuWeaponSelectorTest {

    private val unlimitedAmmo: (WeaponType) -> Int? = { null }

    @Test
    fun `never selects a weapon that's out of ammo`() {
        val target = testTank(id = 2, health = 50)
        val ammo = mapOf(
            WeaponType.STANDARD_SHELL to 0,
            WeaponType.BIG_BERTHA to 0,
            WeaponType.MIRV to 0,
        )
        repeat(100) { seed ->
            val selected = CpuWeaponSelector.selectWeapon(
                target = target,
                otherEnemiesAlive = 1,
                difficulty = Difficulty.entries.random(Random(seed.toLong())),
                ammoFor = { ammo[it] },
                rng = Random(seed.toLong()),
            )
            // Only Baby Missile (unlimited ammo, not present in the map above) is left.
            assertEquals(WeaponType.BABY_MISSILE, selected)
        }
    }

    @Test
    fun `falls back to a different weapon once its usual pick runs out`() {
        val target = testTank(id = 2, health = 50)
        val ammo = mutableMapOf(WeaponType.STANDARD_SHELL to 0)
        val selected = CpuWeaponSelector.selectWeapon(
            target = target,
            otherEnemiesAlive = 1,
            difficulty = Difficulty.HARD,
            ammoFor = { ammo[it] },
        )
        assertTrue("expected a weapon other than the empty Standard Shell", selected != WeaponType.STANDARD_SHELL)
    }

    @Test
    fun `hard difficulty picks the most plentiful weapon that can still one-shot a low-health target`() {
        val target = testTank(id = 2, health = 20)
        val selected = CpuWeaponSelector.selectWeapon(
            target = target,
            otherEnemiesAlive = 1,
            difficulty = Difficulty.HARD,
            ammoFor = unlimitedAmmo,
        )
        // Baby Missile (maxDamage 15) can't finish a 20-health target. Standard Shell, Big
        // Bertha, and MIRV all can, but Standard Shell starts with the most ammo (5, vs 1
        // apiece for the other two) - the most expendable choice, so it's conserved for.
        assertEquals(WeaponType.STANDARD_SHELL, selected)
    }

    @Test
    fun `hard difficulty picks the hardest-hitting single shot when no weapon can finish the target`() {
        val target = testTank(id = 2, health = 1000)
        val selected = CpuWeaponSelector.selectWeapon(
            target = target,
            otherEnemiesAlive = 1,
            difficulty = Difficulty.HARD,
            ammoFor = unlimitedAmmo,
        )
        assertEquals(WeaponType.BIG_BERTHA, selected)
    }

    @Test
    fun `hard difficulty prefers MIRV when multiple enemies are alive and no one-shot is possible`() {
        val target = testTank(id = 2, health = 1000)
        val selected = CpuWeaponSelector.selectWeapon(
            target = target,
            otherEnemiesAlive = 2,
            difficulty = Difficulty.HARD,
            ammoFor = unlimitedAmmo,
        )
        assertEquals(WeaponType.MIRV, selected)
    }

    @Test
    fun `easy difficulty mostly picks Standard Shell`() {
        val target = testTank(id = 2, health = 1000)
        val rng = Random(42)
        val counts = (0 until 200).map {
            CpuWeaponSelector.selectWeapon(
                target = target,
                otherEnemiesAlive = 1,
                difficulty = Difficulty.EASY,
                ammoFor = unlimitedAmmo,
                rng = rng,
            )
        }.groupingBy { it }.eachCount()
        val standardShellCount = counts[WeaponType.STANDARD_SHELL] ?: 0
        assertTrue(
            "expected Standard Shell to dominate easy-difficulty picks, got $counts",
            standardShellCount > 200 * 0.6,
        )
    }
}
