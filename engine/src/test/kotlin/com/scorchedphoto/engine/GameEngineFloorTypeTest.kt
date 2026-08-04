package com.scorchedphoto.engine

import com.scorchedphoto.engine.ai.CpuAimCalculator
import com.scorchedphoto.engine.combat.WeaponCatalog
import com.scorchedphoto.engine.combat.WeaponType
import com.scorchedphoto.engine.physics.GRAVITY
import com.scorchedphoto.engine.physics.POWER_SCALE
import com.scorchedphoto.engine.tanks.Tank
import com.scorchedphoto.engine.tanks.testTank
import com.scorchedphoto.terrain.HeightMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
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
    fun `floor type Reflective retains 98 percent speed on each bounce off the true bottom`() {
        val terrain = flatTerrain(width = 200, height = 200, groundY = 50)
        terrain.groundY[100] = terrain.height
        val shooter = testTank(id = 1, ownerId = 1, x = 100f)
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, floorType = FloorType.REFLECTIVE, rng = Random(1))
        shooter.angleDeg = 270f // straight down - vx stays exactly 0 the whole flight, no wind
        shooter.power = 50f
        engine.fire()

        // Between any two consecutive bounces, gravity alone conserves speed on the way up and
        // back down again (no air resistance) - so the speed reaching the floor the *second*
        // time equals the speed leaving it after the *first* bounce. That makes the ratio
        // between the two bounces' own post-bounce speeds a direct, empirical measurement of
        // this floor's own velocityRetention, without needing to reconstruct the exact
        // analytical impact speed by hand.
        var ticks = 0
        var wasBounceEffectsEmpty = true
        var firstBounceSpeed: Float? = null
        var secondBounceSpeed: Float? = null
        while (ticks < 400 && secondBounceSpeed == null) {
            engine.tick(1f / 60f)
            ticks++
            val bounceEffectsEmptyNow = engine.bounceEffects.isEmpty()
            if (wasBounceEffectsEmpty && !bounceEffectsEmptyNow) {
                val speed = abs(engine.projectiles.single().vy)
                if (firstBounceSpeed == null) firstBounceSpeed = speed else secondBounceSpeed = speed
            }
            wasBounceEffectsEmpty = bounceEffectsEmptyNow
        }

        assertNotNull("expected a first bounce within $ticks ticks", firstBounceSpeed)
        assertNotNull("expected a second bounce within $ticks ticks", secondBounceSpeed)
        assertEquals(0.98f, secondBounceSpeed!! / firstBounceSpeed!!, 0.03f)
    }

    @Test
    fun `floor type Spring never launches weaker than 25 percent of the map's height, even from a very weak impact`() {
        val terrain = flatTerrain(width = 200, height = 200, groundY = 50)
        terrain.groundY[100] = terrain.height
        val shooter = testTank(id = 1, ownerId = 1, x = 100f)
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, floorType = FloorType.SPRING, rng = Random(1))
        shooter.angleDeg = 270f
        shooter.power = 1f // a very weak impact - plain 0.7x retention alone would be nearly negligible
        engine.fire()

        var ticks = 0
        while (engine.bounceEffects.isEmpty() && ticks < 200) {
            engine.tick(1f / 60f)
            ticks++
        }

        assertTrue("expected the floor to reflect the projectile", engine.bounceEffects.isNotEmpty())
        val p = engine.projectiles.single()
        assertTrue("expected an upward (negative) velocity after bouncing off the floor", p.vy < 0f)
        val expectedMinSpeed = sqrt(2f * GRAVITY * 0.25f * terrain.height)
        assertTrue(
            "expected the enforced minimum launch speed (~$expectedMinSpeed) to kick in instead of the raw " +
                "0.7x retention of this near-zero impact speed - got vy=${p.vy}",
            abs(p.vy) >= expectedMinSpeed - 1f,
        )
    }

    @Test
    fun `floor type Spring deflects the bounce sideways by up to 2 degrees instead of bouncing along the exact same vertical line forever`() {
        val terrain = flatTerrain(width = 200, height = 200, groundY = 50)
        terrain.groundY[100] = terrain.height
        val shooter = testTank(id = 1, ownerId = 1, x = 100f)
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, floorType = FloorType.SPRING, rng = Random(1))
        shooter.angleDeg = 270f // straight down - vx starts at exactly 0
        shooter.power = 50f
        engine.fire()

        var ticks = 0
        while (engine.bounceEffects.isEmpty() && ticks < 200) {
            engine.tick(1f / 60f)
            ticks++
        }

        assertTrue("expected the floor to reflect the projectile", engine.bounceEffects.isNotEmpty())
        val p = engine.projectiles.single()
        assertTrue("expected the bounce to introduce some sideways drift, not stay perfectly vertical", p.vx != 0f)
        val speed = hypot(p.vx.toDouble(), p.vy.toDouble()).toFloat()
        // A small safety margin over the exact 2-degree cap, for floating-point slack.
        val maxDeflectionVx = speed * sin(Math.toRadians(2.05)).toFloat()
        assertTrue(
            "expected the sideways drift to stay within the +-2 degree deflection cap - got vx=${p.vx}, speed=$speed",
            abs(p.vx) <= maxDeflectionVx,
        )
    }

    @Test
    fun `a projectile that would otherwise fly forever (Wrap floor) is force-detonated by the 30 second fuse`() {
        val terrain = flatTerrain(width = 200, height = 200, groundY = 50)
        terrain.groundY[100] = terrain.height
        val shooter = testTank(id = 1, ownerId = 1, x = 100f, health = 1000)
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, floorType = FloorType.WRAP, rng = Random(1))
        engine.floorWrapDepthY = 12f
        shooter.angleDeg = 270f
        shooter.power = 50f
        engine.fire()

        var ticks = 0
        while (engine.projectiles.isNotEmpty() && ticks < 3000) {
            engine.tick(1f / 60f)
            ticks++
        }

        assertTrue("expected the 30-second fuse to eventually force-detonate the projectile", engine.projectiles.isEmpty())
        assertTrue("expected a real explosion from the fuse, not a silent removal", engine.impactEffects.isNotEmpty())
        // 30s at 60 ticks/sec = 1800 ticks - loosely bounded to tolerate the shot's own brief
        // travel time before it starts wrapping at all, and any float accumulation error.
        assertTrue("expected this to take close to the full 30-second fuse, not resolve immediately via ordinary wrapping", ticks > 1500)
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

    // --- Water: drowning sequence (replaces burn/explode/ash for a fully submerged death) ----

    /** Shared shape for most drowning tests below: three tanks - two sharing an owner so
     * drowning the "victim" never ends the match on its own - on a mostly-shallow map, with a
     * *wide* dug-down region (not just the victim's own single column) near the map's true
     * bottom, and the victim parked in the middle of it. Water starts basically at zero depth
     * ([GameEngine]'s own `currentWaterLevelY` inits to `terrain.height`, so full submersion is
     * mathematically impossible before at least one round has actually lowered it) and drops by
     * exactly 2% of terrain.height per completed round, so a single completed round (980) is
     * already enough to submerge the victim's own topmost point (999 - 7 = 992). The region is
     * widened well past any ordinary [fireSafeFillerShot]'s own ~95px range specifically so that
     * if turn order ever has the victim itself fire while already resting down here, its shot
     * still lands safely within this same deep region instead of finding the sharply shallower
     * terrain immediately outside it and grounding right back on top of its own body (a real
     * self-splash-kill risk a narrower single-column well was found to actually trigger).
     */
    private fun waterDrowningTrio(): Triple<GameEngine, Tank, HeightMap> {
        val terrain = flatTerrain(width = 3000, height = 1000, groundY = 100)
        for (x in 200 until 800) terrain.groundY[x] = 999
        val victim = testTank(id = 1, ownerId = 1, x = 500f, health = 1000)
        val victimOwnerMate = testTank(id = 2, ownerId = 1, x = 1000f, health = 1000)
        val other = testTank(id = 3, ownerId = 2, x = 2500f, health = 1000)
        val engine = GameEngine(
            terrain,
            listOf(victim, victimOwnerMate, other),
            maxWindMagnitude = 0f,
            floorType = FloorType.WATER,
            rng = Random(1),
        )
        return Triple(engine, victim, terrain)
    }

    @Test
    fun `a fully submerged tank at a round boundary drowns via killByDrowning, never touching the ordinary burn path`() {
        val (engine, victim, _) = waterDrowningTrio()

        fireSafeFillerShot(engine)
        fireSafeFillerShot(engine)

        // The 3rd (round-completing) shot is where victim actually drowns - fire and tick it
        // manually instead of using fireSafeFillerShot/runUntilNotResolving, so every tick of
        // the whole animation can be inspected instead of only the settled end state.
        val firer = engine.currentTank!!
        firer.angleDeg = if (firer.x < engine.terrain.width / 2f) 135f else 45f
        firer.power = 30f
        engine.fire()

        var ticks = 0
        while (!victim.isDrowned && ticks < 20_000) {
            engine.tick(1f / 60f)
            assertFalse("expected no burn animation from drowning", victim.burning)
            assertFalse("expected no pendingBurn from drowning", victim.pendingBurn)
            assertFalse("expected no awaitingExplosion from drowning", victim.awaitingExplosion)
            assertFalse("expected no exploding from drowning", victim.exploding)
            assertFalse("expected drowning to never reach isAsh", victim.isAsh)
            ticks++
        }

        assertTrue("expected the drowning animation to actually finish", ticks < 20_000)
        assertFalse(victim.alive)
        assertTrue(victim.isDrowned)
    }

    @Test
    fun `the drowning phase sequence advances pendingDrown to drowningBubbles to drowningSpeech to rising to isDrowned, freezing y throughout`() {
        val (engine, victim, _) = waterDrowningTrio()

        fireSafeFillerShot(engine)
        fireSafeFillerShot(engine)

        val firer = engine.currentTank!!
        firer.angleDeg = if (firer.x < engine.terrain.width / 2f) 135f else 45f
        firer.power = 30f
        engine.fire()

        var sawPendingDrown = false
        var sawDrowningBubbles = false
        var sawDrowningSpeech = false
        var sawRising = false
        var frozenY: Float? = null
        var ticks = 0
        while (!victim.isDrowned && ticks < 20_000) {
            engine.tick(1f / 60f)
            if (victim.pendingDrown) sawPendingDrown = true
            if (victim.drowningBubbles || victim.drowningSpeech || victim.rising) {
                if (frozenY == null) frozenY = victim.y
                assertEquals(
                    "expected y frozen from the start of drowningBubbles through the end of rising",
                    frozenY,
                    victim.y,
                    0.001f,
                )
            }
            if (victim.drowningBubbles) sawDrowningBubbles = true
            if (victim.drowningSpeech) sawDrowningSpeech = true
            if (victim.rising) sawRising = true
            ticks++
        }

        assertTrue("expected the drowning animation to actually finish", ticks < 20_000)
        assertTrue("expected the pendingDrown phase to have occurred", sawPendingDrown)
        assertTrue("expected the drowningBubbles phase to have occurred", sawDrowningBubbles)
        assertTrue("expected the drowningSpeech phase to have occurred", sawDrowningSpeech)
        assertTrue("expected the rising phase to have occurred", sawRising)
        assertTrue(victim.isDrowned)
    }

    @Test
    fun `deathAnimationsDone gates round completion until isDrowned - the Water round-kill freeze regression`() {
        val (engine, victim, _) = waterDrowningTrio()

        fireSafeFillerShot(engine)
        fireSafeFillerShot(engine)

        val firer = engine.currentTank!!
        firer.angleDeg = if (firer.x < engine.terrain.width / 2f) 135f else 45f
        firer.power = 30f
        engine.fire()

        var sawResolvingWhileDrowning = false
        var ticks = 0
        while (!victim.isDrowned && ticks < 20_000) {
            engine.tick(1f / 60f)
            if (victim.pendingDrown || victim.drowningBubbles || victim.drowningSpeech || victim.rising) {
                assertEquals(
                    "expected the phase to stay RESOLVING while the drowning animation plays out " +
                        "- the finishResolution freeze regression",
                    MatchPhase.RESOLVING,
                    engine.phase,
                )
                sawResolvingWhileDrowning = true
            }
            ticks++
        }

        assertTrue("expected the drowning animation to actually finish", ticks < 20_000)
        assertTrue("expected to have actually observed RESOLVING mid-animation", sawResolvingWhileDrowning)
        assertTrue(victim.isDrowned)
        assertEquals(
            "expected the round to actually resolve to AIMING once the animation finished",
            MatchPhase.AIMING,
            engine.phase,
        )
    }

    @Test
    fun `once isDrowned, the tank no longer blocks further round resolution`() {
        val (engine, victim, terrain) = waterDrowningTrio()

        // Drown victim across round 1 (3 turns: 2 fillers + the round-completing 3rd) - run to
        // completion via runUntilNotResolving/fireSafeFillerShot since this test only cares
        // about the settled state, not the animation's own tick-by-tick progression.
        fireSafeFillerShot(engine)
        fireSafeFillerShot(engine)
        fireSafeFillerShot(engine)

        assertTrue("expected victim to have finished drowning by now", victim.isDrowned)
        val levelAfterRound1 = engine.waterLevelY

        // Two more full rounds, now with only the 2 remaining alive tanks (1 turn each).
        fireSafeFillerShot(engine)
        fireSafeFillerShot(engine)
        assertEquals(levelAfterRound1 - 0.02f * terrain.height, engine.waterLevelY, 0.001f)

        fireSafeFillerShot(engine)
        fireSafeFillerShot(engine)
        assertEquals(levelAfterRound1 - 2 * 0.02f * terrain.height, engine.waterLevelY, 0.001f)
    }

    @Test
    fun `both tanks drowning in the same round still declares a tie`() {
        val terrain = flatTerrain(width = 3000, height = 1000, groundY = 100)
        val a = testTank(id = 1, ownerId = 1, x = 500f, health = 1000)
        val b = testTank(id = 2, ownerId = 2, x = 2500f, health = 1000)
        // Each dug down over a *wide* region, not just its own single column - see
        // waterDrowningTrio's own doc for why a narrow well is actually unsafe here: if either
        // tank's turn has it fire while it's already resting down there, a normal
        // fireSafeFillerShot's own ~95px shot needs to land safely within this same deep region
        // instead of finding sharply shallower terrain immediately outside it and grounding
        // right back on top of its own body.
        for (x in 300 until 700) terrain.groundY[x] = 999
        for (x in 2300 until 2700) terrain.groundY[x] = 999
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 0f, floorType = FloorType.WATER, rng = Random(1))

        fireSafeFillerShot(engine) // 1 of 2 turns
        // 2 of 2 - the round completes here, both drown; this call's own runUntilNotResolving
        // loops until phase leaves FIRING/RESOLVING, i.e. until both full drowning animations
        // finish and the match is scored.
        fireSafeFillerShot(engine)

        assertEquals(MatchPhase.GAME_OVER, engine.phase)
        assertFalse(a.alive)
        assertFalse(b.alive)
        assertTrue(a.isDrowned)
        assertTrue(b.isDrowned)
        val result = engine.winResult
        assertNotNull("expected a tie result instead of a stalled match with no winner", result)
        assertEquals(setOf(1, 2), result?.winningOwnerIds?.toSet())
        assertEquals(setOf(1, 2), result?.winningTankIds?.toSet())
    }

    @Test
    fun `a dry tank under Water floor killed by a direct hit still burns, explodes, and ashes normally`() {
        val terrain = flatTerrain(width = 1000, height = 1000, groundY = 500)
        val a = testTank(id = 1, ownerId = 1, x = 300f, health = 1000)
        val b = testTank(id = 2, ownerId = 2, x = 500f, health = 1000)
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 0f, floorType = FloorType.WATER, rng = Random(1))
        // Neither tank's resting column (500) is anywhere near the map's true bottom, and
        // currentWaterLevelY starts at terrain.height (1000) with no round having completed
        // yet - both tanks are entirely dry.
        val shooter = engine.currentTank!!
        val target = if (shooter === a) b else a
        target.health = 1

        val idealPower = CpuAimCalculator.solveIdealPower(shooter, target, terrain, engine.wind)
        shooter.angleDeg = if (target.x >= shooter.x) 45f else 135f
        shooter.power = idealPower
        assertTrue(engine.fire())

        var ticks = 0
        while (target.alive && ticks < 1000) {
            engine.tick(1f / 60f)
            ticks++
        }
        assertFalse("expected the direct hit to kill the target", target.alive)

        var settleTicks = 0
        while (!target.isAsh && settleTicks < 1000) {
            engine.tick(1f / 60f)
            settleTicks++
        }

        assertTrue("expected the ordinary burn/explosion/ash sequence, not drowning", target.isAsh)
        assertFalse(target.pendingDrown)
        assertFalse(target.drowningBubbles)
        assertFalse(target.drowningSpeech)
        assertFalse(target.rising)
        assertFalse(target.isDrowned)
    }

    @Test
    fun `a tank that falls into deep water drowns instead of burning, even though a fall - not the round boundary - killed it`() {
        // A dedicated (all-dry-at-first) trio, unlike waterDrowningTrio - this test needs the
        // victim to start out shallow like everyone else, only falling into deep water partway
        // through, so it can't reuse a helper that starts the victim already resting down there.
        val terrain = flatTerrain(width = 3000, height = 1000, groundY = 100)
        val victim = testTank(id = 1, ownerId = 1, x = 500f, health = 1000)
        val victimOwnerMate = testTank(id = 2, ownerId = 1, x = 1000f, health = 1000)
        val other = testTank(id = 3, ownerId = 2, x = 2500f, health = 1000)
        val engine = GameEngine(
            terrain,
            listOf(victim, victimOwnerMate, other),
            maxWindMagnitude = 0f,
            floorType = FloorType.WATER,
            rng = Random(1),
        )

        // Round 1 completes safely first (victim's own column is still untouched, so it's dry
        // like everyone else) - full submersion is mathematically impossible before at least one
        // round has actually lowered currentWaterLevelY below the map's true bottom (see
        // waterDrowningTrio's own doc), so a plain fall can't achieve it standalone either.
        fireSafeFillerShot(engine)
        fireSafeFillerShot(engine)
        fireSafeFillerShot(engine)
        assertEquals(980f, engine.waterLevelY, 0.001f)

        // *Now* deepen victim's own column into a genuine gap gravity hasn't discovered yet
        // (round 1's own boundary check has already passed, so it never saw this), and lower
        // its health so the resulting fall damage - not a direct hit, and not this round's own
        // boundary check - is what actually kills it.
        terrain.groundY[victim.x.toInt()] = 995
        victim.health = 1

        // Turn 1 of round 2 - just enough ticks for victim's own fall (and, per the
        // finishResolution fix, its full drowning animation) to play out, however whose turn
        // this actually is.
        fireSafeFillerShot(engine)

        assertFalse("expected the fall into deep water to kill the tank", victim.alive)
        assertFalse("expected no burn animation from a fall into deep water", victim.burning)
        assertFalse("expected no pendingBurn either", victim.pendingBurn)
        assertFalse("expected it to never reach isAsh", victim.isAsh)
        assertTrue("expected the drowning chain to have finished instead", victim.isDrowned)
    }

    @Test
    fun `a projectile landing in open water with no tank there fizzles silently`() {
        // Unlike the Hole/Void fizzle tests above, this can't reuse "groundY set to the map's
        // own true bottom" - floorMaxGroundY() never relaxes Water's ordinary 95%-depth clamp,
        // so handleFloorEdge's own WATER branch (reached only once terrain is carved *all* the
        // way to terrain.height) is a documented no-op/unreachable case for it, and a projectile
        // that ends up there would just linger forever instead of fizzling. WATER's fizzle logic
        // instead lives in tickProjectiles' ordinary groundedThisTick branch - reachable for any
        // depth below the *current* water line that still leaves a sliver of real floor (< the
        // map's true bottom), which also means fizzling needs at least one round to have already
        // lowered the water below the map's own maximum carvable depth (see waterDrowningTrio's
        // own doc on why full submersion needs at least one round too).
        val terrain = flatTerrain(width = 1000, height = 1000, groundY = 100)
        val a = testTank(id = 1, ownerId = 1, x = 100f, health = 1000)
        val b = testTank(id = 2, ownerId = 2, x = 900f, health = 1000)
        val engine = GameEngine(terrain, listOf(a, b), maxWindMagnitude = 0f, floorType = FloorType.WATER, rng = Random(1))

        fireSafeFillerShot(engine)
        fireSafeFillerShot(engine)
        assertEquals(980f, engine.waterLevelY, 0.001f)
        // Drains the two filler shots' own (real, ordinary) impact events so the final
        // drainEvents() assertion below only reflects this test's own fizzle shot.
        engine.drainEvents()

        // Moves the current tank onto a dedicated "nobody else is here" column, well clear of
        // the other tank, and deepens *only* that column below the new water line - a straight-
        // down shot (zero horizontal velocity, no wind) lands exactly there with no horizontal-
        // drift uncertainty, mirroring the existing Hole/Void fizzle tests' own "fire straight
        // down from directly above the target column" approach. A single deepened column is
        // safe to fire from here (unlike waterDrowningTrio's wide wells) precisely because this
        // shot can never detonate at all - there's nothing for it to splash back onto itself.
        val shooter = engine.currentTank!!
        val targetColumn = 500
        shooter.x = targetColumn.toFloat()
        shooter.y = terrain.heightAt(targetColumn).toFloat()
        terrain.groundY[targetColumn] = 990
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
        assertEquals("expected no crater carved at the fizzle column", 990, terrain.groundY[targetColumn])
    }

    @Test
    fun `a projectile landing on dry terrain above the water line under Water floor still detonates normally`() {
        val terrain = flatTerrain(width = 200, height = 200, groundY = 50)
        val shooter = testTank(id = 1, ownerId = 1, x = 100f, health = 1000)
        val engine = GameEngine(terrain, listOf(shooter), maxWindMagnitude = 0f, floorType = FloorType.WATER, rng = Random(1))
        shooter.angleDeg = 270f
        shooter.power = 50f
        engine.fire()

        var ticks = 0
        while (engine.projectiles.isNotEmpty() && ticks < 200) {
            engine.tick(1f / 60f)
            ticks++
        }

        assertTrue("expected the projectile to detonate rather than fizzle", engine.projectiles.isEmpty())
        assertTrue("expected a real explosion effect", engine.impactEffects.isNotEmpty())
        assertTrue("expected the detonation to carve into terrain", terrain.groundY[100] > 50)
    }

    @Test
    fun `splash damage from a dry-ground explosion still reaches a fully submerged tank nearby`() {
        val terrain = flatTerrain(width = 1000, height = 1000, groundY = 970)
        val t1 = testTank(id = 1, ownerId = 1, x = 100f, health = 1000)
        val t2 = testTank(id = 2, ownerId = 2, x = 500f, health = 1000)
        val engine = GameEngine(terrain, listOf(t1, t2), maxWindMagnitude = 0f, floorType = FloorType.WATER, rng = Random(1))

        // Round 1 completes uneventfully - groundY=970 stays well above round 1's own new
        // water level (1000 - 2%*1000 = 980), so nobody is anywhere near submerged yet.
        fireSafeFillerShot(engine)
        fireSafeFillerShot(engine)
        val waterLevelAfterRound1 = engine.waterLevelY
        assertEquals(980f, waterLevelAfterRound1, 0.001f)

        val shooter = engine.currentTank!!
        val victim = if (shooter === t1) t2 else t1
        shooter.currentWeapon = WeaponType.BIG_BERTHA

        // Manually deepen just the victim's own column into a "hole" that's now underwater -
        // fully submerged (its topmost point 5 units below the surface) - and keep terrain
        // consistent so the next gravity tick doesn't yank it back up or apply incidental fall
        // damage that would confound this test's own damage assertion. Mirrors the existing
        // Lava round-damage test's own direct victim.x/y reassignment.
        val submergedY = waterLevelAfterRound1 + Tank.RADIUS + 5f
        terrain.groundY[victim.x.toInt()] = submergedY.toInt()
        victim.y = submergedY
        assertTrue("expected victim to actually be fully submerged now", victim.y - Tank.RADIUS >= engine.waterLevelY)

        // Fires at a dry column right beside the victim (still 970, well above the water line)
        // - close enough that Big Bertha's blast radius reaches the submerged victim, but not a
        // direct hit on it.
        fireAtX(engine, victim.x - 25f)

        assertTrue("expected the splash to have actually damaged the submerged victim", victim.health < 1000)
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
        val engine = GameEngine(terrain, listOf(t1, t2), maxWindMagnitude = 0f, floorType = FloorType.LAVA, rng = Random(1))

        val firer = engine.currentTank!!
        val victim = if (firer === t1) t2 else t1
        // Big Bertha only for the seed shot, matching this test's originally-tuned carve
        // geometry - then back to the default (Standard Shell) for every later filler shot.
        // Those later shots are pure "advance the round" fillers whose only job is to complete
        // rounds (the victim's damage comes from being manually positioned on/near the region,
        // not from the shots themselves), and Big Bertha's now-limited ammo (see WeaponCatalog)
        // would otherwise run out and silently no-op a filler shot before all rounds needed
        // here complete.
        firer.currentWeapon = WeaponType.BIG_BERTHA
        firer.angleDeg = 45f
        firer.power = 30f
        engine.fire()
        runUntilNotResolving(engine)
        firer.currentWeapon = WeaponType.STANDARD_SHELL

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

    @Test
    fun `a Lava round-kill also drives its death animation to completion instead of freezing`() {
        // Same setup as the Lava round-damage test above (groundY=985 puts the region's own
        // depth cap within vertical-proximity range of undisturbed terrain).
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
        victim.x = region.centerX
        victim.y = terrain.heightAt(victim.x.toInt()).toFloat()
        // Lowered to exactly the touch-damage amount (LAVA_TOUCH_DAMAGE_FRACTION * MAX_HEALTH)
        // right before the round-completing shot, so this round's own touch damage actually
        // zeroes it out and triggers a real kill, not just more survivable proximity damage.
        victim.health = 25

        // Completes the round (the seeding shot above was turn 1 of 2) - this call's own
        // runUntilNotResolving must wait out the full death animation (the finishResolution fix
        // under test) rather than returning with the tank frozen mid-animation, which would
        // also make it hang against runUntilNotResolving's own maxTicks guard.
        fireSafeFillerShot(engine)

        assertFalse("expected the round's touch damage to actually kill the victim", victim.alive)
        assertTrue("expected the death animation to have fully completed, not frozen mid-way", victim.isAsh)
    }
}
