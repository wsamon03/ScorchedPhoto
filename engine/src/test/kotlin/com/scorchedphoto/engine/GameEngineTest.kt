package com.scorchedphoto.engine

import com.scorchedphoto.engine.ai.CpuAimCalculator
import com.scorchedphoto.engine.combat.WeaponCatalog
import com.scorchedphoto.engine.combat.WeaponType
import com.scorchedphoto.engine.physics.GRAVITY
import com.scorchedphoto.engine.physics.POWER_SCALE
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

        // Turn order is randomized per match, so this test only asserts that resolving a
        // turn advances to the *other* tank - not which one goes first.
        val first = engine.currentTank!!
        val second = if (first.id == a.id) b else a
        first.angleDeg = 80f
        first.power = 20f
        assertTrue(engine.fire())
        assertEquals(MatchPhase.FIRING, engine.phase)

        runUntilNotResolving(engine)

        assertEquals(MatchPhase.AIMING, engine.phase)
        assertEquals(second.id, engine.currentTank?.id)
    }

    @Test
    fun `direct hit deals splash damage and carves a crater`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val a = testTank(id = 1, ownerId = 1, x = 300f)
        val b = testTank(id = 2, ownerId = 2, x = 500f)
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 0f, rng = Random(1))
        // Turn order is randomized - whichever tank actually goes first plays the shooter
        // role (x=300); the other plays the target (x=500), regardless of which underlying
        // Tank object that is. Repositioning after construction is safe on this flat terrain
        // since every column shares the same height.
        val shooter = engine.currentTank!!
        val target = if (shooter === a) b else a
        shooter.x = 300f
        target.x = 500f

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
        assertEquals(listOf(shooter.ownerId), engine.winResult?.winningOwnerIds)
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
        val t1 = testTank(id = 1, ownerId = 1, x = 300f, health = 1000)
        val t2 = testTank(id = 2, ownerId = 2, x = 900f, health = 1000)
        val engine = GameEngine(terrain, listOf(t1, t2), maxWindMagnitude = 0f, rng = Random(1))
        // Turn order is randomized - whichever tank actually goes first gets the limited
        // weapon this test tracks ("a"), regardless of which underlying Tank object that is.
        val a = engine.currentTank!!
        val b = if (a === t1) t2 else t1
        a.x = 300f
        b.x = 900f

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
        val healthyA = testTank(id = 1, ownerId = 1, x = 300f)
        val healthyB = testTank(id = 2, ownerId = 2, x = 700f)
        val halfHealthA = testTank(id = 1, ownerId = 1, x = 300f)
        val halfHealthB = testTank(id = 2, ownerId = 2, x = 700f)

        // Turn order is randomized - whichever tank actually goes first is given the health
        // this test cares about (the other tank's own health is irrelevant to this test).
        val healthyEngine = GameEngine(terrain, listOf(healthyA, healthyB), maxWindMagnitude = 0f, rng = Random(1))
        val healthyShooter = healthyEngine.currentTank!!
        healthyShooter.health = 100
        healthyShooter.angleDeg = 45f
        healthyShooter.power = 80f
        healthyEngine.fire()
        val healthySpeed = healthyEngine.projectiles.single().let { hypot(it.vx.toDouble(), it.vy.toDouble()) }

        val injuredEngine = GameEngine(terrain, listOf(halfHealthA, halfHealthB), maxWindMagnitude = 0f, rng = Random(1))
        val halfHealthShooter = injuredEngine.currentTank!!
        halfHealthShooter.health = 50
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
        // Turn order is randomized - whichever tank actually fires is this test's "shooter",
        // and the assertion below is expressed relative to its own x, not a fixed value.
        val firer = engine.currentTank!!

        val terrainBefore = terrain.groundY.copyOf()
        firer.angleDeg = 135f // up and to the left, no facingRight involved anymore
        firer.power = 20f // modest power so the shot lands well inside the terrain, not off the edge
        engine.fire()
        runUntilNotResolving(engine)

        // A crater carved to the left of the firer's start x is only possible if the
        // projectile actually traveled left, confirming the full-circle angle (not a
        // legacy facingRight flag) determines direction.
        val upperBound = (firer.x.toInt() - 20).coerceAtLeast(0)
        assertTrue(
            "expected a crater to the firer's left of x=${firer.x}",
            (0 until upperBound).any { x -> terrain.groundY[x] > terrainBefore[x] },
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
    fun `firing a fast shallow shot into a vertical wall detonates at the wall's face, not its top`() {
        // A genuine one-column-wide vertical step: valley at x<520 (groundY=500), plateau at
        // x>=520 (groundY=300) - the same shape TerrainSegmenter's DP-seam-plus-upscale can
        // produce for real. A shallow, fast shot from the valley crosses several columns in a
        // single physics tick while still near the valley's own height (not yet touching
        // valleyY=500) - the bug this test targets: a single-final-column check would resolve
        // the impact at that final column's raw terrain height (300, the plateau's own top
        // surface) instead of the interpolated height at which the flight path actually first
        // went solid.
        val width = 1000
        val valleyY = 500
        val plateauY = 300
        val groundY = IntArray(width) { x -> if (x < 520) valleyY else plateauY }
        val terrain = HeightMap(width, valleyY + 200, groundY)
        val shooter = testTank(id = 1, ownerId = 1, x = 400f)
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, rng = Random(1))

        val terrainBefore = terrain.groundY.copyOf()
        shooter.angleDeg = 10f
        shooter.power = 80f
        engine.fire()

        var impactX: Float? = null
        var impactY: Float? = null
        var ticks = 0
        while (engine.impactEffects.isEmpty() && engine.projectiles.isNotEmpty() && ticks < 200) {
            engine.tick(1f / 60f)
            ticks++
        }
        engine.impactEffects.firstOrNull()?.let {
            impactX = it.x
            impactY = it.y
        }

        assertNotNull("expected the shot to detonate against the wall", impactX)
        assertTrue(
            "expected the impact right at the wall's own column (x=520), not lofted deep into " +
                "the plateau by skipping several columns in one tick; was $impactX",
            impactX!! in 515f..525f,
        )
        assertTrue(
            "expected the impact near the wall's actual face height (close to the valley's own " +
                "~$valleyY), not teleported up to the plateau's top surface (y=$plateauY); was $impactY",
            impactY!! > 400f,
        )

        runUntilNotResolving(engine)

        assertTrue(
            "expected the valley-side ground right at the wall's base to be carved into (the " +
                "projectile hit the wall's side) - a bug that resolves at the plateau's top " +
                "instead leaves these columns completely untouched",
            (500..519).any { x -> terrain.groundY[x] > terrainBefore[x] },
        )
    }

    @Test
    fun `a well-aimed shot reliably deals at least full damage to a fully healthy tank`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val a = testTank(id = 1, ownerId = 1, x = 300f)
        val b = testTank(id = 2, ownerId = 2, x = 500f)
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 0f, rng = Random(1))
        // Turn order is randomized - whichever tank actually goes first plays the shooter
        // role (x=300); the other plays the target (x=500).
        val shooter = engine.currentTank!!
        val target = if (shooter === a) b else a
        shooter.x = 300f
        target.x = 500f
        target.health = Tank.MAX_HEALTH

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
        val t1 = testTank(id = 1, ownerId = 1, x = 300f)
        val t2 = testTank(id = 2, ownerId = 2, x = 500f)
        val t3 = testTank(id = 3, ownerId = 3, x = 520f)
        val engine = GameEngine(terrain, listOf(t1, t2, t3), maxWindMagnitude = 0f, rng = Random(1))
        // Turn order is randomized - whichever tank actually goes first plays the shooter
        // role (x=300); the other two fill the target (x=500, takes the direct hit)/
        // bystander (x=520, just past target - splash only) roles in their original order.
        val shooter = engine.currentTank!!
        val others = listOf(t1, t2, t3).filter { it !== shooter }
        val target = others[0]
        // Just past target, not between shooter and target - the incoming shot hits
        // target directly (ending its flight) before the trajectory ever comes near the
        // bystander, so the bystander only takes falloff splash damage from target's
        // impact point, never registering as a direct hit itself.
        val bystander = others[1]
        shooter.x = 300f
        target.x = 500f
        bystander.x = 520f
        bystander.health = Tank.MAX_HEALTH

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

    @Test
    fun `every remaining tank dying in the same resolution declares a tie instead of stalling`() {
        val terrain = flatTerrain(width = 1000, groundY = 300)
        val a = testTank(id = 1, ownerId = 1, x = 900f, health = Tank.MAX_HEALTH)
        val b = testTank(id = 2, ownerId = 2, x = 300f, health = Tank.MAX_HEALTH)
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 0f, rng = Random(1))

        // `a` fires a weak, unrelated shot just to get the engine into FIRING/RESOLVING so
        // tick() processes gravity (same pattern as the fall-damage tests above) - then
        // the ground under *both* tanks (including the shooter itself) drops by a lethal
        // amount, simulating a single blast big enough to undermine everyone left at once.
        a.angleDeg = 90f
        a.power = 1f
        engine.fire()

        terrain.groundY[a.x.toInt()] = 300 + 400
        terrain.groundY[b.x.toInt()] = 300 + 400

        runUntilNotResolving(engine)

        assertEquals(MatchPhase.GAME_OVER, engine.phase)
        assertFalse(a.alive)
        assertFalse(b.alive)
        val result = engine.winResult
        assertNotNull("expected a tie result instead of a stalled match with no winner", result)
        assertEquals(setOf(1, 2), result?.winningOwnerIds?.toSet())
        assertEquals(setOf(1, 2), result?.winningTankIds?.toSet())
    }

    @Test
    fun `WallType and CeilingType NONE preserve the existing far-off fizzle`() {
        // Shooter starts right at x=0 in a narrow (width=50) map; firing up-and-left at a
        // shallow-enough arc crosses the -width fizzle bound (-50) while still well elevated
        // above the flat ground (long before the parabola would naturally return to ground
        // height), so this exercises the "flew off into the void" branch specifically,
        // distinct from an ordinary ground impact.
        val terrain = flatTerrain(width = 50, groundY = 500)
        val shooter = testTank(id = 1, ownerId = 1, x = 0f)
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, rng = Random(1))
        shooter.angleDeg = 135f
        shooter.power = 50f
        engine.fire()

        var ticks = 0
        while (engine.projectiles.isNotEmpty() && ticks < 200) {
            engine.tick(1f / 60f)
            ticks++
        }

        assertTrue("expected the projectile to fizzle out rather than linger", engine.projectiles.isEmpty())
        assertTrue("fizzle should not register as a scored impact", engine.drainEvents().none { it == GameEvent.Impact })
        assertTrue("fizzle should leave no impact effect", engine.impactEffects.isEmpty())
        assertTrue("fizzle should leave no bounce effect", engine.bounceEffects.isEmpty())
    }

    @Test
    fun `wall type Reflective bounces a projectile back at 98 percent speed`() {
        val terrain = flatTerrain(width = 200, groundY = 500)
        val shooter = testTank(id = 1, ownerId = 1, x = 100f)
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, wallType = EdgeType.REFLECTIVE, rng = Random(1))
        shooter.angleDeg = 135f
        shooter.power = 50f
        engine.fire()

        var vxBeforeBounce = 0f
        var bounced = false
        var ticks = 0
        while (!bounced && ticks < 200) {
            vxBeforeBounce = engine.projectiles.first().vx
            engine.tick(1f / 60f)
            if (engine.projectiles.first().vx > 0f) bounced = true
            ticks++
        }

        assertTrue("expected the wall to reflect the projectile", bounced)
        val p = engine.projectiles.first()
        // No wind, so vx is unchanged by stepProjectile itself - the reflection is the only
        // thing that can flip its sign, and REFLECTIVE's 0.98 retention keeps 98% of the
        // magnitude.
        assertEquals(-vxBeforeBounce * 0.98f, p.vx, 1f)
        assertEquals("expected the projectile clamped exactly onto the wall (x=0)", 0f, p.x, 0.01f)
    }

    @Test
    fun `ceiling type Reflective bounces a projectile back down at 98 percent speed`() {
        val terrain = flatTerrain(width = 1000, groundY = 100)
        val shooter = testTank(id = 1, ownerId = 1, x = 500f)
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, ceilingType = EdgeType.REFLECTIVE, rng = Random(1))
        shooter.angleDeg = 90f
        shooter.power = 50f
        engine.fire()

        var vyBeforeBounce = 0f
        var bounced = false
        var ticks = 0
        while (!bounced && ticks < 200) {
            vyBeforeBounce = engine.projectiles.first().vy
            engine.tick(1f / 60f)
            if (engine.projectiles.first().vy > 0f) bounced = true
            ticks++
        }

        assertTrue("expected the ceiling to reflect the projectile downward", bounced)
        val p = engine.projectiles.first()
        // Unlike vx, vy always gets one more tick's worth of gravity applied by stepProjectile
        // before the reflection check runs, so the expected post-bounce value accounts for it.
        // REFLECTIVE's 0.98 retention keeps 98% of that magnitude.
        val vyEnteringBounceTick = vyBeforeBounce + GRAVITY * (1f / 60f)
        assertEquals(-vyEnteringBounceTick * 0.98f, p.vy, 1f)
        assertEquals("expected the projectile clamped exactly onto the ceiling (y=0)", 0f, p.y, 0.01f)
    }

    @Test
    fun `wall type Spring launches a weak hit at the guaranteed minimum (power 50) rather than 70 percent of its incoming speed`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        // Close enough to the wall (x) that a shallow-angled, low-power shot (angleDeg=135,
        // power=10) still reaches it well before gravity arcs it back down to its own starting
        // height - see the wall Reflective test above for the same up-and-toward-the-wall shape,
        // here scaled down so a much weaker shot still clears the same margin.
        val shooter = testTank(id = 1, ownerId = 1, x = 3f)
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, wallType = EdgeType.SPRING, rng = Random(1))
        shooter.angleDeg = 135f // up and toward the near wall (x=0)
        shooter.power = 10f // weak enough that 70% of its speed is well under the guaranteed minimum
        engine.fire()

        var prevVx = 0f
        var prevVy = 0f
        var bounced = false
        var ticks = 0
        while (!bounced && ticks < 200) {
            val p = engine.projectiles.single()
            prevVx = p.vx
            prevVy = p.vy
            engine.tick(1f / 60f)
            if (engine.bounceEffects.isNotEmpty()) bounced = true
            ticks++
        }

        assertTrue("expected the wall to launch the projectile", bounced)
        // stepProjectile always runs before the wall-bounce dispatch, so the incoming speed the
        // bounce itself sees already includes this tick's own gravity increment to vy.
        val incomingSpeed = hypot(prevVx.toDouble(), (prevVy + GRAVITY * (1f / 60f)).toDouble()).toFloat()
        val guaranteedMinimum = 50f * POWER_SCALE
        assertTrue(
            "expected this weak a hit's 70% retention to fall under the guaranteed minimum, or this test isn't exercising the branch it means to",
            incomingSpeed * 0.7f < guaranteedMinimum,
        )
        val p = engine.projectiles.single()
        assertEquals("expected the guaranteed minimum launch speed, directly away from the wall (positive = rightward)", guaranteedMinimum, p.vx, 1f)
        assertEquals("expected vertical velocity reset to exactly 0, restarting gravity from a standstill", 0f, p.vy, 0.01f)
    }

    @Test
    fun `wall type Spring launches a strong hit at 70 percent of its incoming speed once that exceeds the guaranteed minimum`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val shooter = testTank(id = 1, ownerId = 1, x = 900f)
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, wallType = EdgeType.SPRING, rng = Random(1))
        shooter.angleDeg = 45f // up and toward the far wall (x=terrain.width)
        shooter.power = 100f
        engine.fire()

        var prevVx = 0f
        var prevVy = 0f
        var bounced = false
        var ticks = 0
        while (!bounced && ticks < 200) {
            val p = engine.projectiles.single()
            prevVx = p.vx
            prevVy = p.vy
            engine.tick(1f / 60f)
            if (engine.bounceEffects.isNotEmpty()) bounced = true
            ticks++
        }

        assertTrue("expected the wall to launch the projectile", bounced)
        val incomingSpeed = hypot(prevVx.toDouble(), (prevVy + GRAVITY * (1f / 60f)).toDouble()).toFloat()
        val guaranteedMinimum = 50f * POWER_SCALE
        assertTrue(
            "expected this strong a hit's 70% retention to exceed the guaranteed minimum, or this test isn't exercising the branch it means to",
            incomingSpeed * 0.7f > guaranteedMinimum,
        )
        val p = engine.projectiles.single()
        assertEquals(
            "expected 70% of the incoming speed, directly away from the wall (negative = leftward)",
            -incomingSpeed * 0.7f,
            p.vx,
            5f,
        )
        assertEquals("expected vertical velocity reset to exactly 0, restarting gravity from a standstill", 0f, p.vy, 0.01f)
    }

    @Test
    fun `wall type Blast Steel detonates at the wall, damaging a nearby tank without carving distant terrain`() {
        val terrain = flatTerrain(width = 200, groundY = 500)
        // A 45-degree arc whose unobstructed range (~95px) is just past the shooter's 90px
        // distance to the wall, so it crosses x=0 just before it would naturally land - still
        // close to ground level (a few px of clearance) rather than deep mid-flight, keeping
        // the detonation point within the bystander's blast reach.
        val t1 = testTank(id = 1, ownerId = 1, x = 90f)
        val t2 = testTank(id = 2, ownerId = 2, x = 10f)
        val engine = GameEngine(terrain, listOf(t1, t2), maxWindMagnitude = 0f, wallType = EdgeType.BLAST_STEEL, rng = Random(1))
        // Turn order is randomized - whichever tank actually goes first plays the shooter
        // role (x=90); the other plays the bystander (x=10).
        val shooter = engine.currentTank!!
        val bystander = if (shooter === t1) t2 else t1
        shooter.x = 90f
        bystander.x = 10f
        bystander.health = Tank.MAX_HEALTH
        shooter.angleDeg = 135f // up-and-left, toward the wall at x=0
        shooter.power = 30f

        val distantColumn = 190
        val distantGroundBefore = terrain.groundY[distantColumn]

        engine.fire()
        var ticks = 0
        while (engine.projectiles.isNotEmpty() && ticks < 200) {
            engine.tick(1f / 60f)
            ticks++
        }

        assertTrue("expected the blast-steel wall to detonate the projectile", engine.projectiles.isEmpty())
        assertTrue("expected the nearby bystander to take blast damage", bystander.health < Tank.MAX_HEALTH)
        assertEquals(
            "expected terrain far from the wall to be untouched",
            distantGroundBefore,
            terrain.groundY[distantColumn],
        )
    }

    @Test
    fun `wall type Wrap teleports the projectile to the opposite edge with velocity unchanged`() {
        val terrain = flatTerrain(width = 200, groundY = 500)
        val shooter = testTank(id = 1, ownerId = 1, x = 100f)
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, wallType = EdgeType.WRAP, rng = Random(1))
        shooter.angleDeg = 135f
        shooter.power = 50f
        engine.fire()

        val vxOriginal = engine.projectiles.first().vx
        var wrapped = false
        var ticks = 0
        while (!wrapped && ticks < 200) {
            val before = engine.projectiles.first().x
            engine.tick(1f / 60f)
            val after = engine.projectiles.first().x
            if (after - before > 100f) wrapped = true
            ticks++
        }

        assertTrue("expected the projectile to wrap to the opposite wall", wrapped)
        val p = engine.projectiles.first()
        assertEquals(200f, p.x, 0.01f)
        // No wind, so vx is untouched by ordinary physics too - confirms WRAP itself never
        // negates/scales velocity the way a bounce type would.
        assertEquals(vxOriginal, p.vx, 0.01f)
    }

    @Test
    fun `ceiling type Wrap detonates at the bottom edge instead of continuing to fly`() {
        // flatTerrain(groundY=100) -> height=300, so 0.95*height=285: CraterCarver clamps any
        // deep-enough candidate to that floor, so a detonation genuinely anchored at the map's
        // bottom (not the shallow original surface) drives the shooter's own column all the
        // way down to exactly 285, regardless of its starting height.
        val terrain = flatTerrain(width = 1000, groundY = 100)
        val shooter = testTank(id = 1, ownerId = 1, x = 500f)
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, ceilingType = EdgeType.WRAP, rng = Random(1))
        shooter.angleDeg = 90f
        shooter.power = 50f
        engine.fire()

        var ticks = 0
        while (engine.projectiles.isNotEmpty() && ticks < 200) {
            engine.tick(1f / 60f)
            ticks++
        }

        assertTrue("expected the wrapped projectile to detonate rather than keep flying", engine.projectiles.isEmpty())
        assertTrue(
            "expected an explosion near the map's bottom edge (terrain.height=300), not the surface",
            engine.impactEffects.any { it.y in 299f..301f },
        )
        // The shooter's own column has 200 units of material above the blast's own circle (it
        // starts well below the original surface at 100) - far more than the hole's own full
        // height (2 * blastRadius = 56) - so it drops by exactly that much: 100 + 56 = 156.
        assertEquals(156, terrain.groundY[shooter.x.toInt()])
    }

    @Test
    fun `ceiling type Wrap honors a custom ceilingWrapDepthY override`() {
        val terrain = flatTerrain(width = 1000, groundY = 100)
        val shooter = testTank(id = 1, ownerId = 1, x = 500f)
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, ceilingType = EdgeType.WRAP, rng = Random(1))
        engine.ceilingWrapDepthY = 175f
        shooter.angleDeg = 90f
        shooter.power = 50f
        engine.fire()

        var ticks = 0
        while (engine.projectiles.isNotEmpty() && ticks < 200) {
            engine.tick(1f / 60f)
            ticks++
        }

        assertTrue("expected the wrapped projectile to detonate", engine.projectiles.isEmpty())
        assertTrue(
            "expected the explosion at the overridden depth, not the default terrain height",
            engine.impactEffects.any { it.y in 174f..176f },
        )
        // The hole's own full height (2 * blastRadius = 56) is still the bottleneck here, not
        // the override's own depth (75 units of material sit above it, plenty) - same result as
        // the default-depth test above: 100 + 56 = 156. The impactEffects assertion above is
        // what actually proves the override is honored (300 vs. 175); this just confirms the
        // collapse math doesn't change depending on how deep the override happens to be.
        assertEquals(156, terrain.groundY[shooter.x.toInt()])
    }

    @Test
    fun `ceiling type Wrap drops a tall peak's terrain down by the explosion's own size`() {
        val width = 1000
        val height = 1000
        val peakX = 500
        val groundY = IntArray(width) { x -> if (x == peakX) 200 else 900 }
        val terrain = HeightMap(width, height, groundY)
        val shooter = testTank(id = 1, ownerId = 1, x = peakX.toFloat())
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, ceilingType = EdgeType.WRAP, rng = Random(1))
        shooter.angleDeg = 90f
        shooter.power = 80f
        engine.fire()

        var ticks = 0
        while (engine.projectiles.isNotEmpty() && ticks < 200) {
            engine.tick(1f / 60f)
            ticks++
        }

        assertTrue("expected the wrapped projectile to detonate", engine.projectiles.isEmpty())
        // 800 units of material sit above the blast's own circle (the default
        // ceilingWrapDepthY, terrain.height=1000, puts it entirely below the peak's own
        // surface) - far more than the hole's own full height (2 * blastRadius = 56) at the
        // peak's own column - so it drops by exactly that much: 200 + 56 = 256, not an erasure
        // down to some fixed depth.
        assertEquals(256, terrain.groundY[peakX])
        assertEquals("expected terrain far outside the blast radius to be untouched", 900, terrain.groundY[peakX + 200])
    }

    @Test
    fun `death sequence enters an exploding state before turning to ash`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val a = testTank(id = 1, ownerId = 1, x = 300f)
        val b = testTank(id = 2, ownerId = 2, x = 500f)
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 0f, rng = Random(1))
        // Turn order is randomized - whichever tank actually goes first is this test's
        // "shooter" (kept at full health), and the other one is the "target" that needs to
        // die from the shot, so its health is only dropped to 1 now that we know which
        // tank that is (never the one about to fire a shot of its own).
        val shooter = engine.currentTank!!
        val target = if (shooter === a) b else a
        target.health = 1

        val idealPower = CpuAimCalculator.solveIdealPower(shooter, target, terrain, engine.wind)
        // Mirrors CpuAimCalculator's own private towardTargetAngleDeg - idealPower was solved
        // assuming this same mirrored angle, so it must match here regardless of which tank
        // (now decided by the randomized turn order, not always the one at the lower x) ends
        // up as the shooter.
        shooter.angleDeg = if (target.x >= shooter.x) 45f else 135f
        shooter.power = idealPower
        assertTrue(engine.fire())

        var ticks = 0
        while (!target.exploding && ticks < 1000) {
            engine.tick(1f / 60f)
            ticks++
        }

        assertTrue("expected the tank to reach the exploding state", target.exploding)
        assertFalse(target.isAsh)
        assertFalse(target.awaitingExplosion)
    }

    @Test
    fun `tank becomes ash only after the death explosion's growth phase completes`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val a = testTank(id = 1, ownerId = 1, x = 300f)
        val b = testTank(id = 2, ownerId = 2, x = 500f)
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 0f, rng = Random(1))
        val shooter = engine.currentTank!!
        val target = if (shooter === a) b else a
        target.health = 1

        val idealPower = CpuAimCalculator.solveIdealPower(shooter, target, terrain, engine.wind)
        // Mirrors CpuAimCalculator's own private towardTargetAngleDeg - idealPower was solved
        // assuming this same mirrored angle, so it must match here regardless of which tank
        // (now decided by the randomized turn order, not always the one at the lower x) ends
        // up as the shooter.
        shooter.angleDeg = if (target.x >= shooter.x) 45f else 135f
        shooter.power = idealPower
        assertTrue(engine.fire())

        var ticks = 0
        while (!target.exploding && ticks < 1000) {
            engine.tick(1f / 60f)
            ticks++
        }
        assertTrue("expected the tank to reach the exploding state", target.exploding)

        // DEATH_EXPLOSION_GROWTH_SECONDS (0.125s) at 60fps is ~7.5 ticks - a small bounded
        // window is enough to see it complete without masking a regression that skips ahead.
        var growthTicks = 0
        while (target.exploding && growthTicks < 10) {
            engine.tick(1f / 60f)
            growthTicks++
        }

        assertTrue("expected the tank to become ash within the explosion's growth window", target.isAsh)
        assertFalse(target.exploding)
    }

    @Test
    fun `death explosion still fires at the same moment, only isAsh is deferred`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val a = testTank(id = 1, ownerId = 1, x = 300f)
        val b = testTank(id = 2, ownerId = 2, x = 500f)
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 0f, rng = Random(1))
        val shooter = engine.currentTank!!
        val target = if (shooter === a) b else a
        target.health = 1

        val idealPower = CpuAimCalculator.solveIdealPower(shooter, target, terrain, engine.wind)
        // Mirrors CpuAimCalculator's own private towardTargetAngleDeg - idealPower was solved
        // assuming this same mirrored angle, so it must match here regardless of which tank
        // (now decided by the randomized turn order, not always the one at the lower x) ends
        // up as the shooter.
        shooter.angleDeg = if (target.x >= shooter.x) 45f else 135f
        shooter.power = idealPower
        assertTrue(engine.fire())

        val terrainBefore = terrain.groundY.copyOf()
        var ticks = 0
        while (!target.exploding && ticks < 1000) {
            engine.tick(1f / 60f)
            ticks++
        }
        assertTrue("expected the tank to reach the exploding state", target.exploding)

        assertTrue(
            "expected the death explosion event to already have fired",
            engine.drainEvents().any { it is GameEvent.TankExploded && it.tankId == target.id },
        )
        val nearTarget = ((target.x.toInt() - 50).coerceAtLeast(0))..((target.x.toInt() + 50).coerceAtMost(terrain.width - 1))
        assertTrue(
            "expected the death explosion to already have carved terrain near the tank",
            nearTarget.any { x -> terrain.groundY[x] > terrainBefore[x] },
        )
    }
}
