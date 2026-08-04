package com.scorchedphoto.engine

import com.scorchedphoto.engine.ai.CpuAimCalculator
import com.scorchedphoto.engine.combat.WeaponType
import com.scorchedphoto.engine.tanks.testTank
import com.scorchedphoto.terrain.HeightMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Covers [GameEngine.deathLog]/[DeathRecord] - the kill-attribution history multi-game
 * tournament scoring (see the :app `tournament` package) is built from. See [Tank]'s and
 * [DeathRecord]'s own docs, and [GameEngine.resolveImpact]'s doc for the full attribution
 * rules this exercises. */
class GameEngineDeathLogTest {

    private fun flatTerrain(width: Int, groundY: Int) = HeightMap(width, groundY + 200, IntArray(width) { groundY })

    private fun runUntilNotResolving(engine: GameEngine, dt: Float = 1f / 60f, maxTicks: Int = 20_000) {
        var ticks = 0
        while ((engine.phase == MatchPhase.FIRING || engine.phase == MatchPhase.RESOLVING) && ticks < maxTicks) {
            engine.tick(dt)
            ticks++
        }
        assertTrue("engine never left FIRING/RESOLVING within $maxTicks ticks", ticks < maxTicks)
    }

    /** See [GameEngineWeaponMechanicsTest]'s own identical helper. */
    private fun fireAtX(engine: GameEngine, targetX: Float) {
        val shooter = engine.currentTank!!
        val toRight = targetX >= shooter.x
        val distance = abs(targetX - shooter.x)
        val sin2theta = sin(Math.toRadians(90.0)).toFloat()
        val speed = sqrt((distance * com.scorchedphoto.engine.physics.GRAVITY / sin2theta).toDouble()).toFloat()
        shooter.angleDeg = if (toRight) 45f else 135f
        shooter.power = (speed / com.scorchedphoto.engine.physics.POWER_SCALE).coerceIn(1f, 100f)
        engine.fire()
        runUntilNotResolving(engine)
    }

    /** See [GameEngineFloorTypeTest]'s own identical helper. */
    private fun fireSafeFillerShot(engine: GameEngine) {
        val current = engine.currentTank!!
        current.angleDeg = if (current.x < engine.terrain.width / 2f) 135f else 45f
        current.power = 30f
        engine.fire()
        runUntilNotResolving(engine)
    }

    @Test
    fun `a direct hit kill records the shooter as killedByOwnerId`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val t1 = testTank(id = 1, ownerId = 1, x = 300f, health = 1000)
        val t2 = testTank(id = 2, ownerId = 2, x = 500f, health = 1000)
        val engine = GameEngine(terrain, listOf(t1, t2), maxWindMagnitude = 0f, rng = Random(1))
        val shooter = engine.currentTank!!
        val target = if (shooter === t1) t2 else t1
        target.health = 1
        val idealPower = CpuAimCalculator.solveIdealPower(shooter, target, terrain, engine.wind)
        shooter.angleDeg = if (target.x >= shooter.x) 45f else 135f
        shooter.power = idealPower

        engine.fire()
        runUntilNotResolving(engine)

        assertFalse(target.alive)
        val record = engine.deathLog.single()
        assertEquals(target.ownerId, record.ownerId)
        assertEquals(shooter.ownerId, record.killedByOwnerId)
        assertEquals(1, record.turnNumber)
    }

    @Test
    fun `one blast killing two tanks records both with the same impactId and turnNumber`() {
        val terrain = flatTerrain(width = 2000, groundY = 1000)
        val t1 = testTank(id = 1, ownerId = 1, x = 200f, health = 1000)
        val t2 = testTank(id = 2, ownerId = 2, x = 200f, health = 1000)
        val t3 = testTank(id = 3, ownerId = 3, x = 200f, health = 1000)
        val engine = GameEngine(terrain, listOf(t1, t2, t3), maxWindMagnitude = 0f, rng = Random(1))
        val shooter = engine.currentTank!!
        val victims = listOf(t1, t2, t3).filter { it !== shooter }
        // Repositioned after roles are known (turn order is randomized) - see GameEngineTest's
        // own "direct hit deals splash damage" note on why this is safe on flat terrain.
        shooter.x = 200f
        victims[0].x = 1000f
        victims[0].health = 1
        victims[1].x = 1050f
        victims[1].health = 1
        val targetX = (victims[0].x + victims[1].x) / 2f
        shooter.currentWeapon = WeaponType.NUKE

        fireAtX(engine, targetX)

        assertFalse(victims[0].alive)
        assertFalse(victims[1].alive)
        val victimOwnerIds = victims.map { it.ownerId }.toSet()
        val records = engine.deathLog.filter { it.ownerId in victimOwnerIds }
        assertEquals(2, records.size)
        assertEquals(records[0].impactId, records[1].impactId)
        assertEquals(records[0].turnNumber, records[1].turnNumber)
        assertEquals(shooter.ownerId, records[0].killedByOwnerId)
        assertEquals(shooter.ownerId, records[1].killedByOwnerId)
    }

    @Test
    fun `a self-kill records killedByOwnerId equal to the shooter's own owner id`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val t1 = testTank(id = 1, ownerId = 1, x = 300f, health = 1)
        val t2 = testTank(id = 2, ownerId = 2, x = 900f, health = 1000)
        val engine = GameEngine(terrain, listOf(t1, t2), maxWindMagnitude = 0f, rng = Random(1))
        val shooter = engine.currentTank!!
        shooter.health = 1
        // Straight up, no wind - lands back at essentially the shooter's own x, so its own
        // splash/bullseye damage reaches itself, the only way a self-kill happens (the
        // projectile's own direct-touch check explicitly excludes its owner tank).
        shooter.angleDeg = 90f
        shooter.power = 5f

        engine.fire()
        runUntilNotResolving(engine)

        assertFalse(shooter.alive)
        val record = engine.deathLog.single { it.ownerId == shooter.ownerId }
        assertEquals(shooter.ownerId, record.killedByOwnerId)
    }

    @Test
    fun `a chain of two death explosions both credit the original shooter`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val t1 = testTank(id = 1, ownerId = 1, x = 200f, health = 1000)
        val t2 = testTank(id = 2, ownerId = 2, x = 200f, health = 1000)
        val t3 = testTank(id = 3, ownerId = 3, x = 200f, health = 1000)
        val engine = GameEngine(terrain, listOf(t1, t2, t3), maxWindMagnitude = 0f, rng = Random(1))
        val shooter = engine.currentTank!!
        val others = listOf(t1, t2, t3).filter { it !== shooter }
        val a = others[0]
        val b = others[1]
        // Repositioning after construction is safe on this flat terrain - see
        // GameEngineTest's own identical note.
        shooter.x = 200f
        a.x = 500f
        a.health = 1
        // 30px from a - outside Widowmaker's own small blast reach (~20.5px) so b survives
        // the original shot, but well within the death explosion's larger reach (~42px), so
        // it dies only from a's own chain explosion, a separate blast.
        b.x = 530f
        b.health = 1

        shooter.currentWeapon = WeaponType.WIDOWMAKER
        val idealPower = CpuAimCalculator.solveIdealPower(shooter, a, terrain, engine.wind)
        shooter.angleDeg = if (a.x >= shooter.x) 45f else 135f
        shooter.power = idealPower

        engine.fire()
        runUntilNotResolving(engine)

        assertFalse(a.alive)
        assertFalse(b.alive)
        val aRecord = engine.deathLog.single { it.ownerId == a.ownerId }
        val bRecord = engine.deathLog.single { it.ownerId == b.ownerId }
        assertEquals(shooter.ownerId, aRecord.killedByOwnerId)
        assertEquals(shooter.ownerId, bRecord.killedByOwnerId)
        assertTrue(
            "expected b's death to come from a's own chain explosion, a separate blast from the original shot",
            bRecord.impactId != aRecord.impactId,
        )
    }

    @Test
    fun `a DoT death credits whoever applied the burn, even after an unrelated interim hit changes creditOwnerId`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val t1 = testTank(id = 1, ownerId = 1, x = 300f, health = 1000)
        val t2 = testTank(id = 2, ownerId = 2, x = 700f, health = 1000)
        val t3 = testTank(id = 3, ownerId = 3, x = 700f, health = 1000)
        val engine = GameEngine(terrain, listOf(t1, t2, t3), maxWindMagnitude = 0f, rng = Random(1))
        val shooter1 = engine.currentTank!!
        val others = listOf(t1, t2, t3).filter { it !== shooter1 }
        val victim = others[0]
        val shooter2 = others[1]
        // Repositioned after roles are known (turn order is randomized) - see
        // GameEngineTest's own "direct hit deals splash damage" note on why this is safe on
        // flat terrain. Without this, shooter1 could itself land on whichever of t2/t3's
        // original x it started at, overlapping victim's own reassigned position below.
        shooter1.x = 300f
        victim.x = 700f
        shooter2.x = 750f

        shooter1.currentWeapon = WeaponType.NAPALM
        // Near the edge of Napalm's own blast radius - survivable splash, mirroring
        // GameEngineWeaponMechanicsTest's own Napalm setup.
        fireAtX(engine, victim.x + 30f)

        assertTrue("expected the victim to survive the initial blast", victim.alive)
        assertEquals(shooter1.ownerId, victim.dotSourceOwnerId)

        // Simulates a later, unrelated hit from a different tank changing creditOwnerId
        // without killing the victim - dotSourceOwnerId must stay independent of this.
        victim.creditOwnerId = shooter2.ownerId
        // The next DoT tick finishes the victim off exactly.
        victim.health = victim.dotDamagePerRound

        // Advances one full round of the remaining 3 tanks so applyPendingDotDamage ticks once.
        fireSafeFillerShot(engine)
        fireSafeFillerShot(engine)

        assertFalse(victim.alive)
        val record = engine.deathLog.single { it.ownerId == victim.ownerId }
        assertEquals(shooter1.ownerId, record.killedByOwnerId)
        assertTrue(record.killedByOwnerId != shooter2.ownerId)
    }

    @Test
    fun `fall damage from ground a blast removed is attributed to that blast's shooter`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val t1 = testTank(id = 1, ownerId = 1, x = 300f, health = 1000)
        val t2 = testTank(id = 2, ownerId = 2, x = 700f, health = 1000)
        val engine = GameEngine(terrain, listOf(t1, t2), maxWindMagnitude = 0f, rng = Random(1))
        val shooter = engine.currentTank!!
        val victim = if (shooter === t1) t2 else t1
        shooter.currentWeapon = WeaponType.STANDARD_SHELL
        // Real, survivable splash within Standard Shell's own reach (~38.5px) - stamps
        // victim.creditOwnerId via the engine's own resolveImpact loop, without killing it.
        fireAtX(engine, victim.x + 20f)

        assertTrue(victim.alive)
        assertEquals(shooter.ownerId, victim.creditOwnerId)

        // Simulates that same blast having also carved away the ground under the victim - a
        // whole neighborhood of columns, not just victim's own exact one, matching a real
        // crater's actual footprint (CraterCarver.blastRadius) - a single hollowed column
        // would create an unrealistic knife-edge pit that the harmless filler shot below
        // (fired by whoever's turn it now is, which may well be the victim itself) could
        // detonate against just one pixel away from its own launch point, right back on
        // ordinary terrain. CraterCarver's own exact depth math isn't what this test is
        // about, only that a fall triggered while creditOwnerId is still fresh gets
        // attributed to it.
        val victimColumn = victim.x.toInt()
        for (dx in -40..40) {
            terrain.groundY[(victimColumn + dx).coerceIn(0, terrain.width - 1)] = terrain.height - 5
        }
        victim.health = 5

        // More ticks (via any real shot) let applyTankGravity notice the changed terrain.
        fireSafeFillerShot(engine)

        assertFalse(victim.alive)
        val record = engine.deathLog.single { it.ownerId == victim.ownerId }
        assertEquals(shooter.ownerId, record.killedByOwnerId)
    }

    @Test
    fun `a fall-through with no attributable blast ever touching the tank is unattributed`() {
        val terrain = flatTerrain(width = 200, groundY = 50)
        val victim = testTank(id = 1, ownerId = 1, x = 100f, health = 1000)
        val other = testTank(id = 2, ownerId = 2, x = 190f, health = 1000)
        val engine = GameEngine(terrain, listOf(victim, other), maxWindMagnitude = 0f, floorType = FloorType.HOLE, rng = Random(1))
        // Pre-hollowed with no live shot ever touching this column - simulates a naturally
        // spread hazard reaching a tank that was never blasted (see GameEngineFloorTypeTest's
        // own identical setup for the non-attribution-aware version of this scenario).
        terrain.groundY[victim.x.toInt()] = terrain.height

        val firer = engine.currentTank!!
        firer.angleDeg = 90f
        firer.power = 1f
        engine.fire()

        var ticks = 0
        while (victim.alive && ticks < 400) {
            engine.tick(1f / 60f)
            ticks++
        }

        assertFalse(victim.alive)
        val record = engine.deathLog.single { it.ownerId == victim.ownerId }
        assertNull(record.killedByOwnerId)
    }

    @Test
    fun `two kills separated by an intervening turn are recorded with different turnNumbers`() {
        val terrain = flatTerrain(width = 1000, groundY = 500)
        val t1 = testTank(id = 1, ownerId = 1, x = 100f, health = 1000)
        val t2 = testTank(id = 2, ownerId = 2, x = 400f, health = 1)
        val t3 = testTank(id = 3, ownerId = 3, x = 700f, health = 1)
        val engine = GameEngine(terrain, listOf(t1, t2, t3), maxWindMagnitude = 0f, rng = Random(1))

        var guard = 0
        while ((t2.alive || t3.alive) && guard < 20) {
            val current = engine.currentTank!!
            if (current === t1) {
                val target = if (t2.alive) t2 else t3
                val idealPower = CpuAimCalculator.solveIdealPower(t1, target, terrain, engine.wind)
                t1.angleDeg = if (target.x >= t1.x) 45f else 135f
                t1.power = idealPower
            } else {
                // Fires away from its own side of the map at modest power - can neither
                // self-splash (unlike a straight-up shot) nor reach the other victim.
                current.angleDeg = if (current.x < terrain.width / 2f) 135f else 45f
                current.power = 30f
            }
            engine.fire()
            runUntilNotResolving(engine)
            guard++
        }

        assertFalse(t2.alive)
        assertFalse(t3.alive)
        val records = engine.deathLog
        assertEquals(2, records.size)
        assertTrue("expected the two kills to land on different turns", records[0].turnNumber != records[1].turnNumber)
    }
}
