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
        // Every weapon with a finite ammoLimit zeroed out - Baby Missile (the only weapon with
        // ammoLimit == null, so absent here) is the sole one left with ammo remaining.
        val ammo = mapOf(
            WeaponType.STANDARD_SHELL to 0,
            WeaponType.BIG_BERTHA to 0,
            WeaponType.CLUSTER_MIRV to 0,
            WeaponType.SPREAD_MIRV to 0,
            WeaponType.NUKE to 0,
            WeaponType.EARTHMOVER to 0,
            WeaponType.NAPALM to 0,
            WeaponType.WIDOWMAKER to 0,
            WeaponType.FLAK_BURST to 0,
        )
        repeat(100) { seed ->
            val selected = CpuWeaponSelector.selectWeapon(
                target = target,
                otherEnemiesAlive = 1,
                difficulty = Difficulty.entries.random(Random(seed.toLong())),
                ammoFor = { ammo[it] },
                rng = Random(seed.toLong()),
            )
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
        // Baby Missile (maxDamage 15) and Flak Burst (maxDamage 8, its own single pellet) can't
        // finish a 20-health target; every other weapon that can (Standard Shell, Big Bertha,
        // Cluster MIRV, Spread MIRV, Nuke, Napalm, Widowmaker) starts with less ammo than
        // Standard Shell's 5 - the most expendable choice, so it's conserved for.
        assertEquals(WeaponType.STANDARD_SHELL, selected)
    }

    @Test
    fun `hard difficulty picks the hardest overall damage (direct hit plus any DoT) when no weapon can finish the target`() {
        val target = testTank(id = 2, health = 1000)
        val selected = CpuWeaponSelector.selectWeapon(
            target = target,
            otherEnemiesAlive = 1,
            difficulty = Difficulty.HARD,
            ammoFor = unlimitedAmmo,
        )
        // Nuke's direct 80 damage plus its 3-round, 10%-max-health radiation tick (worth 30
        // more) beats every other weapon's damage, including Big Bertha's raw 60.
        assertEquals(WeaponType.NUKE, selected)
    }

    @Test
    fun `hard difficulty prefers the best multi-warhead weapon when multiple enemies are alive and no one-shot is possible`() {
        val target = testTank(id = 2, health = 1000)
        val selected = CpuWeaponSelector.selectWeapon(
            target = target,
            otherEnemiesAlive = 2,
            difficulty = Difficulty.HARD,
            ammoFor = unlimitedAmmo,
        )
        // Cluster MIRV (30 dmg x 4) and Spread MIRV (24 dmg x 5) tie at 120 total; Cluster MIRV
        // wins as the first one WeaponCatalog.all lists.
        assertEquals(WeaponType.CLUSTER_MIRV, selected)
    }

    @Test
    fun `never selects Earthmover - a utility weapon with no offensive AI behind it`() {
        val target = testTank(id = 2, health = 50)
        repeat(50) { seed ->
            val selected = CpuWeaponSelector.selectWeapon(
                target = target,
                otherEnemiesAlive = 1,
                difficulty = Difficulty.entries.random(Random(seed.toLong())),
                ammoFor = unlimitedAmmo,
                rng = Random(seed.toLong()),
            )
            assertTrue("expected Earthmover to never be selected, got $selected", selected != WeaponType.EARTHMOVER)
        }
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
