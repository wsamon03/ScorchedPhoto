package com.scorchedphoto.engine

import com.scorchedphoto.engine.combat.DamageCalculator
import com.scorchedphoto.engine.combat.WeaponCatalog
import com.scorchedphoto.engine.combat.WeaponType
import com.scorchedphoto.engine.physics.GRAVITY
import com.scorchedphoto.engine.physics.POWER_SCALE
import com.scorchedphoto.engine.physics.launchVelocity
import com.scorchedphoto.engine.tanks.Tank
import com.scorchedphoto.engine.tanks.testTank
import com.scorchedphoto.terrain.HeightMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Covers the three genuinely new mechanics the 10-weapon roster expansion introduced -
 * Spread MIRV's horizontal split geometry, Nuke/Napalm's damage-over-time, and Earthmover's
 * terrain fill - as opposed to the purely-stat weapons, which need no dedicated coverage
 * beyond [com.scorchedphoto.engine.combat.WeaponCatalogTest]'s own field assertions. */
class GameEngineWeaponMechanicsTest {

    private fun flatTerrain(width: Int, groundY: Int) = HeightMap(width, groundY + 200, IntArray(width) { groundY })

    private fun runUntilNotResolving(engine: GameEngine, dt: Float = 1f / 60f, maxTicks: Int = 20_000) {
        var ticks = 0
        while ((engine.phase == MatchPhase.FIRING || engine.phase == MatchPhase.RESOLVING) && ticks < maxTicks) {
            engine.tick(dt)
            ticks++
        }
        assertTrue("engine never left FIRING/RESOLVING within $maxTicks ticks", ticks < maxTicks)
    }

    /** Lands the current tank's shot exactly at [targetX] on flat ground - see
     * [com.scorchedphoto.engine.GameEngineFloorTypeTest]'s own identical helper. */
    private fun fireAtX(engine: GameEngine, targetX: Float) {
        val shooter = engine.currentTank!!
        val toRight = targetX >= shooter.x
        val distance = abs(targetX - shooter.x)
        val sin2theta = sin(Math.toRadians(90.0)).toFloat() // sin(2 * 45 degrees)
        val speed = sqrt((distance * GRAVITY / sin2theta).toDouble()).toFloat()
        shooter.angleDeg = if (toRight) 45f else 135f
        shooter.power = (speed / POWER_SCALE).coerceIn(1f, 100f)
        engine.fire()
        runUntilNotResolving(engine)
    }

    /** A real, non-self-destructive filler shot for tests that care about round progression -
     * see [com.scorchedphoto.engine.GameEngineFloorTypeTest]'s own identical helper. */
    private fun fireSafeFillerShot(engine: GameEngine) {
        val current = engine.currentTank!!
        current.angleDeg = if (current.x < engine.terrain.width / 2f) 135f else 45f
        current.power = 30f
        engine.fire()
        runUntilNotResolving(engine)
    }

    // --- Spread MIRV: horizontal-line split geometry ------------------------------------------

    @Test
    fun `Spread MIRV splits into 5 children in a horizontal line, vy unchanged and vx fanned around the parent's own`() {
        val terrain = flatTerrain(width = 2000, groundY = 1000)
        val a = testTank(id = 1, ownerId = 1, x = 500f)
        val b = testTank(id = 2, ownerId = 2, x = 1500f)
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 0f, rng = Random(1))
        val shooter = engine.currentTank!!
        shooter.currentWeapon = WeaponType.SPREAD_MIRV
        shooter.angleDeg = 45f
        shooter.power = 40f
        assertTrue(engine.fire())

        var ticks = 0
        while (engine.projectiles.size <= 1 && ticks < 10_000) {
            engine.tick(1f / 60f)
            ticks++
        }
        assertTrue("expected the split to actually happen", ticks < 10_000)

        val children = engine.projectiles
        assertEquals(5, children.size)

        // No wind, so the parent's own vx never changes between launch and apex/split.
        val (launchVx, _) = launchVelocity(shooter.angleDeg, shooter.power)
        val step = WeaponCatalog.SPREAD_MIRV.horizontalSpreadSpeed
        val expectedVx = listOf(-2, -1, 0, 1, 2).map { launchVx + it * step }
        // Every expected offset has exactly one matching child, and vice versa - a real
        // one-to-one pairing, not just "each actual value is near some expected value."
        val remainingExpected = expectedVx.toMutableList()
        for (vx in children.map { it.vx }) {
            val match = remainingExpected.firstOrNull { abs(it - vx) < 0.5f }
            assertTrue("no expected vx matched actual child vx=$vx (remaining: $remainingExpected)", match != null)
            remainingExpected.remove(match)
        }
        assertTrue("expected every offset to be matched, but $remainingExpected were left over", remainingExpected.isEmpty())

        // Every child keeps the parent's own vy at the moment of split, unlike Cluster MIRV's
        // radial fan (which spreads vy too).
        val firstVy = children.first().vy
        assertTrue(children.all { abs(it.vy - firstVy) < 0.01f })
    }

    // --- Nuke/Napalm: damage-over-time -----------------------------------------------------

    @Test
    fun `a Nuke hit that doesn't kill sets 3 rounds of pending radiation, ticking once per round then stopping`() {
        // 400px apart - close enough that a 45-degree shot can actually reach at an
        // achievable power (max range at full power/45 degrees is ~1056px here).
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val t1 = testTank(id = 1, ownerId = 1, x = 300f, health = 1000)
        val t2 = testTank(id = 2, ownerId = 2, x = 700f, health = 1000)
        val engine = GameEngine(terrain, listOf(t1, t2), maxWindMagnitude = 0f, rng = Random(1))
        val shooter = engine.currentTank!!
        val victim = if (shooter === t1) t2 else t1
        shooter.currentWeapon = WeaponType.NUKE
        // Just off-center - within reach of Nuke's huge blast radius, but outside the bullseye
        // zone, so this is ordinary (survivable) damage, not an instant kill.
        fireAtX(engine, victim.x + Tank.RADIUS + 5f)
        // Only the seed shot uses Nuke (1 ammo) - every later filler shot below needs a weapon
        // with ammo left, mirroring GameEngineFloorTypeTest's own Big Bertha seed-shot pattern.
        shooter.currentWeapon = WeaponType.STANDARD_SHELL

        val healthAfterBlast = victim.health
        assertTrue("expected the Nuke to actually damage the victim", healthAfterBlast < 1000)
        assertTrue("expected the victim to survive the initial blast", victim.alive)
        assertEquals(3, victim.dotRoundsRemaining)
        val dotDamage = victim.dotDamagePerRound
        assertEquals(DamageCalculator.percentOfMaxHealth(WeaponCatalog.NUKE.dotFraction), dotDamage)

        fireSafeFillerShot(engine) // victim's turn - completes round 1
        assertEquals(2, victim.dotRoundsRemaining)
        assertEquals(healthAfterBlast - dotDamage, victim.health)

        fireSafeFillerShot(engine) // shooter's turn (round 2, turn 1)
        fireSafeFillerShot(engine) // victim's turn - completes round 2
        assertEquals(1, victim.dotRoundsRemaining)
        assertEquals(healthAfterBlast - 2 * dotDamage, victim.health)

        fireSafeFillerShot(engine) // round 3, turn 1
        fireSafeFillerShot(engine) // completes round 3
        assertEquals(0, victim.dotRoundsRemaining)
        val healthAfterThreeRounds = healthAfterBlast - 3 * dotDamage
        assertEquals(healthAfterThreeRounds, victim.health)

        // A 4th round passes with no more radiation ticks.
        fireSafeFillerShot(engine)
        fireSafeFillerShot(engine)
        assertEquals(healthAfterThreeRounds, victim.health)
    }

    // --- Economy: damageDealtByOwner tracking ----------------------------------------------

    @Test
    fun `damageDealtByOwner credits the shooter for splash damage dealt to an enemy`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val t1 = testTank(id = 1, ownerId = 1, x = 300f, health = 1000)
        val t2 = testTank(id = 2, ownerId = 2, x = 700f, health = 1000)
        val engine = GameEngine(terrain, listOf(t1, t2), maxWindMagnitude = 0f, rng = Random(1))
        val shooter = engine.currentTank!!
        val victim = if (shooter === t1) t2 else t1
        val healthBefore = victim.health

        fireAtX(engine, victim.x)

        // Not necessarily an exact match: the same blast that damages the victim can also carve
        // ground out from under them, adding a little uncredited fall damage on top of the
        // weapon's own blast damage (deliberately excluded from creditDamage - see its own
        // doc) - so credited damage is real and positive, but only ever <= the victim's total
        // health lost, never more.
        val totalHealthLost = healthBefore - victim.health
        val credited = engine.damageDealtByOwner[shooter.ownerId] ?: 0
        assertTrue("expected the shot to have actually damaged the victim", totalHealthLost > 0)
        assertTrue("expected the shooter to be credited for some of the damage", credited > 0)
        assertTrue(
            "expected credited damage ($credited) to never exceed total health lost ($totalHealthLost)",
            credited <= totalHealthLost,
        )
    }

    @Test
    fun `damageDealtByOwner never credits self-inflicted damage`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val t1 = testTank(id = 1, ownerId = 1, x = 300f, health = 1000)
        val t2 = testTank(id = 2, ownerId = 2, x = 900f, health = 1000)
        val engine = GameEngine(terrain, listOf(t1, t2), maxWindMagnitude = 0f, rng = Random(1))
        val shooter = engine.currentTank!!
        val healthBefore = shooter.health

        // A steep, weak shot arcs right back down onto the shooter's own position - a
        // guaranteed self-hit (see GameEngineTest's own ammoLimit test for the same trick,
        // deliberately avoided there but deliberately used here).
        shooter.angleDeg = 80f
        shooter.power = 5f
        engine.fire()
        runUntilNotResolving(engine)

        assertTrue("expected the shooter to have actually damaged itself", shooter.health < healthBefore)
        assertEquals(null, engine.damageDealtByOwner[shooter.ownerId])
    }

    @Test
    fun `damageDealtByOwner credits only the tank's actual health removed, not the weapon's full maxDamage, on an overkill hit`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val t1 = testTank(id = 1, ownerId = 1, x = 300f, health = 1000)
        val t2 = testTank(id = 2, ownerId = 2, x = 700f, health = 1000)
        val engine = GameEngine(terrain, listOf(t1, t2), maxWindMagnitude = 0f, rng = Random(1))
        val shooter = engine.currentTank!!
        val victim = if (shooter === t1) t2 else t1
        // A single point of health left - any real hit is a massive overkill relative to
        // WIDOWMAKER's own 90 maxDamage, so a credit of 90 (or anything above 1) would mean
        // the engine wrongly credited the weapon's raw power instead of what was actually
        // removed.
        victim.health = 1
        shooter.currentWeapon = WeaponType.WIDOWMAKER
        fireAtX(engine, victim.x)

        assertFalse(victim.alive)
        assertEquals(1, engine.damageDealtByOwner[shooter.ownerId])
    }

    @Test
    fun `damageDealtByOwner credits a Nuke DoT tick to dotSourceOwnerId, on top of the initial blast`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val t1 = testTank(id = 1, ownerId = 1, x = 300f, health = 1000)
        val t2 = testTank(id = 2, ownerId = 2, x = 700f, health = 1000)
        val engine = GameEngine(terrain, listOf(t1, t2), maxWindMagnitude = 0f, rng = Random(1))
        val shooter = engine.currentTank!!
        val victim = if (shooter === t1) t2 else t1
        shooter.currentWeapon = WeaponType.NUKE
        fireAtX(engine, victim.x + Tank.RADIUS + 5f)
        shooter.currentWeapon = WeaponType.STANDARD_SHELL

        val damageAfterBlast = engine.damageDealtByOwner[shooter.ownerId]
        assertTrue("expected the initial blast to already be credited", (damageAfterBlast ?: 0) > 0)
        assertTrue("expected the blast to leave a pending radiation tick to test", victim.dotRoundsRemaining > 0)

        val dotDamage = victim.dotDamagePerRound
        fireSafeFillerShot(engine) // completes round 1, applies the first DoT tick

        assertEquals(damageAfterBlast!! + dotDamage, engine.damageDealtByOwner[shooter.ownerId])
    }

    @Test
    fun `a Nuke kill never leaves a pending radiation tick on the dead tank`() {
        // 400px apart - close enough that a 45-degree shot can actually reach at an
        // achievable power (max range at full power/45 degrees is ~1056px here).
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val t1 = testTank(id = 1, ownerId = 1, x = 300f, health = 1000)
        val t2 = testTank(id = 2, ownerId = 2, x = 700f, health = 1000)
        val engine = GameEngine(terrain, listOf(t1, t2), maxWindMagnitude = 0f, rng = Random(1))
        val shooter = engine.currentTank!!
        val victim = if (shooter === t1) t2 else t1
        // Bare survival threshold, set only on the victim (a low-health shooter would also cap
        // its own max power too low to reach the victim) - Nuke's huge blast radius guarantees
        // real (non-zero) splash damage anywhere near it, without needing a precisely-aimed
        // bullseye (whose ~2px radius is too tight a target for a physics-simulated trajectory).
        victim.health = 1
        shooter.currentWeapon = WeaponType.NUKE
        fireAtX(engine, victim.x)

        assertFalse(victim.alive)
        assertEquals(0, victim.dotRoundsRemaining)
    }

    @Test
    fun `a fresh Nuke hit overwrites rather than stacks with an already-pending radiation tick`() {
        // 400px apart - close enough that a 45-degree shot can actually reach at an
        // achievable power (max range at full power/45 degrees is ~1056px here).
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val t1 = testTank(id = 1, ownerId = 1, x = 300f, health = 1000)
        val t2 = testTank(id = 2, ownerId = 2, x = 700f, health = 1000)
        val engine = GameEngine(terrain, listOf(t1, t2), maxWindMagnitude = 0f, rng = Random(1))
        val shooter = engine.currentTank!!
        val victim = if (shooter === t1) t2 else t1
        // Simulates an earlier, nearly-finished radiation tick from a previous hit.
        victim.dotRoundsRemaining = 1
        victim.dotDamagePerRound = 999

        shooter.currentWeapon = WeaponType.NUKE
        fireAtX(engine, victim.x + Tank.RADIUS + 5f)

        assertEquals(3, victim.dotRoundsRemaining)
        assertEquals(DamageCalculator.percentOfMaxHealth(WeaponCatalog.NUKE.dotFraction), victim.dotDamagePerRound)
    }

    @Test
    fun `a Napalm hit that doesn't kill sets dotBurning true, clearing it exactly when the DoT itself ends`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val t1 = testTank(id = 1, ownerId = 1, x = 300f, health = 1000)
        val t2 = testTank(id = 2, ownerId = 2, x = 700f, health = 1000)
        val engine = GameEngine(terrain, listOf(t1, t2), maxWindMagnitude = 0f, rng = Random(1))
        val shooter = engine.currentTank!!
        val victim = if (shooter === t1) t2 else t1
        shooter.currentWeapon = WeaponType.NAPALM
        // Near the edge of Napalm's own (much smaller than Nuke's) blast radius rather than
        // right on top of the victim - close enough for ordinary survivable damage, but far
        // enough that the crater it carves doesn't dig a pit directly under the victim's own
        // feet, which would otherwise land its very next "safe" filler shot back on itself.
        fireAtX(engine, victim.x + 30f)
        shooter.currentWeapon = WeaponType.STANDARD_SHELL

        assertTrue("expected the victim to survive the initial blast", victim.alive)
        assertEquals(4, victim.dotRoundsRemaining)
        assertTrue("expected Napalm's DoT to show a fire animation", victim.dotBurning)

        fireSafeFillerShot(engine) // victim's turn - completes round 1
        assertEquals(3, victim.dotRoundsRemaining)
        assertTrue(victim.dotBurning)

        repeat(2) {
            fireSafeFillerShot(engine) // shooter's turn
            fireSafeFillerShot(engine) // victim's turn - completes a round
        }
        assertEquals(1, victim.dotRoundsRemaining)
        assertTrue("expected dotBurning to stay true with a round still remaining", victim.dotBurning)

        fireSafeFillerShot(engine) // round 4, turn 1
        fireSafeFillerShot(engine) // completes round 4 - the last burn tick
        assertEquals(0, victim.dotRoundsRemaining)
        assertFalse("expected dotBurning to clear once the DoT itself ends", victim.dotBurning)
    }

    @Test
    fun `a Nuke hit never sets dotBurning - its radiation has no fire animation`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val t1 = testTank(id = 1, ownerId = 1, x = 300f, health = 1000)
        val t2 = testTank(id = 2, ownerId = 2, x = 700f, health = 1000)
        val engine = GameEngine(terrain, listOf(t1, t2), maxWindMagnitude = 0f, rng = Random(1))
        val shooter = engine.currentTank!!
        val victim = if (shooter === t1) t2 else t1
        shooter.currentWeapon = WeaponType.NUKE
        fireAtX(engine, victim.x + Tank.RADIUS + 5f)

        assertTrue(victim.dotRoundsRemaining > 0)
        assertFalse(victim.dotBurning)
    }

    // --- Earthmover: raises terrain instead of carving it --------------------------------------

    @Test
    fun `Earthmover raises a mound of terrain instead of carving a crater`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val a = testTank(id = 1, ownerId = 1, x = 300f)
        val b = testTank(id = 2, ownerId = 2, x = 700f)
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 0f, rng = Random(1))
        val shooter = engine.currentTank!!
        shooter.currentWeapon = WeaponType.EARTHMOVER
        val targetX = shooter.x + 100f
        fireAtX(engine, targetX)

        val impactColumn = targetX.toInt().coerceIn(0, terrain.width - 1)
        assertTrue(
            "expected Earthmover to raise terrain (a smaller groundY), not carve a crater",
            terrain.groundY[impactColumn] < 500,
        )
    }
}
