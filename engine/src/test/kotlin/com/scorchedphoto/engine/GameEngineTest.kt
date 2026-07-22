package com.scorchedphoto.engine

import com.scorchedphoto.engine.ai.CpuAimCalculator
import com.scorchedphoto.engine.combat.WeaponCatalog
import com.scorchedphoto.engine.combat.WeaponType
import com.scorchedphoto.engine.physics.launchVelocity
import com.scorchedphoto.engine.tanks.Tank
import com.scorchedphoto.engine.tanks.testTank
import com.scorchedphoto.engine.terrain.CraterCarver
import com.scorchedphoto.terrain.HeightMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
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
                shooter.angleDeg = if (target.x >= shooter.x) 45f else 135f
                shooter.power = idealPower
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
        // A steep, weak shot arcs right back down onto the shooter's own position, which
        // is now a guaranteed self-bullseye (instant kill) - use a shallower/stronger arc
        // that clears its own footprint, matching the pattern other tests already use.
        // a fires rightward (toward the middle of the map, away from itself at x=300);
        // b fires leftward (also toward the middle, away from both itself at x=900 and
        // the terrain's right edge) so neither shot risks self-splash or edge clamping.
        a.angleDeg = 45f
        a.power = 30f
        b.angleDeg = 135f
        b.power = 30f

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
            // A shallow, strong-enough shot (range ~216px) that clears the shooter's own
            // bullseye zone without reaching the other tank 400px away, whichever tank is
            // currently up - avoids a self-bullseye ending the match early (see the
            // ammoLimit test above for the same fix).
            current.angleDeg = 45f
            current.power = 30f
            engine.fire()
            runUntilNotResolving(engine)
            winds += engine.wind.velocity
        }
        assertTrue("expected wind to vary across turns", winds.size > 1)
    }

    @Test
    fun `tank falls when a nearby blast removes the ground beneath it`() {
        // Carve the terrain directly rather than firing a shot that happens to undermine
        // the bystander, so this test isolates the actual thing it's about - GameEngine
        // settling a tank onto newly-lower ground - from whether any particular shot also
        // happens to damage or touch the bystander.
        val terrain = flatTerrain(width = 1000, groundY = 500)
        // Far enough apart (450px) that a carve reaching the bystander (radius 30) can't
        // also reach the shooter's own column and interfere with its "unrelated" shot.
        val shooter = testTank(id = 1, ownerId = 1, x = 50f)
        val bystander = testTank(id = 2, ownerId = 2, x = 500f, health = 1000)
        val engine = GameEngine(terrain, listOf(shooter, bystander), maxWindMagnitude = 0f, rng = Random(1))

        // A weak, unrelated shot straight up just to get the engine into
        // FIRING/RESOLVING phase so tick() actually processes gravity.
        shooter.angleDeg = 90f
        shooter.power = 5f
        engine.fire()

        val surfaceBefore = terrain.heightAt(bystander.x.toInt())
        CraterCarver.carve(terrain, bystander.x.toInt(), surfaceBefore, 30)

        runUntilNotResolving(engine)

        val surfaceAfter = terrain.heightAt(bystander.x.toInt())
        assertTrue("expected the ground under the bystander to be carved lower", surfaceAfter > surfaceBefore)
        assertEquals(surfaceAfter.toFloat(), bystander.y, 0.6f)
        assertFalse(bystander.falling)
    }

    @Test
    fun `an injured tank's shot is capped at its reduced max power, not scaled down from what was requested`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val healthyShooter = testTank(id = 1, ownerId = 1, x = 300f, health = 100)
        val healthyTarget = testTank(id = 2, ownerId = 2, x = 700f)
        val halfHealthShooter = testTank(id = 1, ownerId = 1, x = 300f, health = 50)
        val halfHealthTarget = testTank(id = 2, ownerId = 2, x = 700f)

        val healthyEngine = GameEngine(terrain, listOf(healthyShooter, healthyTarget), maxWindMagnitude = 0f, rng = Random(1))
        healthyShooter.angleDeg = 45f
        healthyShooter.power = 80f
        healthyEngine.fire()
        val healthySpeed = healthyEngine.projectiles.single().let { hypot(it.vx.toDouble(), it.vy.toDouble()) }

        val injuredEngine = GameEngine(terrain, listOf(halfHealthShooter, halfHealthTarget), maxWindMagnitude = 0f, rng = Random(1))
        halfHealthShooter.angleDeg = 45f
        halfHealthShooter.power = 80f // requests more than its capped max (62.5)
        injuredEngine.fire()
        val injuredSpeed = injuredEngine.projectiles.single().let { hypot(it.vx.toDouble(), it.vy.toDouble()) }

        // A full-health tank's request (80, under its 100 cap) passes through unchanged.
        val (expectedHealthyVx, expectedHealthyVy) = launchVelocity(45f, 80f)
        val expectedHealthySpeed = hypot(expectedHealthyVx.toDouble(), expectedHealthyVy.toDouble())
        assertEquals(expectedHealthySpeed, healthySpeed, expectedHealthySpeed * 0.01)

        // Half health caps max power at 62.5 (100 * (1 - 0.75*0.5)); the request of 80 clamps
        // down to that fixed ceiling rather than being scaled by a hidden multiplier.
        val (expectedInjuredVx, expectedInjuredVy) = launchVelocity(45f, 62.5f)
        val expectedInjuredSpeed = hypot(expectedInjuredVx.toDouble(), expectedInjuredVy.toDouble())
        assertEquals(expectedInjuredSpeed, injuredSpeed, expectedInjuredSpeed * 0.01)
    }

    @Test
    fun `firing at an angle past 90 degrees lands to the shooter's left`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val shooter = testTank(id = 1, ownerId = 1, x = 500f)
        val bystander = testTank(id = 2, ownerId = 2, x = 900f, health = 1000)
        val engine = GameEngine(terrain, listOf(shooter, bystander), maxWindMagnitude = 0f, rng = Random(1))

        val terrainBefore = terrain.groundY.copyOf()
        shooter.angleDeg = 135f // up and to the left, no facingRight involved anymore
        shooter.power = 20f // modest power so the shot lands well inside the terrain, not off the edge
        engine.fire()
        runUntilNotResolving(engine)

        // A crater carved to the left of the shooter's start x is only possible if the
        // projectile actually traveled left, confirming the full-circle angle (not a
        // legacy facingRight flag) determines direction.
        assertTrue(
            "expected a crater to the shooter's left of x=500",
            (0..480).any { x -> terrain.groundY[x] > terrainBefore[x] },
        )
    }

    @Test
    fun `firing straight down from an elevated tank lands near its own position`() {
        // A tall step: shooter stands on a plateau, bystander sits in the low valley next
        // to it - a 270-degree ("straight down") shot should carve a crater right at the
        // shooter's own x, the "fire down into a valley" case this feature exists for.
        val width = 1000
        val plateauY = 300
        val valleyY = 500
        val groundY = IntArray(width) { x -> if (x < 520) plateauY else valleyY }
        val terrain = HeightMap(width, valleyY + 200, groundY)
        val shooter = testTank(id = 1, ownerId = 1, x = 400f)
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, rng = Random(1))

        val terrainBefore = terrain.groundY.copyOf()
        shooter.angleDeg = 270f
        shooter.power = 20f
        engine.fire()
        runUntilNotResolving(engine)

        assertTrue(
            "expected a crater near the shooter's own x=400",
            (370..430).any { x -> terrain.groundY[x] > terrainBefore[x] },
        )
    }

    @Test
    fun `a well-aimed shot reliably deals at least full damage to a fully healthy tank`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val shooter = testTank(id = 1, ownerId = 1, x = 300f)
        val target = testTank(id = 2, ownerId = 2, x = 500f, health = Tank.MAX_HEALTH)
        val engine = GameEngine(terrain, listOf(shooter, target), maxWindMagnitude = 0f, rng = Random(1))

        val idealPower = CpuAimCalculator.solveIdealPower(shooter, target, terrain, engine.wind)
        shooter.angleDeg = 45f
        shooter.power = idealPower

        assertEquals(Tank.MAX_HEALTH, target.health)
        engine.fire()
        runUntilNotResolving(engine)

        // A shot aimed at the target's exact x always registers as touching the target
        // (distance to its center under Tank.RADIUS, see DamageCalculator) before it can
        // fly past - that's guaranteed at least full weapon damage, and sometimes an
        // outright bullseye kill depending on exactly where the discrete flight path
        // first crosses into range, but never anything less than full damage.
        val maxDamage = WeaponCatalog.STANDARD_SHELL.maxDamage
        assertTrue(
            "expected at least full weapon damage ($maxDamage) or a kill, " +
                "target health was ${target.health}, alive=${target.alive}",
            !target.alive || target.health <= Tank.MAX_HEALTH - maxDamage,
        )
    }

    @Test
    fun `a near hit deals splash damage but does not automatically destroy the tank`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val shooter = testTank(id = 1, ownerId = 1, x = 300f)
        val target = testTank(id = 2, ownerId = 2, x = 500f)
        // Just past target, not between shooter and target - the incoming shot hits
        // target directly (ending its flight) before the trajectory ever comes near the
        // bystander, so the bystander only takes falloff splash damage from target's
        // impact point, never registering as a direct hit itself.
        val bystander = testTank(id = 3, ownerId = 3, x = 520f, health = Tank.MAX_HEALTH)
        val engine = GameEngine(terrain, listOf(shooter, target, bystander), maxWindMagnitude = 0f, rng = Random(1))

        val idealPower = CpuAimCalculator.solveIdealPower(shooter, target, terrain, engine.wind)
        shooter.angleDeg = 45f
        shooter.power = idealPower

        assertTrue(engine.fire())
        runUntilNotResolving(engine)

        val maxDamage = WeaponCatalog.STANDARD_SHELL.maxDamage
        assertTrue(
            "expected the directly-hit target to take at least full weapon damage ($maxDamage) or be killed, " +
                "target health was ${target.health}, alive=${target.alive}",
            !target.alive || target.health <= Tank.MAX_HEALTH - maxDamage,
        )
        assertTrue(
            "expected the bystander to take some splash damage, health was ${bystander.health}",
            bystander.health < Tank.MAX_HEALTH,
        )
        assertTrue("expected the bystander to survive a near hit at full health", bystander.alive)
    }

    @Test
    fun `a large fall damages a tank without automatically killing it`() {
        val terrain = flatTerrain(width = 1000, groundY = 300)
        val other = testTank(id = 1, ownerId = 1, x = 900f, health = Tank.MAX_HEALTH)
        val tank = testTank(id = 2, ownerId = 2, x = 300f, health = Tank.MAX_HEALTH)
        val engine = GameEngine(terrain, listOf(other, tank), maxWindMagnitude = 0f, rng = Random(1))

        // `other` fires a weak, unrelated shot near its own position (far from `tank`)
        // just to get the engine into FIRING/RESOLVING so tick() processes gravity. The
        // ground under `tank` is then dropped directly, for an exact, deterministic fall
        // distance, decoupled from `other`'s shot and from CraterCarver's own carve shape.
        other.angleDeg = 90f
        other.power = 1f
        engine.fire()

        terrain.groundY[tank.x.toInt()] = 300 + 150 // a large, sudden 150px drop

        val healthBefore = tank.health
        runUntilNotResolving(engine)

        assertTrue("expected the fall to deal damage", tank.health < healthBefore)
        assertTrue("expected a single fall to not automatically kill a full-health tank", tank.alive)
    }

    @Test
    fun `a small settle causes no fall damage`() {
        val terrain = flatTerrain(width = 1000, groundY = 300)
        val other = testTank(id = 1, ownerId = 1, x = 900f, health = Tank.MAX_HEALTH)
        val tank = testTank(id = 2, ownerId = 2, x = 300f, health = Tank.MAX_HEALTH)
        val engine = GameEngine(terrain, listOf(other, tank), maxWindMagnitude = 0f, rng = Random(1))

        other.angleDeg = 90f
        other.power = 1f
        engine.fire()

        terrain.groundY[tank.x.toInt()] = 300 + 5 // a trivial settle, well under the damage threshold

        runUntilNotResolving(engine)

        assertEquals(Tank.MAX_HEALTH, tank.health)
    }
}
