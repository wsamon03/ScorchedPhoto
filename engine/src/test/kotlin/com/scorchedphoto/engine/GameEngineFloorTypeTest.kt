package com.scorchedphoto.engine

import com.scorchedphoto.engine.combat.WeaponCatalog
import com.scorchedphoto.engine.combat.WeaponType
import com.scorchedphoto.engine.physics.GRAVITY
import com.scorchedphoto.engine.physics.POWER_SCALE
import com.scorchedphoto.engine.tanks.testTank
import com.scorchedphoto.terrain.HeightMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class GameEngineFloorTypeTest {

    private fun flatTerrain(width: Int, height: Int, groundY: Int) =
        HeightMap(width, height, IntArray(width) { groundY })

    private fun runUntilNotResolving(engine: GameEngine, dt: Float = 1f / 60f, maxTicks: Int = 20_000) {
        var ticks = 0
        while ((engine.phase == MatchPhase.FIRING || engine.phase == MatchPhase.RESOLVING) && ticks < maxTicks) {
            engine.tick(dt)
            ticks++
        }
        assertTrue("engine never left FIRING/RESOLVING within $maxTicks ticks", ticks < maxTicks)
    }

    /** A real, non-self-destructive filler shot for tests that care about tank survival/
     * counts - fires whoever's turn it currently is away from the map's own center, at
     * modest power, so it can neither land back on the firer itself (a real arc, unlike a
     * straight-up shot which always lands exactly where it launched - a guaranteed
     * self-bullseye) nor reach a tank on the opposite side of a widely-separated map. */
    private fun fireSafeFillerShot(engine: GameEngine) {
        val current = engine.currentTank!!
        current.angleDeg = if (current.x < engine.terrain.width / 2f) 135f else 45f
        current.power = 30f
        engine.fire()
        runUntilNotResolving(engine)
    }

    /** Fires the current tank at a computed power (45-degree arc, mirrored to 135 if
     * [targetX] is behind it) so the shot lands at [targetX] regardless of how far away
     * the tank is standing - used by the Void/Lava region-growth tests below to always
     * hit the same (fixed-center, growing-radius) region from two tanks parked far apart
     * and never moved, rather than relying on incidental overlap between two nearby
     * launch points (which risks an early direct hit on the other tank instead of a
     * clean shot into the region - see the "identical shot" approach this replaced). */
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

    // --- Floor-edge bounce/detonate/teleport/fizzle parity with wall/ceiling -----------------

    @Test
    fun `floor type Padded bounces a projectile off the true bottom like a wall would`() {
        val terrain = flatTerrain(width = 200, height = 200, groundY = 50)
        // Pre-open a column directly under the shooter, simulating an explosion having
        // already hollowed it out completely, so a straight-down shot can reach the map's
        // true bottom without needing a real explosion to carve it first.
        terrain.groundY[100] = terrain.height
        val shooter = testTank(id = 1, ownerId = 1, x = 100f)
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, floorType = FloorType.PADDED, rng = Random(1))
        shooter.angleDeg = 270f // straight down
        shooter.power = 50f
        engine.fire()

        var ticks = 0
        while (engine.bounceEffects.isEmpty() && ticks < 200) {
            engine.tick(1f / 60f)
            ticks++
        }

        assertTrue("expected the floor to reflect the projectile", engine.bounceEffects.isNotEmpty())
        assertTrue("expected the projectile still flying (bounced, not removed)", engine.projectiles.isNotEmpty())
        val p = engine.projectiles.single()
        assertEquals("expected the projectile clamped exactly onto the floor", terrain.height.toFloat(), p.y, 0.01f)
    }

    @Test
    fun `floor type Blast Steel detonates a projectile that reaches the true bottom`() {
        val terrain = flatTerrain(width = 200, height = 200, groundY = 50)
        terrain.groundY[100] = terrain.height
        val shooter = testTank(id = 1, ownerId = 1, x = 100f)
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, floorType = FloorType.BLAST_STEEL, rng = Random(1))
        shooter.angleDeg = 270f
        shooter.power = 50f
        engine.fire()

        var ticks = 0
        while (engine.projectiles.isNotEmpty() && ticks < 200) {
            engine.tick(1f / 60f)
            ticks++
        }

        assertTrue("expected the floor to detonate the projectile", engine.projectiles.isEmpty())
        assertTrue("expected a real explosion effect", engine.impactEffects.isNotEmpty())
        // Detonating right at the true bottom is inherently a "genuinely embedded" blast (see
        // CraterCarver's own doc) relative to anything above it - confirms floorMaxGroundY()
        // actually relaxed the clamp here, not just that *some* explosion happened.
        assertTrue(
            "expected the detonation to fully open the floor somewhere nearby",
            (72..128).any { x -> terrain.groundY[x] == terrain.height },
        )
    }

    @Test
    fun `floor type Wrap teleports the projectile to just below the top border and keeps it flying`() {
        val terrain = flatTerrain(width = 200, height = 200, groundY = 50)
        terrain.groundY[100] = terrain.height
        val shooter = testTank(id = 1, ownerId = 1, x = 100f)
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, floorType = FloorType.WRAP, rng = Random(1))
        engine.floorWrapDepthY = 12f
        shooter.angleDeg = 270f
        shooter.power = 50f
        engine.fire()

        var ticks = 0
        while (engine.bounceEffects.isEmpty() && ticks < 200) {
            engine.tick(1f / 60f)
            ticks++
        }

        assertTrue("expected the floor to wrap the projectile", engine.bounceEffects.isNotEmpty())
        assertTrue(
            "expected the projectile to keep flying, unlike ceiling-Wrap's detonate-immediately",
            engine.projectiles.isNotEmpty(),
        )
        assertEquals(12f, engine.projectiles.single().y, 0.01f)
    }

    @Test
    fun `floor type Hole fizzles a projectile that reaches the true bottom, with no explosion or damage`() {
        val terrain = flatTerrain(width = 200, height = 200, groundY = 50)
        terrain.groundY[100] = terrain.height
        val shooter = testTank(id = 1, ownerId = 1, x = 100f, health = 1000)
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, floorType = FloorType.HOLE, rng = Random(1))
        shooter.angleDeg = 270f
        shooter.power = 50f
        engine.fire()

        var ticks = 0
        while (engine.projectiles.isNotEmpty() && ticks < 200) {
            engine.tick(1f / 60f)
            ticks++
        }

        assertTrue("expected the projectile to vanish rather than linger", engine.projectiles.isEmpty())
        assertTrue("expected no explosion effect from a fizzle", engine.impactEffects.isEmpty())
        assertTrue("fizzle should not register as a scored impact", engine.drainEvents().none { it == GameEvent.Impact })
    }

    @Test
    fun `floor type Void also fizzles a projectile that reaches the true bottom over open terrain`() {
        val terrain = flatTerrain(width = 200, height = 200, groundY = 50)
        terrain.groundY[100] = terrain.height
        val shooter = testTank(id = 1, ownerId = 1, x = 100f, health = 1000)
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, floorType = FloorType.VOID, rng = Random(1))
        shooter.angleDeg = 270f
        shooter.power = 50f
        engine.fire()

        var ticks = 0
        while (engine.projectiles.isNotEmpty() && ticks < 200) {
            engine.tick(1f / 60f)
            ticks++
        }

        assertTrue("expected the projectile to vanish rather than linger", engine.projectiles.isEmpty())
        assertTrue("expected no explosion effect from a fizzle", engine.impactEffects.isEmpty())
    }

    // Ground's ordinary 95%-depth clamp itself is already covered directly by
    // CraterCarverTest (defaultMaxGroundY/omitting maxGroundY); floorMaxGroundY()'s
    // GROUND/WATER/LAVA-vs-everything-else branch is exercised end to end by every other test
    // in this file that constructs an engine with a non-Ground floor type and observes the
    // floor actually opening (see the Blast-Steel test above) or staying capped for ordinary
    // Lava impacts (see the Lava depth-cap test below).

    // --- Tank fall-through-the-floor death (Hole/Wrap/Void only) ----------------------------

    @Test
    fun `a tank that settles onto a fully open column under Hole dies instantly, skipping burn, explosion, and ash`() {
        val terrain = flatTerrain(width = 200, height = 200, groundY = 50)
        val victim = testTank(id = 1, ownerId = 1, x = 100f, health = 1000)
        val other = testTank(id = 2, ownerId = 2, x = 190f, health = 1000)
        val engine = GameEngine(terrain, listOf(victim, other), maxWindMagnitude = 0f, floorType = FloorType.HOLE, rng = Random(1))
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

        assertFalse("expected the tank to die from falling through the open floor", victim.alive)
        assertTrue("expected fallingThroughFloor to be set instead of the ordinary burn sequence", victim.fallingThroughFloor)
        assertFalse("expected no burn animation", victim.burning)
        assertFalse("expected no pendingBurn either", victim.pendingBurn)
        assertFalse("expected it to never reach isAsh", victim.isAsh)
    }

    @Test
    fun `a tank settling over a hollowed column under a bounce-type floor survives instead of falling through`() {
        val terrain = flatTerrain(width = 200, height = 200, groundY = 50)
        val survivor = testTank(id = 1, ownerId = 1, x = 100f, health = 1000)
        val other = testTank(id = 2, ownerId = 2, x = 190f, health = 1000)
        val engine = GameEngine(terrain, listOf(survivor, other), maxWindMagnitude = 0f, floorType = FloorType.PADDED, rng = Random(1))
        terrain.groundY[survivor.x.toInt()] = terrain.height

        val firer = engine.currentTank!!
        firer.angleDeg = 90f
        firer.power = 1f
        engine.fire()

        var ticks = 0
        while (ticks < 400) {
            engine.tick(1f / 60f)
            ticks++
        }

        assertTrue("expected the tank to survive resting at the true bottom", survivor.alive)
        assertFalse(survivor.fallingThroughFloor)
        assertEquals(terrain.height.toFloat(), survivor.y, 0.01f)
    }

    @Test
    fun `both tanks falling through the floor in the same round still declares a tie`() {
        val terrain = flatTerrain(width = 1000, height = 1000, groundY = 500)
        val a = testTank(id = 1, ownerId = 1, x = 300f, health = 1000)
        val b = testTank(id = 2, ownerId = 2, x = 700f, health = 1000)
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 0f, floorType = FloorType.HOLE, rng = Random(1))

        val firer = engine.currentTank!!
        firer.angleDeg = 90f
        firer.power = 1f
        engine.fire()

        terrain.groundY[a.x.toInt()] = terrain.height
        terrain.groundY[b.x.toInt()] = terrain.height

        runUntilNotResolving(engine)

        assertEquals(MatchPhase.GAME_OVER, engine.phase)
        assertFalse(a.alive)
        assertFalse(b.alive)
        val result = engine.winResult
        assertNotNull("expected a tie result instead of a stalled match with no winner", result)
        assertEquals(setOf(1, 2), result?.winningOwnerIds?.toSet())
        assertEquals(setOf(1, 2), result?.winningTankIds?.toSet())
    }

    // --- Round counting (one full lap of currently-alive tanks) -----------------------------

    @Test
    fun `a round processes exactly once per full lap of currently-alive tanks`() {
        val terrain = flatTerrain(width = 3000, height = 1000, groundY = 100)
        val a = testTank(id = 1, ownerId = 1, x = 500f, health = 1000)
        val b = testTank(id = 2, ownerId = 2, x = 2500f, health = 1000)
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 0f, floorType = FloorType.WATER, rng = Random(1))
        val initialLevel = engine.waterLevelY

        fireSafeFillerShot(engine) // 1 of 2 turns this round
        assertEquals("no round should have completed with only one of two tanks having gone", initialLevel, engine.waterLevelY, 0.001f)

        fireSafeFillerShot(engine) // 2 of 2 - the round completes here
        assertEquals(initialLevel - 0.02f * terrain.height, engine.waterLevelY, 0.001f)

        fireSafeFillerShot(engine) // 1 of 2 turns of the next round
        assertEquals(initialLevel - 0.02f * terrain.height, engine.waterLevelY, 0.001f)

        fireSafeFillerShot(engine) // 2 of 2 - the second round completes here
        assertEquals(initialLevel - 2 * 0.02f * terrain.height, engine.waterLevelY, 0.001f)
    }

    @Test
    fun `round counting stays robust when a tank dies mid-round, using the tank count from when the round started`() {
        val terrain = flatTerrain(width = 3000, height = 1000, groundY = 100)
        val a = testTank(id = 1, ownerId = 1, x = 500f, health = 1000)
        val b = testTank(id = 2, ownerId = 2, x = 1500f, health = 1000)
        val c = testTank(id = 3, ownerId = 3, x = 2500f, health = 1)
        val engine = GameEngine(terrain, listOf(a, b, c), maxWindMagnitude = 0f, floorType = FloorType.WATER, rng = Random(1))
        val initialLevel = engine.waterLevelY

        // A large, sudden drop under c - a fatal fall (unrelated to the floor type), killing
        // it partway through round 1 regardless of whose turn happens to go first (turn
        // order is randomized).
        terrain.groundY[c.x.toInt()] = 100 + 400

        // Round 1 was captured with 3 tanks alive at its start - per the engine's own design,
        // that count isn't recomputed mid-round just because c dies partway through, so this
        // round still takes 3 total completed turns (however they're distributed among the 2
        // tanks left alive) before it actually completes.
        fireSafeFillerShot(engine)
        assertFalse("expected c to have died from the fall by now", c.alive)
        assertEquals(initialLevel, engine.waterLevelY, 0.001f)

        fireSafeFillerShot(engine)
        assertEquals(initialLevel, engine.waterLevelY, 0.001f)

        fireSafeFillerShot(engine)
        assertEquals("expected round 1 to complete on its 3rd turn", initialLevel - 0.02f * terrain.height, engine.waterLevelY, 0.001f)

        // Round 2 is recomputed against the 2 tanks now alive - only 2 more turns needed.
        fireSafeFillerShot(engine)
        assertEquals(initialLevel - 0.02f * terrain.height, engine.waterLevelY, 0.001f)

        fireSafeFillerShot(engine)
        assertEquals(initialLevel - 2 * 0.02f * terrain.height, engine.waterLevelY, 0.001f)
    }

    @Test
    fun `a Water round that drowns the last standing side ends the match on that same turn`() {
        val terrain = flatTerrain(width = 3000, height = 1000, groundY = 950)
        val a = testTank(id = 1, ownerId = 1, x = 500f, health = 1000)
        val b = testTank(id = 2, ownerId = 2, x = 2500f, health = 1000)
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 0f, floorType = FloorType.WATER, rng = Random(1))

        // groundY=950, height=1000: both tanks rest at y~950 (RADIUS=7, so submersion needs
        // waterLevelY <= 943). Starting at 1000 and dropping 2%*1000=20/round, that's reached
        // after 3 full rounds (940 <= 943) - each round is 2 turns, so up to 8 filler shots.
        var guard = 0
        while (engine.phase != MatchPhase.GAME_OVER && guard < 12) {
            fireSafeFillerShot(engine)
            guard++
        }

        assertEquals("expected the match to actually end from drowning", MatchPhase.GAME_OVER, engine.phase)
        assertNotNull(engine.winResult)
        assertTrue("expected at least one tank to have drowned", !a.alive || !b.alive)
    }

    // --- Void: region seeding and per-round growth -------------------------------------------

    @Test
    fun `a Void region grows immediately upon creation, then by exactly 2 percent of terrain width per further completed round`() {
        val terrain = flatTerrain(width = 1000, height = 1000, groundY = 980)
        // Two tanks (two different owners) - a single-tank match would trivially "win" (one
        // remaining owner) the instant its first shot resolves, per TurnManager.checkWinCondition,
        // ending the match before a second round could ever happen. Parked at opposite edges of
        // the map and never moved, so however far the region ends up (and however wide it grows),
        // neither tank's own resting spot is ever near it or near the other tank - only the shots
        // themselves (aimed via fireAtX, below) travel to the region.
        val a = testTank(id = 1, ownerId = 1, x = 100f, health = 1000)
        val b = testTank(id = 2, ownerId = 2, x = 900f, health = 1000)
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 0f, floorType = FloorType.VOID, rng = Random(1))

        val seeder = engine.currentTank!!
        seeder.angleDeg = 45f
        seeder.power = 30f
        engine.fire()
        runUntilNotResolving(engine) // seeds the region (+ its own immediate growth)

        assertEquals(1, engine.voidRegions.size)
        val region = engine.voidRegions.single()
        val centerX = region.centerX
        val radiusAfterSeed = region.radius
        assertTrue(
            "expected the region to already be wider than its own seed blast radius",
            radiusAfterSeed > WeaponCatalog.STANDARD_SHELL.blastRadius,
        )

        // Whichever tank goes next fires straight at the region's own (fixed) center - it
        // fizzles through the already-open floor (see the Hole/Void fizzle tests above) rather
        // than re-seeding, but completing this turn also completes the round (2 tanks, 2 turns),
        // growing the existing region regardless of how this particular shot resolved.
        fireAtX(engine, centerX)

        assertEquals("expected no second region to have been seeded", 1, engine.voidRegions.size)
        val radiusAfterRound = engine.voidRegions.single().radius
        assertEquals(0.02f * terrain.width, radiusAfterRound - radiusAfterSeed, 1f)
    }

    @Test
    fun `Void region growth pushes no ImpactEffect - growing a region is silent and invisible`() {
        val terrain = flatTerrain(width = 1000, height = 1000, groundY = 980)
        // Same setup as the growth test above - two tanks parked far apart and never moved, so a
        // round can actually complete (2 tanks, 2 turns) with no collision risk.
        val a = testTank(id = 1, ownerId = 1, x = 100f, health = 1000)
        val b = testTank(id = 2, ownerId = 2, x = 900f, health = 1000)
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 0f, floorType = FloorType.VOID, rng = Random(1))

        val seeder = engine.currentTank!!
        seeder.angleDeg = 45f
        seeder.power = 30f
        engine.fire()
        runUntilNotResolving(engine) // seeds the region (+ its own immediate growth)

        val centerX = engine.voidRegions.single().centerX
        val radiusBeforeRound = engine.voidRegions.single().radius
        fireAtX(engine, centerX) // completes the round (2 tanks, 2 turns), growing the region again

        // The round-boundary growth happens at the very tail of the resolving tick, right as
        // phase leaves RESOLVING - tick() becomes a no-op once phase is AIMING/GAME_OVER, so an
        // ImpactEffect pushed there would never get a chance to age out and would sit frozen in
        // engine.impactEffects forever. Asserting it's empty here is a real regression check
        // (it would fail if growRegion still pushed one), not just an artifact of effects having
        // had time to age out naturally.
        assertTrue(
            "expected round-boundary region growth to push no ImpactEffect at all",
            engine.impactEffects.isEmpty(),
        )
        assertTrue(
            "expected the region to still have grown from the round boundary despite no visible effect",
            engine.voidRegions.single().radius > radiusBeforeRound,
        )
    }

    // --- Lava: depth-capped growth and round damage ------------------------------------------

    @Test
    fun `a Lava region never carves deeper than its own 4 percent depth cap, however many rounds it grows for`() {
        val terrain = flatTerrain(width = 1000, height = 1000, groundY = 955)
        // Two tanks (two different owners), parked at opposite edges of the map and never
        // moved (as in the Void growth test above) - a single-tank match would trivially end
        // after its first turn, never reaching a second round, and repositioning either tank
        // near the region across several rounds of growth risks landing it on top of the
        // other, causing an early direct hit instead of a clean shot into the region.
        val a = testTank(id = 1, ownerId = 1, x = 100f, health = 1000)
        val b = testTank(id = 2, ownerId = 2, x = 900f, health = 1000)
        // Each has its own ammo allowance for the larger-blast-radius weapon needed to
        // geometrically reach true bottom from this depth - whichever one seeds the region.
        a.currentWeapon = WeaponType.BIG_BERTHA
        b.currentWeapon = WeaponType.BIG_BERTHA
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 0f, floorType = FloorType.LAVA, rng = Random(1))

        val seeder = engine.currentTank!!
        seeder.angleDeg = 45f
        seeder.power = 30f
        engine.fire()
        runUntilNotResolving(engine) // seeds the region

        assertEquals(1, engine.lavaRegions.size)
        val region = engine.lavaRegions.single()
        assertEquals(995, region.depthCapGroundY) // seeded at groundY=955, capped 4%*1000=40 deeper
        val centerColumn = region.centerX.toInt()
        assertTrue(terrain.groundY[centerColumn] <= 995)
        val radiusAfterFirstShot = region.radius
        val centerX = region.centerX

        // Subsequent shots just need to land within the already-open region, not re-seed it -
        // fired straight at the region's own (fixed) center each time, from whichever tank is
        // up, regardless of how wide the region has grown since.
        repeat(3) { fireAtX(engine, centerX) }

        assertTrue("expected the region to have grown wider over several more rounds", engine.lavaRegions.single().radius > radiusAfterFirstShot)
        assertTrue(
            "expected the carved depth at the region's own center to never exceed its depth cap",
            terrain.groundY[centerColumn] <= 995,
        )
    }

    @Test
    fun `Lava round damage - touching deals 25 percent max health and overrides (never stacks with) the proximity burn`() {
        // groundY=985, height=1000: the depth cap (groundY + 4%*height = 1025, clamped to
        // 1000) ends up at the map's own true bottom, only 15 short of the original surface -
        // within the 2%*height=20 vertical-proximity tolerance. A shallower map (as the depth-
        // cap test above uses) would leave the region's own carved surface too far below the
        // original ground for any tank standing on undisturbed terrain to ever register as
        // vertically "nearby," even directly beside the region.
        val terrain = flatTerrain(width = 1000, height = 1000, groundY = 985)
        val t1 = testTank(id = 1, ownerId = 1, x = 500f, health = 1000)
        val t2 = testTank(id = 2, ownerId = 2, x = 100f, health = 1000)
        t1.currentWeapon = WeaponType.BIG_BERTHA
        t2.currentWeapon = WeaponType.BIG_BERTHA
        val engine = GameEngine(terrain, listOf(t1, t2), maxWindMagnitude = 0f, floorType = FloorType.LAVA, rng = Random(1))

        val firer = engine.currentTank!!
        val victim = if (firer === t1) t2 else t1
        firer.angleDeg = 45f
        firer.power = 30f
        engine.fire()
        runUntilNotResolving(engine)

        assertEquals(1, engine.lavaRegions.size)
        val region = engine.lavaRegions.single()

        // Directly over the region - "touching" it. Reassigning x alone would otherwise strand
        // the tank above the region's much deeper surface, incurring incidental fall damage on
        // the next tick that has nothing to do with lava - so y is reset to the new column's
        // surface immediately too.
        victim.x = region.centerX
        victim.y = terrain.heightAt(victim.x.toInt()).toFloat()
        fireSafeFillerShot(engine) // completes the round (the seeding shot above was turn 1 of 2)

        assertEquals(1000 - 25, victim.health)

        // Just outside the region's own radius, but still within its 5%-width/2%-height
        // proximity zone.
        victim.x = region.centerX + region.radius + 0.03f * terrain.width
        victim.y = terrain.heightAt(victim.x.toInt()).toFloat()
        // Two more filler shots: the previous filler completed round 1 (turn 2 of 2), so this
        // next one is only turn 1 of round 2 - a second filler shot is needed to complete round
        // 2 and actually trigger this round's damage pass.
        fireSafeFillerShot(engine)
        fireSafeFillerShot(engine)

        assertEquals(1000 - 25 - 10, victim.health)
    }
}
