package com.scorchedphoto.engine

import com.scorchedphoto.engine.ai.CpuAimCalculator
import com.scorchedphoto.engine.combat.WeaponCatalog
import com.scorchedphoto.engine.combat.WeaponType
import com.scorchedphoto.engine.tanks.testTank
import com.scorchedphoto.terrain.HeightMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameEngineTest {

    private fun flatTerrain(width: Int, groundY: Int) = HeightMap(width, groundY + 200, IntArray(width) { groundY })

    private fun runUntilNotResolving(engine: GameEngine, dt: Float = 1f / 60f, maxTicks: Int = 20_000) {
        var ticks = 0
        while ((engine.phase == MatchPhase.FIRING || engine.phase == MatchPhase.RESOLVING) && ticks < maxTicks) {
            engine.tick(dt)
            ticks++
        }
        assertTrue("engine never left FIRING/RESOLVING within $maxTicks ticks", ticks < maxTicks)
    }

    @Test
    fun `firing and resolving advances to the next tank's turn`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val a = testTank(id = 1, ownerId = 1, x = 300f)
        val b = testTank(id = 2, ownerId = 2, x = 700f)
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 0f, rng = Random(1))

        assertEquals(1, engine.currentTank?.id)
        a.angleDeg = 80f
        a.power = 20f
        a.facingRight = true
        assertTrue(engine.fire())
        assertEquals(MatchPhase.FIRING, engine.phase)

        runUntilNotResolving(engine)

        assertEquals(MatchPhase.AIMING, engine.phase)
        assertEquals(2, engine.currentTank?.id)
    }

    @Test
    fun `direct hit deals splash damage and carves a crater`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val shooter = testTank(id = 1, ownerId = 1, x = 300f)
        val target = testTank(id = 2, ownerId = 2, x = 500f)
        val engine = GameEngine(terrain, listOf(shooter, target), maxWindMagnitude = 0f, rng = Random(1))

        val idealPower = CpuAimCalculator.solveIdealPower(shooter, target, terrain, engine.wind)
        shooter.angleDeg = 45f
        shooter.power = idealPower
        shooter.facingRight = true

        val healthBefore = target.health
        val terrainBefore = terrain.groundY.copyOf()

        assertTrue(engine.fire())
        runUntilNotResolving(engine)

        assertTrue(
            "expected splash damage, health was $healthBefore now ${target.health}",
            target.health < healthBefore,
        )
        assertTrue(
            "expected a crater near the target",
            (400..600).any { x -> terrain.groundY[x] > terrainBefore[x] },
        )
    }

    @Test
    fun `repeated direct hits eliminate the target and end the match`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val shooter = testTank(id = 1, ownerId = 1, x = 300f)
        val target = testTank(id = 2, ownerId = 2, x = 500f)
        val engine = GameEngine(terrain, listOf(shooter, target), maxWindMagnitude = 0f, rng = Random(1))

        var guard = 0
        while (engine.phase != MatchPhase.GAME_OVER && guard < 20) {
            val current = engine.currentTank!!
            if (current.id == shooter.id) {
                val idealPower = CpuAimCalculator.solveIdealPower(shooter, target, terrain, engine.wind)
                shooter.angleDeg = 45f
                shooter.power = idealPower
                shooter.facingRight = target.x >= shooter.x
            } else {
                // Fires straight up so it can never hit back and skew the test.
                target.angleDeg = 90f
                target.power = 5f
            }
            engine.fire()
            runUntilNotResolving(engine)
            guard++
        }

        assertEquals(MatchPhase.GAME_OVER, engine.phase)
        assertFalse(target.alive)
        assertNotNull(engine.winResult)
        assertEquals(shooter.ownerId, engine.winResult?.winningOwnerId)
    }

    @Test
    fun `impact leaves a short-lived visual effect that eventually fades`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val a = testTank(id = 1, ownerId = 1, x = 300f)
        val b = testTank(id = 2, ownerId = 2, x = 700f)
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 0f, rng = Random(1))

        // 45 degrees / decent power clears the weapon's own blast radius so the shooter
        // doesn't eliminate itself with self-splash damage on repeated re-fires below.
        fun fireArcShot() {
            val current = engine.currentTank!!
            current.angleDeg = 45f
            current.power = 30f
            engine.fire()
        }

        fireArcShot()
        var ticks = 0
        while (engine.impactEffects.isEmpty() && ticks < 1000) {
            engine.tick(1f / 60f)
            ticks++
        }
        assertTrue("expected an impact effect to be recorded", engine.impactEffects.isNotEmpty())

        // tick() is a no-op outside FIRING/RESOLVING, so aging pauses once a turn resolves;
        // keep firing new (survivable) shots to keep ticking until the first flash fades.
        var guard = 0
        while (engine.impactEffects.isNotEmpty() && guard < 300 && engine.phase != MatchPhase.GAME_OVER) {
            if (engine.phase == MatchPhase.AIMING) {
                fireArcShot()
            }
            engine.tick(1f / 60f)
            guard++
        }
        assertTrue("expected the impact effect to eventually fade", engine.impactEffects.isEmpty())
    }

    @Test
    fun `limited ammo weapon runs out after ammoLimit shots`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val a = testTank(id = 1, ownerId = 1, x = 300f, health = 1000)
        val b = testTank(id = 2, ownerId = 2, x = 900f, health = 1000)
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 0f, rng = Random(1))

        val ammoLimit = WeaponCatalog.BIG_BERTHA.ammoLimit!!
        a.currentWeapon = WeaponType.BIG_BERTHA
        a.angleDeg = 90f
        a.power = 1f
        b.angleDeg = 90f
        b.power = 1f

        repeat(ammoLimit) {
            assertEquals(a.id, engine.currentTank?.id)
            assertEquals(ammoLimit - it, engine.ammoFor(a.id, WeaponType.BIG_BERTHA))
            assertTrue(engine.fire())
            runUntilNotResolving(engine)
            assertEquals(b.id, engine.currentTank?.id)
            engine.fire()
            runUntilNotResolving(engine)
        }

        assertEquals(0, engine.ammoFor(a.id, WeaponType.BIG_BERTHA))
        assertEquals(a.id, engine.currentTank?.id)
        assertFalse(engine.fire())
    }

    @Test
    fun `wind changes between turns when magnitude is nonzero`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val a = testTank(id = 1, ownerId = 1, x = 300f, health = 1000)
        val b = testTank(id = 2, ownerId = 2, x = 700f, health = 1000)
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 20f, rng = Random(2))

        val winds = mutableSetOf(engine.wind.velocity)
        repeat(5) {
            val current = engine.currentTank!!
            current.angleDeg = 80f
            current.power = 10f
            engine.fire()
            runUntilNotResolving(engine)
            winds += engine.wind.velocity
        }
        assertTrue("expected wind to vary across turns", winds.size > 1)
    }

    @Test
    fun `tank falls when a nearby blast removes the ground beneath it`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val shooter = testTank(id = 1, ownerId = 1, x = 300f)
        val bystander = testTank(id = 2, ownerId = 2, x = 340f, health = 1000)
        val engine = GameEngine(terrain, listOf(shooter, bystander), maxWindMagnitude = 0f, rng = Random(1))

        shooter.currentWeapon = WeaponType.BIG_BERTHA // large blast radius, easily undermines nearby ground
        val aimTarget = testTank(id = 99, ownerId = 2, x = bystander.x, y = bystander.y)
        val idealPower = CpuAimCalculator.solveIdealPower(shooter, aimTarget, terrain, engine.wind)
        shooter.angleDeg = 45f
        shooter.power = idealPower
        shooter.facingRight = true

        val surfaceBefore = terrain.heightAt(bystander.x.toInt())
        engine.fire()
        runUntilNotResolving(engine)

        val surfaceAfter = terrain.heightAt(bystander.x.toInt())
        assertTrue("expected the ground under the bystander to be carved lower", surfaceAfter > surfaceBefore)
        assertEquals(surfaceAfter.toFloat(), bystander.y, 0.6f)
        assertFalse(bystander.falling)
    }
}
