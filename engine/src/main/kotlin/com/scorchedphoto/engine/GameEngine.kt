package com.scorchedphoto.engine

import com.scorchedphoto.engine.combat.DamageCalculator
import com.scorchedphoto.engine.combat.SplitPattern
import com.scorchedphoto.engine.combat.TerrainEffect
import com.scorchedphoto.engine.combat.Weapon
import com.scorchedphoto.engine.combat.WeaponCatalog
import com.scorchedphoto.engine.combat.WeaponType
import com.scorchedphoto.engine.physics.GRAVITY
import com.scorchedphoto.engine.physics.Projectile
import com.scorchedphoto.engine.physics.Wind
import com.scorchedphoto.engine.physics.launchVelocity
import com.scorchedphoto.engine.physics.maxPowerForHealth
import com.scorchedphoto.engine.physics.stepProjectile
import com.scorchedphoto.engine.tanks.Tank
import com.scorchedphoto.engine.terrain.CraterCarver
import com.scorchedphoto.engine.terrain.FloorRegion
import com.scorchedphoto.engine.turns.TurnManager
import com.scorchedphoto.engine.turns.WinResult
import com.scorchedphoto.terrain.HeightMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

enum class MatchPhase { AIMING, FIRING, RESOLVING, GAME_OVER }

/** A terrain collision point found by [GameEngine.findTerrainCrossing] - [x] is the swept
 * column (as a Float, matching [GameEngine.resolveImpact]'s signature), [y] the flight
 * path's interpolated height at that column, not the column's own raw terrain height. */
private data class TerrainHit(val x: Float, val y: Float)

/**
 * One tank's death, appended to [GameEngine.deathLog] the moment it happens - the full history
 * multi-game tournament scoring (see the :app `tournament` package) is built from.
 * [turnNumber] is the turn during which the tank died (see [GameEngine]'s own `turnNumber`
 * field) - two records sharing a [turnNumber] died simultaneously, the tie-break signal
 * Knockout/Survivor/Standing scoring all need. [impactId] identifies which single blast (a
 * live shot's direct/splash damage, or a DoT tick sourced from one - see
 * [Tank.dotSourceImpactId]) this death came from; `null` for a death with no single
 * attributable blast (fall damage, lava, drowning, falling through an open floor - though
 * [killedByOwnerId] can still be non-null for these, see rule 4 in [GameEngine.resolveImpact]'s
 * own doc). [killedByOwnerId] is the owner credited for this death, or `null` if unattributed.
 */
data class DeathRecord(val ownerId: Int, val tankId: Int, val turnNumber: Int, val impactId: Int?, val killedByOwnerId: Int?)

/**
 * Orchestrates a full match: turn order, firing, projectile physics, terrain/tank
 * collision, crater carving, damage, tank gravity, and win detection. This is the single
 * source of truth the real-time game loop (:app) drives via [tick]; UI layers observe its
 * public state and never mutate [terrain]/[tanks] directly.
 */
class GameEngine(
    val terrain: HeightMap,
    val tanks: List<Tank>,
    val wind: Wind = Wind(),
    val maxWindMagnitude: Float = 15f,
    val wallType: EdgeType = EdgeType.NONE,
    val ceilingType: EdgeType = EdgeType.NONE,
    val floorType: FloorType = FloorType.GROUND,
    private val rng: Random = Random.Default,
) {
    // Shuffled once per match so turn order isn't always the order tanks were configured in -
    // tanks itself (used everywhere else: rendering, collision, gravity, etc.) stays in its
    // original, caller-supplied order; only the turn sequence is randomized.
    private val turnManager = TurnManager(tanks.shuffled(rng))

    private val ammoRemaining: MutableMap<Int, MutableMap<WeaponType, Int>> =
        tanks.associateTo(mutableMapOf()) { tank ->
            tank.id to WeaponCatalog.all.filter { it.ammoLimit != null }
                .associateTo(mutableMapOf()) { it.type to it.ammoLimit!! }
        }

    private val activeProjectiles = mutableListOf<Projectile>()
    private val activeImpactEffects = mutableListOf<ImpactEffect>()
    private val activeBounceEffects = mutableListOf<BounceEffect>()
    private val pendingEvents = mutableListOf<GameEvent>()

    // Ids of tanks killed since the current shot was fired - reset at the start of each
    // fire(). Used only for the mutual-elimination tie case in finishResolution(), where
    // TurnManager.checkWinCondition() has no alive tank left to identify a winner from.
    private val killedThisResolution = mutableSetOf<Int>()

    // The turn currently in progress - incremented in finishResolution() right before turn
    // control actually passes to the next tank, so every DeathRecord appended between one
    // increment and the next died "on the same turn" (see DeathRecord's own doc). Starts at 1,
    // the match's first turn.
    private var turnNumber = 1

    // Identifies a single resolveImpact() call (one blast) for DeathRecord.impactId - see its
    // own doc. Incremented at the top of every resolveImpact() call, live shot or chain
    // explosion alike.
    private var impactSequence = 0

    private val recordedDeaths = mutableListOf<DeathRecord>()

    /** Every tank death so far this match, in the order they happened - see [DeathRecord]'s own
     * doc. Read by :app's tournament scoring once a match ends; otherwise unused by the engine
     * itself beyond producing it. */
    val deathLog: List<DeathRecord> get() = recordedDeaths

    var phase: MatchPhase = MatchPhase.AIMING
        private set

    var winResult: WinResult? = null
        private set

    /**
     * World-space Y a ceiling-WRAP'd projectile reappears at (see [handleEdgeBounce]'s WRAP
     * branch) - defaults to [terrain]'s own height, matching the original hardcoded behavior.
     * A caller with access to the real rendered canvas size can override this to the world-Y
     * that maps to the canvas's actual visible bottom edge instead, so a wrap reliably
     * reappears at the bottom of what the player can actually see rather than wherever
     * terrain.height happens to fall (which is not guaranteed to be on-screen, or visually
     * distinct from ground). Mutable rather than a constructor param since the real canvas
     * size isn't known until a surface actually exists.
     */
    var ceilingWrapDepthY: Float = terrain.height.toFloat()

    /** World-space Y a floor-WRAP'd projectile reappears at (see [handleFloorEdge]'s WRAP
     * branch) - "just below the top border, not colliding with it," mirroring
     * [ceilingWrapDepthY] but for the opposite edge. Defaults to `0f` (the world's own top)
     * until a caller with the real rendered canvas geometry overrides it. */
    var floorWrapDepthY: Float = 0f

    private val activeVoidRegions = mutableListOf<FloorRegion>()
    private val activeLavaRegions = mutableListOf<FloorRegion>()

    /** [FloorType.VOID]'s independently-growing open-floor regions - see [growRegion]/
     * [processFloorRound]. Empty unless [floorType] is [FloorType.VOID]. */
    val voidRegions: List<FloorRegion> get() = activeVoidRegions

    /** [FloorType.LAVA]'s independently-growing, depth-capped regions - see [growRegion]/
     * [applyLavaRoundDamage]. Empty unless [floorType] is [FloorType.LAVA]. */
    val lavaRegions: List<FloorRegion> get() = activeLavaRegions

    private var currentWaterLevelY: Float = terrain.height.toFloat()

    /** [FloorType.WATER]'s current rising surface - starts at [HeightMap.height] (invisible,
     * right at the bottom edge) and decreases by [WATER_RISE_FRACTION] of it every round (see
     * [raiseWaterLevel]). Only meaningful when [floorType] is [FloorType.WATER]. */
    val waterLevelY: Float get() = currentWaterLevelY

    // "A round" (see processFloorRound) has no prior concept anywhere in this engine - only
    // individual turns did. Counting completed turns against however many tanks were alive when
    // the round started (rather than tracking a specific "round-starting tank" identity) stays
    // correct even if a tank dies mid-round. See finishResolution.
    private var turnsCompletedThisRound = 0
    private var tanksAliveAtRoundStart = tanks.count { it.alive }

    // Set when processFloorRound() (see finishResolution) has just started a brand-new death
    // animation (a round-boundary Water/Lava kill) that tick()'s own pre-call gate had no way to
    // know about yet - true for however many finishResolution() re-entries it takes for that
    // animation to actually finish, guarding against re-running processFloorRound() a second time
    // for the same round boundary. See finishResolution's own doc.
    private var awaitingRoundAnimations: Boolean = false

    val currentTank: Tank? get() = turnManager.currentTank
    val projectiles: List<Projectile> get() = activeProjectiles

    /** Short-lived flashes at recent impact points, for the renderer to fade out. */
    val impactEffects: List<ImpactEffect> get() = activeImpactEffects

    /** Short-lived flashes at recent wall/ceiling bounce points, for the renderer to fade out. */
    val bounceEffects: List<BounceEffect> get() = activeBounceEffects

    init {
        for (tank in tanks) {
            tank.y = terrain.heightAt(tank.x.toInt()).toFloat()
        }
        wind.reroll(maxWindMagnitude, rng)
    }

    fun ammoFor(tankId: Int, type: WeaponType): Int? = ammoRemaining[tankId]?.get(type)

    /** Sound-relevant events produced since the last call - drained once per frame by
     * [com.scorchedphoto.app.game.GameLoopThread] and forwarded to the sound controller,
     * so audio triggers stay decoupled from wherever inside [tick] each change happens. */
    fun drainEvents(): List<GameEvent> {
        if (pendingEvents.isEmpty()) return emptyList()
        val drained = pendingEvents.toList()
        pendingEvents.clear()
        return drained
    }

    fun fire(): Boolean {
        val shooter = currentTank ?: return false
        if (phase != MatchPhase.AIMING) return false
        killedThisResolution.clear()
        val weapon = WeaponCatalog.byType(shooter.currentWeapon)
        if (weapon.ammoLimit != null) {
            val remaining = ammoRemaining[shooter.id]?.get(weapon.type) ?: 0
            if (remaining <= 0) return false
            ammoRemaining[shooter.id]?.set(weapon.type, remaining - 1)
        }
        // Capped at the source rather than scaled afterward: an injured shooter's power
        // meter already stops rising at this same ceiling (see maxPowerForHealth), so this
        // clamp is a defensive backstop (e.g. against a CPU aim solve or a caller that
        // ignores the meter) rather than the primary way the cap gets enforced.
        val cappedPower = shooter.power.coerceAtMost(maxPowerForHealth(shooter.health, Tank.MAX_HEALTH))
        val (vx, vy) = launchVelocity(shooter.angleDeg, cappedPower)
        activeProjectiles += Projectile(shooter.x, shooter.y, vx, vy, weapon, shooter.id)
        phase = MatchPhase.FIRING
        pendingEvents += GameEvent.ShotFired
        return true
    }

    fun tick(dt: Float) {
        if (phase != MatchPhase.FIRING && phase != MatchPhase.RESOLVING) return

        tickProjectiles(dt)
        applyTankGravity(dt)
        ageImpactEffects(dt)
        ageBounceEffects(dt)
        updateBurningTanks(dt)
        updateAwaitingExplosion(dt)
        updateExploding(dt)
        updateFallingThroughFloor(dt)
        updateDrowningBubbles(dt)
        updateDrowningSpeech(dt)
        updateRisingTanks(dt)
        startPendingBurns()
        startPendingDrowns()

        // Explosions (from projectiles still flying or still-animating impact flashes)
        // must fully finish before any death animation begins - see startPendingBurns -
        // and death animations (burning, its pause, then its own closing explosion) must
        // fully finish before the turn can advance or a win can be declared.
        val explosionsDone = activeProjectiles.isEmpty() && activeImpactEffects.isEmpty()
        if (explosionsDone && tanks.none { it.falling } && deathAnimationsDone()) {
            finishResolution()
        } else {
            phase = MatchPhase.RESOLVING
        }
    }

    /** True once every tank's own death animation (burn/explosion/ash, fall-through, or drown)
     * has fully finished - see [tick]/[finishResolution], both of which must wait for this
     * before letting a round/turn actually be considered complete. `it.exploding` is included
     * for clarity/defense-in-depth even though it's currently redundant against `tick()`'s own
     * `explosionsDone` gate: [DEATH_EXPLOSION_GROWTH_SECONDS] is strictly less than the impact
     * effect's own [IMPACT_EFFECT_LIFETIME_SECONDS], so `activeImpactEffects` is guaranteed
     * still non-empty for as long as any `tank.exploding` is true. `fallingThroughFloor`/the
     * drowning-chain flags have no such effect-based backstop (neither death plays a real
     * explosion) so they're checked directly, for the same reason: don't let the round resolve
     * out from under a still-playing death-taunt speech bubble or flip/float animation.
     * `isAsh`/`isDrowned` are deliberately excluded - both are permanent terminal states that
     * must never keep gating future rounds. */
    private fun deathAnimationsDone(): Boolean = tanks.none {
        it.burning || it.pendingBurn || it.awaitingExplosion || it.exploding || it.fallingThroughFloor ||
            it.pendingDrown || it.drowningBubbles || it.drowningSpeech || it.rising
    }

    // Filters activeProjectiles in place via its own iterator instead of rebuilding fresh
    // "still flying"/"to split" lists every call - this runs up to 60x/sec on the app's
    // render thread for the entire duration of every shot's flight, so the common case (one
    // projectile, still flying, nothing to remove or split) allocates nothing at all instead
    // of two fresh lists per tick.
    private fun tickProjectiles(dt: Float) {
        if (activeProjectiles.isEmpty()) return

        var spawned: MutableList<Projectile>? = null
        val iterator = activeProjectiles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            val wasPastApex = p.hasPassedApex
            val prevX = p.x
            val prevY = p.y
            stepProjectile(p, wind, dt)

            if (p.weapon.childCount > 1 && !wasPastApex && p.hasPassedApex) {
                val children = when (p.weapon.splitPattern) {
                    SplitPattern.RADIAL_FAN -> splitMirv(p)
                    SplitPattern.HORIZONTAL_LINE -> splitHorizontalLine(p)
                    SplitPattern.NONE -> listOf(p) // unreachable - childCount > 1 always sets a real pattern
                }
                (spawned ?: mutableListOf<Projectile>().also { spawned = it }) += children
                iterator.remove()
                continue
            }

            val column = p.x.toInt().coerceIn(0, terrain.width - 1)
            val terrainY = terrain.heightAt(column)
            val touchedTank = tanks.any { tank ->
                tank.alive && tank.id != p.ownerTankId &&
                    hypot((tank.x - p.x).toDouble(), (tank.y - p.y).toDouble()) < Tank.RADIUS
            }
            // Whether a collision happens this tick is decided exactly as before (this tick's
            // final position vs. its own column's height) - only *where* it's resolved changes,
            // via findTerrainCrossing below - so this can never shift a collision into or out of
            // a tick that would otherwise have gone to the wall/ceiling/fizzle branches (e.g. a
            // shot crossing x<=0 close to the ground, meant to bounce/detonate off the wall
            // rather than hit "terrain" at the clamped column 0). `terrainY < terrain.height`
            // additionally excludes a column floorType has fully hollowed out (a phantom
            // "surface" right at the map's true bottom) - a projectile reaching that isn't
            // grounded on real terrain, it's reached the floor-edge branch below instead.
            val groundedThisTick = !touchedTank && p.y >= terrainY && terrainY < terrain.height

            when {
                // Impact point is always anchored to the ground surface, never floating in
                // mid-air - whether the projectile stopped by touching a tank's body (which
                // can happen while still somewhat elevated - tanks are sizable now; kept
                // anchored to *this* column's own surface height, not the tank's y, same as
                // before) or by reaching the ground directly (see findTerrainCrossing -
                // resolved at the actual interpolated column/height where the flight path
                // first went solid this tick, not wherever the tick's Euler step happened to
                // land). Each affected tank's own distance to this point (not snapped to any
                // specific tank's center) drives its damage via DamageCalculator.
                touchedTank -> {
                    resolveImpact(p.weapon, p.x, terrainY.toFloat(), shooterOwnerId(p))
                    pendingEvents += GameEvent.Impact
                    iterator.remove()
                }
                groundedThisTick -> {
                    val hit = findTerrainCrossing(prevX, prevY, p.x, p.y) ?: TerrainHit(p.x, terrainY.toFloat())
                    if (floorType == FloorType.WATER && hit.y >= currentWaterLevelY) {
                        // A shell landing directly in open water with no tank there is
                        // absorbed silently - no explosion, no crater, no damage - mirroring
                        // the Hole/Void fizzle in handleFloorEdge, but triggered by submersion
                        // rather than reaching the map's true bottom. A direct hit on a tank
                        // (touchedTank, above) always still detonates regardless of water.
                        iterator.remove()
                    } else {
                        resolveImpact(p.weapon, hit.x, hit.y, shooterOwnerId(p))
                        pendingEvents += GameEvent.Impact
                        iterator.remove()
                    }
                }
                wallType != EdgeType.NONE && (p.x <= 0f || p.x >= terrain.width) ->
                    handleEdgeBounce(iterator, p, wallType, isWall = true)
                ceilingType != EdgeType.NONE && p.y <= 0f ->
                    handleEdgeBounce(iterator, p, ceilingType, isWall = false)
                p.y >= terrain.height -> handleFloorEdge(iterator, p)
                p.x < -terrain.width || p.x > 2 * terrain.width -> iterator.remove() // fizzle, flew off into the void
            }
        }

        spawned?.let { activeProjectiles += it }
    }

    /** The owner id to credit for a still-flying [p]'s eventual impact - the tank that fired it,
     * or `null` if that tank is no longer in [tanks] (shouldn't happen in practice, but a
     * projectile outliving its shooter isn't otherwise impossible to represent). */
    private fun shooterOwnerId(p: Projectile): Int? = tanks.find { it.id == p.ownerTankId }?.ownerId

    /**
     * Once a tick's own final position has already been determined to be grounded (see
     * [tickProjectiles]'s `groundedThisTick`), walks every terrain column the projectile
     * actually crossed to get there - from [prevX] to ([newX], [newY]), inclusive, in
     * direction of travel - to find the *first* one whose straight-line-interpolated flight
     * height already sits at or below (i.e. `>=`, since larger y is lower/more solid) the
     * terrain's own surface there, rather than blindly resolving at the final column.
     * [stepProjectile] is plain per-tick Euler integration with no substeps, so a fast,
     * shallow shot can cross several columns in a single ~1/60s tick; a genuinely
     * steep/near-vertical terrain step can then get skipped clean over, with the final
     * column's own height - effectively "the top of the wall" - used instead of the height at
     * which the flight path actually first crossed into solid ground, which can sit far lower
     * down the wall's face. Each column is sampled at its own *exit* edge (the last,
     * highest-y point the path actually occupies while "in" that column, given y moves
     * monotonically within one linear Euler step) rather than its entry edge, so a tick that
     * never crosses any column boundary reduces to exactly the final column's own check.
     * Gravity's curvature within a single tick is small enough that this straight-line
     * interpolation between the tick's start/end position is a reasonable approximation of the
     * true (parabolic) sub-tick path. Returns null only if no swept column actually satisfies
     * the condition (shouldn't happen given the caller's own precondition, but callers fall
     * back to the final column's own height defensively rather than crash).
     */
    private fun findTerrainCrossing(prevX: Float, prevY: Float, newX: Float, newY: Float): TerrainHit? {
        if (newX == prevX) return TerrainHit(newX, newY)
        val maxColumn = terrain.width - 1
        val startColumn = prevX.toInt().coerceIn(0, maxColumn)
        val endColumn = newX.toInt().coerceIn(0, maxColumn)
        val rightward = endColumn >= startColumn
        var column = startColumn
        while (true) {
            val edgeX = when {
                column == endColumn -> newX
                rightward -> (column + 1).toFloat()
                else -> column.toFloat()
            }
            val t = ((edgeX - prevX) / (newX - prevX)).coerceIn(0f, 1f)
            val interpolatedY = prevY + t * (newY - prevY)
            if (interpolatedY >= terrain.heightAt(column)) {
                return TerrainHit(column.toFloat(), interpolatedY)
            }
            if (column == endColumn) return null
            column += if (rightward) 1 else -1
        }
    }

    /**
     * Applied when a still-flying projectile reaches the wall (x<=0 or x>=terrain.width,
     * [isWall]=true) or ceiling (y<=0, [isWall]=false) boundary and [edgeType] configures
     * something other than NONE for that edge. BLAST_STEEL detonates it in place, reusing the
     * existing impact pipeline wholesale; WRAP teleports it to the opposite edge with velocity
     * untouched for a wall, but detonates immediately at [ceilingWrapDepthY] for a ceiling
     * (see the branch below for why); the remaining four types clamp the projectile exactly
     * onto the boundary and reflect the relevant axis's velocity, scaled by a per-type "energy
     * retention" multiplier.
     */
    private fun handleEdgeBounce(iterator: MutableIterator<Projectile>, p: Projectile, edgeType: EdgeType, isWall: Boolean) {
        when (edgeType) {
            EdgeType.NONE -> Unit // unreachable - callers only invoke this when edgeType != NONE
            EdgeType.BLAST_STEEL -> {
                resolveImpact(p.weapon, p.x, p.y, shooterOwnerId(p))
                pendingEvents += GameEvent.Impact
                iterator.remove()
            }
            EdgeType.WRAP -> {
                if (isWall) {
                    activeBounceEffects += BounceEffect(p.x, p.y, edgeType) // exit point
                    p.x = if (p.x <= 0f) terrain.width.toFloat() else 0f
                    activeBounceEffects += BounceEffect(p.x, p.y, edgeType) // entry point
                } else {
                    // A ceiling wrap always lands inside solid ground (ceilingWrapDepthY sits
                    // near the map's bottom, below virtually any column's surface) - unlike
                    // the wall case, there's no "keep flying" that makes sense here, since the
                    // very next tick's ordinary ground-collision check would just resolve at
                    // the surface height instead (discarding where it actually teleported to).
                    // Detonate here directly, the same way BLAST_STEEL does, so the blast
                    // actually carves from the map's bottom - CraterCarver's existing
                    // max()-clamped carving naturally turns that into the intended "collapse
                    // the mountain down to fill the hole" effect (see its own doc).
                    activeBounceEffects += BounceEffect(p.x, p.y, edgeType) // exit point, at the ceiling
                    resolveImpact(p.weapon, p.x, ceilingWrapDepthY, shooterOwnerId(p))
                    pendingEvents += GameEvent.Impact
                    iterator.remove()
                }
            }
            else -> { // PADDED, RUBBER, SPRING, REFLECTIVE
                val retention = velocityRetention(edgeType)
                if (isWall) {
                    p.x = p.x.coerceIn(0f, terrain.width.toFloat())
                    p.vx = -p.vx * retention
                } else {
                    p.y = 0f
                    p.vy = -p.vy * retention
                }
                activeBounceEffects += BounceEffect(p.x, p.y, edgeType)
            }
        }
    }

    private fun velocityRetention(edgeType: EdgeType): Float = when (edgeType) {
        EdgeType.PADDED -> 0.1f
        EdgeType.RUBBER -> 0.45f
        EdgeType.SPRING -> 1.2f
        EdgeType.REFLECTIVE -> 1.0f
        else -> error("velocityRetention called for non-bounce EdgeType $edgeType")
    }

    /**
     * Applied when a still-flying projectile reaches the true bottom of the map (`p.y >=
     * terrain.height`) - only reachable at all once [floorType] has let a column be fully
     * hollowed out (see [floorMaxGroundY]; GROUND/WATER/LAVA never relax the ordinary 95%-depth
     * clamp, so this branch is unreachable for them - included below only for [when]'s
     * exhaustiveness). BLAST_STEEL detonates in place, reusing the existing impact pipeline
     * wholesale; WRAP teleports it to [floorWrapDepthY] with velocity untouched and keeps it
     * flying - the same teleport-and-continue pattern wall-WRAP uses, deliberately *not*
     * ceiling-WRAP's detonate-immediately (a floor wrap's reappearance point, just under the top
     * border, is never itself embedded in solid ground the way ceiling-WRAP's is, so there's no
     * reason to cut its flight short); HOLE/VOID fizzle silently - there's genuinely no terrain
     * there to explode into, and letting a projectile fly forever over open floor would
     * otherwise stall [tick]'s resolution-completion check; the remaining four types bounce off
     * the phantom floor exactly like they'd bounce off a wall, reusing the same
     * [velocityRetention] values and [BounceEffect] visuals (via [EdgeType], used here purely as
     * a shared rendering vocabulary between wall/ceiling/floor bounces, not because a floor
     * bounce is "a wall").
     */
    private fun handleFloorEdge(iterator: MutableIterator<Projectile>, p: Projectile) {
        when (floorType) {
            FloorType.BLAST_STEEL -> {
                resolveImpact(p.weapon, p.x, p.y, shooterOwnerId(p))
                pendingEvents += GameEvent.Impact
                iterator.remove()
            }
            FloorType.WRAP -> {
                activeBounceEffects += BounceEffect(p.x, p.y, EdgeType.WRAP) // exit, at the floor
                p.y = floorWrapDepthY
                activeBounceEffects += BounceEffect(p.x, p.y, EdgeType.WRAP) // entry, just below the top border
            }
            FloorType.HOLE, FloorType.VOID -> iterator.remove()
            FloorType.PADDED, FloorType.RUBBER, FloorType.SPRING, FloorType.REFLECTIVE -> {
                val edgeType = when (floorType) {
                    FloorType.PADDED -> EdgeType.PADDED
                    FloorType.RUBBER -> EdgeType.RUBBER
                    FloorType.SPRING -> EdgeType.SPRING
                    else -> EdgeType.REFLECTIVE
                }
                p.y = terrain.height.toFloat()
                p.vy = -p.vy * velocityRetention(edgeType)
                activeBounceEffects += BounceEffect(p.x, p.y, edgeType)
            }
            FloorType.GROUND, FloorType.WATER, FloorType.LAVA -> Unit // unreachable, see doc above
        }
    }

    /**
     * Carves the terrain and damages every alive tank within reach exactly like a real
     * weapon impact - shared by actual projectile impacts and a dying tank's own death
     * blast (see [updateAwaitingExplosion]), so the two affect the ground and nearby tanks
     * the same way. Damage is keyed purely on each tank's own distance from
     * ([impactX], [impactY]) via [DamageCalculator] - a "bullseye" (the tank's central 20%)
     * returns `null` and destroys that tank outright regardless of current health;
     * everything else is an ordinary amount that only kills if it actually brings health
     * to 0. Callers are responsible for pushing whichever [GameEvent] matches their own
     * source, since a shell landing and a tank's own death blast sound different.
     *
     * [shooterOwnerId] is whoever's credited for this blast - the tank that fired the shot for
     * an ordinary impact, or (per [updateAwaitingExplosion]) whoever was credited for killing
     * the tank whose own death explosion this is, so a chain of explosions keeps crediting
     * back to whoever started it. `null` for an unattributed blast (a chain explosion whose own
     * predecessor death was itself unattributed). Every alive tank within the blast's actual
     * reach (mirroring [DamageCalculator.computeDamage]'s own distance formula, not just
     * [Weapon.blastRadius] in isolation) has [Tank.creditOwnerId] stamped with this value
     * *before* damage is computed, regardless of whether it ends up taking any - this is the
     * single mechanism every other environmental/fall/hazard attribution rule in this file
     * builds on (see each of those call sites' own use of `tank.creditOwnerId`).
     */
    private fun resolveImpact(weapon: Weapon, impactX: Float, impactY: Float, shooterOwnerId: Int?) {
        val impactId = impactSequence++
        when (weapon.terrainEffect) {
            TerrainEffect.CARVE -> {
                CraterCarver.carve(terrain, impactX.toInt(), impactY.toInt(), weapon.blastRadius.toInt(), floorMaxGroundY())
                maybeSeedFloorRegion(impactX, impactY, weapon.blastRadius)
            }
            // A mound can never reach terrain.height, so it never seeds a Void/Lava region -
            // and it skips raising terrain at all if it would land inside an already-open one,
            // since nothing else in the engine ever un-collapses a tracked FloorRegion.
            TerrainEffect.FILL -> {
                if (!withinActiveFloorRegion(impactX, weapon.blastRadius)) {
                    CraterCarver.fill(terrain, impactX.toInt(), impactY.toInt(), weapon.blastRadius.toInt())
                }
            }
        }
        activeImpactEffects += ImpactEffect(impactX, impactY, weapon.blastRadius)
        for (tank in tanks) {
            if (!tank.alive) continue
            val distance = hypot((tank.x - impactX).toDouble(), (tank.y - impactY).toDouble()).toFloat()
            if (distance <= Tank.RADIUS + weapon.blastRadius) {
                tank.creditOwnerId = shooterOwnerId
            }
            val damage = DamageCalculator.computeDamage(weapon, impactX, impactY, tank)
            when {
                damage == null -> {
                    if (weapon.maxDamage > 0) {
                        tank.health = 0
                        killOrDrown(tank, tank.creditOwnerId, impactId)
                    }
                }
                damage > 0 -> {
                    tank.health = (tank.health - damage).coerceAtLeast(0)
                    if (tank.health == 0) {
                        killOrDrown(tank, tank.creditOwnerId, impactId)
                    } else if (weapon.dotRounds > 0) {
                        // Overwrites rather than stacks with an already-pending effect - see
                        // Tank.dotRoundsRemaining's own doc. dotSourceOwnerId/dotSourceImpactId
                        // are independent of creditOwnerId (see Tank's own doc) so a later,
                        // unrelated hit can't steal credit for this already-ticking burn.
                        tank.dotRoundsRemaining = weapon.dotRounds
                        tank.dotDamagePerRound = DamageCalculator.percentOfMaxHealth(weapon.dotFraction)
                        tank.dotBurning = weapon.dotIsFire
                        tank.dotSourceOwnerId = shooterOwnerId
                        tank.dotSourceImpactId = impactId
                    }
                }
            }
        }
    }

    /** Whether ([impactX], [impactX] + [blastRadius]) reaches into any already-open Void/Lava
     * [FloorRegion] - see [resolveImpact]'s [TerrainEffect.FILL] branch, the only caller. */
    private fun withinActiveFloorRegion(impactX: Float, blastRadius: Float): Boolean {
        val regions = when (floorType) {
            FloorType.VOID -> activeVoidRegions
            FloorType.LAVA -> activeLavaRegions
            else -> return false
        }
        return regions.any { abs(impactX - it.centerX) <= it.radius + blastRadius }
    }

    /** Marks [tank] dead and queues its death animation - see [Tank.pendingBurn] - and
     * records it in [killedThisResolution] so a mutual-elimination tie (see
     * [finishResolution]) can still name who was actually tied. Also appends a [DeathRecord]
     * and snapshots [killedByOwnerId] onto [Tank.deathKillerOwnerId], so this tank's own later
     * death explosion (see [updateAwaitingExplosion]) keeps crediting the same owner. */
    private fun kill(tank: Tank, killedByOwnerId: Int?, impactId: Int?) {
        tank.alive = false
        tank.pendingBurn = true
        tank.deathKillerOwnerId = killedByOwnerId
        killedThisResolution += tank.id
        recordedDeaths += DeathRecord(tank.ownerId, tank.id, turnNumber, impactId, killedByOwnerId)
    }

    /** Marks [tank] dead and queues its drowning animation - little bubbles, then a
     * death-taunt speech bubble, then flipping upside-down and floating up to (and
     * permanently at) the water's own rising surface - see [Tank.pendingDrown] and its onward
     * chain. Deliberately bypasses [kill]'s ordinary burn/explosion/ash sequence entirely:
     * drowning is peaceful, not violent - no fire, no death-explosion crater, no splash damage
     * to nearby tanks. Appends a [DeathRecord] the same way [kill] does - see its own doc. */
    private fun killByDrowning(tank: Tank, killedByOwnerId: Int?, impactId: Int?) {
        tank.alive = false
        tank.deathKillerOwnerId = killedByOwnerId
        killedThisResolution += tank.id
        tank.pendingDrown = true
        recordedDeaths += DeathRecord(tank.ownerId, tank.id, turnNumber, impactId, killedByOwnerId)
    }

    /** Chooses between the ordinary burn/explosion/ash death ([kill]) and the drowning
     * sequence ([killByDrowning]) based on [tank]'s own submersion right now - a tank whose
     * entire body is underwater never burns, however it was killed (a direct hit, splash
     * damage, or a fall into deep water); a tank that's only partially submerged (or on any
     * non-Water floor) still gets the ordinary sequence. Mirrors [applyDrowningCheck]'s own
     * submersion test exactly. */
    private fun killOrDrown(tank: Tank, killedByOwnerId: Int?, impactId: Int?) {
        if (floorType == FloorType.WATER && tank.y - Tank.RADIUS >= currentWaterLevelY) {
            killByDrowning(tank, killedByOwnerId, impactId)
        } else {
            kill(tank, killedByOwnerId, impactId)
        }
    }

    /** How deep an ordinary weapon/death-explosion impact (see [resolveImpact]) is allowed to
     * carve: [FloorType.GROUND]/[FloorType.WATER]/[FloorType.LAVA] keep the default 95%-depth
     * clamp (a thin floor strip always survives - Lava's own 4% depth cap only bounds its own
     * tracked regions' regrowth, see [growRegion], not ordinary gunfire); every other floor
     * type allows a blast to fully hollow a column down to the map's true bottom. */
    private fun floorMaxGroundY(): Int = when (floorType) {
        FloorType.GROUND, FloorType.WATER, FloorType.LAVA -> CraterCarver.defaultMaxGroundY(terrain)
        else -> terrain.height
    }

    /** [FloorType.VOID]/[FloorType.LAVA] each spawn their own independently-growing
     * [FloorRegion] the first time an explosion's own blast geometry reaches the map's true
     * bottom in a spot no existing region (for that floor type) already covers - grown once
     * immediately here (per [FloorType.VOID]'s own "immediately upon creation" spec, extended
     * to Lava for consistency), then again every completed round (see [processFloorRound]). */
    private fun maybeSeedFloorRegion(impactX: Float, impactY: Float, blastRadius: Float) {
        val regions = when (floorType) {
            FloorType.VOID -> activeVoidRegions
            FloorType.LAVA -> activeLavaRegions
            else -> return
        }
        if (impactY + blastRadius < terrain.height) return
        if (regions.any { abs(impactX - it.centerX) <= it.radius }) return
        val column = impactX.toInt().coerceIn(0, terrain.width - 1)
        val depthCap = if (floorType == FloorType.LAVA) {
            (terrain.groundY[column] + (terrain.height * LAVA_DEPTH_CAP_FRACTION).roundToInt()).coerceAtMost(terrain.height)
        } else {
            null
        }
        val region = FloorRegion(impactX, blastRadius, depthCap)
        regions += region
        growRegion(region)
    }

    /** Grows [region]'s radius by [FLOOR_REGION_GROWTH_FRACTION] of [HeightMap.width] and
     * re-carves the full circle centered at the map's true bottom (the same "genuinely
     * embedded" collapse [CraterCarver] already applies for a deep-enough impact - see its own
     * doc), clamped to [FloorRegion.depthCapGroundY] for Lava or the map's own bottom for Void.
     * Deliberately pushes no [ImpactEffect] - unlike a real weapon impact (see [resolveImpact]),
     * this growth is silent and invisible: the ground itself still visibly opens wider (the
     * renderer's own Void/Lava fill redraws automatically whenever [CraterCarver.carve] bumps
     * [HeightMap.version]), it just doesn't get an explosion flash on top of it. */
    private fun growRegion(region: FloorRegion) {
        region.radius += FLOOR_REGION_GROWTH_FRACTION * terrain.width
        val maxGroundY = region.depthCapGroundY ?: terrain.height
        CraterCarver.carve(terrain, region.centerX.toInt(), terrain.height, region.radius.toInt(), maxGroundY)
    }

    /** Fires once every completed round (see [finishResolution]) for whichever mechanic
     * [floorType] actually has - a no-op for every other floor type. */
    private fun processFloorRound() {
        when (floorType) {
            FloorType.VOID -> activeVoidRegions.forEach { growRegion(it) }
            FloorType.LAVA -> {
                activeLavaRegions.forEach { growRegion(it) }
                applyLavaRoundDamage()
            }
            FloorType.WATER -> {
                raiseWaterLevel()
                applyDrowningCheck()
            }
            else -> Unit
        }
    }

    private fun raiseWaterLevel() {
        currentWaterLevelY = (currentWaterLevelY - WATER_RISE_FRACTION * terrain.height).coerceAtLeast(0f)
    }

    /** Any alive tank fully submerged - even its topmost point at or below the water's own
     * surface - drowns at the round boundary (see [killByDrowning]). */
    private fun applyDrowningCheck() {
        for (tank in tanks) {
            if (!tank.alive) continue
            if (tank.y - Tank.RADIUS >= currentWaterLevelY) {
                // Attributed only if a blast knocked this tank somewhere the rising water then
                // reached (tank.creditOwnerId) - the water simply rising to a tank that never
                // moved is "strictly hazard spreading" and stays unattributed.
                killByDrowning(tank, tank.creditOwnerId, impactId = null)
            }
        }
    }

    /** A tank resting (not [Tank.falling]) on a column horizontally within a lava region's own
     * radius counts as touching it - [LAVA_TOUCH_DAMAGE_FRACTION] of max health, and this
     * always overrides (never stacks with) the proximity burn below for the same tank in the
     * same round. Otherwise, a tank within [LAVA_PROXIMITY_HORIZONTAL_FRACTION] of
     * [HeightMap.width] horizontally and [LAVA_PROXIMITY_VERTICAL_FRACTION] of
     * [HeightMap.height] vertically of a lava region's own surface takes
     * [LAVA_PROXIMITY_DAMAGE_FRACTION] of max health in burning damage. */
    private fun applyLavaRoundDamage() {
        for (tank in tanks) {
            if (!tank.alive) continue
            if (isTouchingLava(tank)) {
                applyLavaDamage(tank, LAVA_TOUCH_DAMAGE_FRACTION)
                continue
            }
            if (isNearLava(tank)) {
                applyLavaDamage(tank, LAVA_PROXIMITY_DAMAGE_FRACTION)
            }
        }
    }

    private fun isTouchingLava(tank: Tank): Boolean =
        activeLavaRegions.any { region -> !tank.falling && abs(tank.x - region.centerX) <= region.radius }

    private fun isNearLava(tank: Tank): Boolean = activeLavaRegions.any { region ->
        val horizontalGap = abs(tank.x - region.centerX) - region.radius
        val verticalGap = abs(tank.y - terrain.heightAt(region.centerX.toInt()).toFloat())
        horizontalGap in 0f..(LAVA_PROXIMITY_HORIZONTAL_FRACTION * terrain.width) &&
            verticalGap <= LAVA_PROXIMITY_VERTICAL_FRACTION * terrain.height
    }

    private fun applyLavaDamage(tank: Tank, fraction: Float) {
        tank.health = (tank.health - DamageCalculator.percentOfMaxHealth(fraction)).coerceAtLeast(0)
        // Attributed only if a blast is why this tank is here at all (tank.creditOwnerId) - a
        // tank that walked into lava that grew to reach it on its own has no credit to give.
        if (tank.health == 0) killOrDrown(tank, tank.creditOwnerId, impactId = null)
    }

    /** Weapon-inflicted damage-over-time (Nuke/Napalm - see [Tank.dotRoundsRemaining]'s own
     * doc and [resolveImpact]'s [Weapon.dotRounds] handling) - called once per completed round
     * from [finishResolution], the same boundary [processFloorRound] fires at, but independent
     * of [floorType] since this is weapon-driven, not floor-driven. */
    private fun applyPendingDotDamage() {
        for (tank in tanks) {
            if (!tank.alive || tank.dotRoundsRemaining <= 0) continue
            tank.health = (tank.health - tank.dotDamagePerRound).coerceAtLeast(0)
            tank.dotRoundsRemaining--
            if (tank.health == 0) {
                // Whoever applied this DoT gets credit, not whatever tank.creditOwnerId
                // currently holds - see Tank.dotSourceOwnerId's own doc.
                killOrDrown(tank, tank.dotSourceOwnerId, tank.dotSourceImpactId)
            } else if (tank.dotRoundsRemaining <= 0) {
                tank.dotBurning = false
            }
        }
    }

    /** [FloorType.HOLE]/[FloorType.WRAP]/[FloorType.VOID] only: a tank that settles onto a
     * fully-open column (see [applyTankGravity]) dies instantly, with none of the ordinary
     * burn/explosion/ash sequence - there's no ground left for one to play out on. The only
     * remaining trace is its usual random death-taunt speech bubble (see
     * [com.scorchedphoto.app.game.GameRenderer]'s reuse of the same taunt-assignment cache a
     * burning tank's bubble already uses), anchored at [Tank.fallThroughAnchorY] - the fixed
     * point it fell through, not wherever it might otherwise have kept falling to. */
    private fun killByFallingThroughFloor(tank: Tank) {
        tank.alive = false
        // A fall-through only ever happens because some blast fully hollowed this column (a
        // live shot) or a hazard region grew to reach it (unattributed) - tank.creditOwnerId
        // already distinguishes the two, same as applyFallDamage/applyLavaDamage.
        val killedByOwnerId = tank.creditOwnerId
        tank.deathKillerOwnerId = killedByOwnerId
        killedThisResolution += tank.id
        tank.fallingThroughFloor = true
        tank.fallThroughElapsed = 0f
        tank.fallThroughAnchorY = terrain.height.toFloat()
        tank.falling = false
        tank.fallVelocity = 0f
        recordedDeaths += DeathRecord(tank.ownerId, tank.id, turnNumber, impactId = null, killedByOwnerId)
    }

    /** Clears [Tank.fallingThroughFloor] (and with it, its death-taunt speech bubble) once
     * [FALL_THROUGH_BUBBLE_SECONDS] has elapsed since [killByFallingThroughFloor] - see [tick]'s
     * `deathAnimationsDone`, which waits for this the same way it waits for an ordinary death's
     * burn/explosion/ash sequence. */
    private fun updateFallingThroughFloor(dt: Float) {
        for (tank in tanks) {
            if (!tank.fallingThroughFloor) continue
            tank.fallThroughElapsed += dt
            if (tank.fallThroughElapsed >= FALL_THROUGH_BUBBLE_SECONDS) {
                tank.fallingThroughFloor = false
            }
        }
    }

    /** Mirrors [startPendingBurns] exactly, but for [Tank.pendingDrown] - waits for no active
     * projectiles/impact effects and a tank that's actually landed before starting the bubble
     * phase, so a drowning death's own animation never overlaps a still-playing blast/fall. */
    private fun startPendingDrowns() {
        if (activeProjectiles.isNotEmpty() || activeImpactEffects.isNotEmpty()) return
        for (tank in tanks) {
            if (!tank.pendingDrown || tank.falling) continue
            tank.pendingDrown = false
            tank.drowningBubbles = true
            tank.drowningBubblesElapsed = 0f
        }
    }

    private fun updateDrowningBubbles(dt: Float) {
        for (tank in tanks) {
            if (!tank.drowningBubbles) continue
            tank.drowningBubblesElapsed += dt
            if (tank.drowningBubblesElapsed >= DROWNING_BUBBLES_DURATION_SECONDS) {
                tank.drowningBubbles = false
                tank.drowningSpeech = true
                tank.drowningSpeechElapsed = 0f
            }
        }
    }

    private fun updateDrowningSpeech(dt: Float) {
        for (tank in tanks) {
            if (!tank.drowningSpeech) continue
            tank.drowningSpeechElapsed += dt
            if (tank.drowningSpeechElapsed >= DROWNING_SPEECH_DURATION_SECONDS) {
                tank.drowningSpeech = false
                tank.rising = true
                tank.risingElapsed = 0f
            }
        }
    }

    /** The final drowning phase - flips upside-down and floats from wherever gravity last left
     * it (see [applyTankGravity]'s own skip for [Tank.rising]) up to the live water surface,
     * over [DROWNING_RISE_DURATION_SECONDS] - see [com.scorchedphoto.app.game.GameRenderer]'s
     * `drawRisingTank` for the actual tween. [Tank.isDrowned] afterward is permanent - the
     * drowning equivalent of [Tank.isAsh] - and, like it, excluded from [deathAnimationsDone]. */
    private fun updateRisingTanks(dt: Float) {
        for (tank in tanks) {
            if (!tank.rising) continue
            tank.risingElapsed += dt
            if (tank.risingElapsed >= DROWNING_RISE_DURATION_SECONDS) {
                tank.rising = false
                tank.isDrowned = true
            }
        }
    }

    private fun splitMirv(parent: Projectile): List<Projectile> {
        val weapon = parent.weapon
        val childWeapon = weapon.copy(childCount = 1, childSpreadDegrees = 0f, splitPattern = SplitPattern.NONE)
        val speed = hypot(parent.vx.toDouble(), parent.vy.toDouble())
        val baseAngle = kotlin.math.atan2(parent.vy.toDouble(), parent.vx.toDouble())
        val spreadRad = Math.toRadians(weapon.childSpreadDegrees.toDouble())

        return (0 until weapon.childCount).map { i ->
            val t = if (weapon.childCount == 1) 0.0 else (i.toDouble() / (weapon.childCount - 1)) - 0.5
            val angle = baseAngle + t * spreadRad
            Projectile(
                x = parent.x,
                y = parent.y,
                vx = (kotlin.math.cos(angle) * speed).toFloat(),
                vy = (kotlin.math.sin(angle) * speed).toFloat(),
                weapon = childWeapon,
                ownerTankId = parent.ownerTankId,
                hasPassedApex = true,
            )
        }
    }

    /** Spread MIRV's split geometry: unlike [splitMirv]'s radial fan (centered on the parent's
     * current heading), this always spreads horizontally - every child keeps the parent's own
     * `vy` unchanged (so they all still fall in roughly the same time window) and only offsets
     * `vx` in fixed [Weapon.horizontalSpreadSpeed] steps around the parent's own `vx`. With 5
     * children this gives exactly 2 short (behind), 1 unchanged (the parent's own original
     * path), and 2 long (ahead) - a wide horizontal line rather than a fan. */
    private fun splitHorizontalLine(parent: Projectile): List<Projectile> {
        val weapon = parent.weapon
        val childWeapon = weapon.copy(childCount = 1, splitPattern = SplitPattern.NONE)
        val half = weapon.childCount / 2

        return (-half..half).map { step ->
            Projectile(
                x = parent.x,
                y = parent.y,
                vx = parent.vx + step * weapon.horizontalSpreadSpeed,
                vy = parent.vy,
                weapon = childWeapon,
                ownerTankId = parent.ownerTankId,
                hasPassedApex = true,
            )
        }
    }

    private fun applyTankGravity(dt: Float) {
        for (tank in tanks) {
            // Fell through and gone (see killByFallingThroughFloor), or already mid-rise/
            // permanently floating (see updateRisingTanks) - none of these ever participate in
            // gravity again; a rising/drowned tank's vertical position is driven entirely by
            // its own risingElapsed tween (GameRenderer) or the live water level, never tank.y.
            if (tank.fallingThroughFloor || tank.rising || tank.isDrowned) continue
            // A tank that just died (an ordinary kill - pendingBurn - or a drowning death -
            // pendingDrown) keeps falling until it actually lands - skipping gravity for it the
            // instant it dies (like every other dead tank) would leave it frozen hovering
            // wherever the killing blow found it, even when the blast that killed it also blew
            // away the ground underneath - see startPendingBurns/startPendingDrowns, which wait
            // for tank.falling to clear before starting the death animation. Once the animation
            // itself starts (burning/awaitingExplosion/exploding, or drowningBubbles/
            // drowningSpeech), gravity stops touching it again until isAsh (ordinary deaths
            // only - a drowned tank never becomes ash, it goes drowningSpeech -> rising ->
            // isDrowned instead, so there is no "resume forever" phase for it). isAsh itself
            // keeps settling the same way for as long as the match goes on, so a later blast
            // digging out the ground underneath it makes it fall too, instead of hanging in
            // mid-air over its own crater.
            if (!tank.alive && !tank.pendingBurn && !tank.pendingDrown && !tank.isAsh) continue
            val surfaceY = terrain.heightAt(tank.x.toInt()).toFloat()
            if (tank.y < surfaceY - FALL_SETTLE_EPSILON) {
                tank.falling = true
                tank.fallVelocity += GRAVITY * dt
                tank.y = min(tank.y + tank.fallVelocity * dt, surfaceY)
            } else if (tank.alive && surfaceY >= terrain.height && floorType in FALL_THROUGH_FLOOR_TYPES) {
                // The column it's settling onto has no floor left at all - under Hole/Wrap/
                // Void, that's a fatal fall through the map, not a landing. Ash piles are
                // excluded (already dead - already-alive-false is covered by the tank.alive
                // check itself, since only a still-alive tank can be freshly killed this way).
                killByFallingThroughFloor(tank)
            } else {
                // Ash has no health left to lose, so a fall never damages/re-kills it -
                // only a still-alive-or-dying tank's fall does.
                if (tank.falling && !tank.isAsh) {
                    applyFallDamage(tank)
                }
                tank.falling = false
                tank.fallVelocity = 0f
                tank.y = surfaceY
            }
        }
    }

    /**
     * A fall (a blast removing the ground beneath a tank) damages it like any other
     * ordinary damage source - proportional to how far it dropped, but never an
     * automatic kill the way a direct hit is: a tank only dies from a fall if the
     * damage happens to bring its existing health to 0, same as splash damage.
     */
    private fun applyFallDamage(tank: Tank) {
        // A tank already dead from an earlier cause (e.g. the same blast that carved away its
        // ground also killed it outright) keeps "falling" purely for its death animation's own
        // sake - see applyTankGravity's own doc - and must never be killed a second time once
        // its fall actually completes and lands.
        if (!tank.alive) return
        // v^2 = 2*g*distance (constant acceleration from rest), using the fall's final
        // velocity right before it's reset below - avoids needing to track a separate
        // "fall started at" position on Tank.
        val fallDistance = (tank.fallVelocity * tank.fallVelocity) / (2f * GRAVITY)
        if (fallDistance <= FALL_DAMAGE_MIN_DISTANCE) return
        val damage = ((fallDistance - FALL_DAMAGE_MIN_DISTANCE) * FALL_DAMAGE_PER_PIXEL).toInt()
        if (damage > 0) {
            tank.health = (tank.health - damage).coerceAtLeast(0)
            if (tank.health == 0) {
                // A fall only ever happens because some blast removed the tank's ground -
                // tank.creditOwnerId already holds whoever's blast most recently did that (or
                // null if it was never a live shot, e.g. a naturally-spreading Void region).
                killOrDrown(tank, tank.creditOwnerId, impactId = null)
            }
        }
    }

    private fun updateBurningTanks(dt: Float) {
        for (tank in tanks) {
            if (!tank.burning) continue
            tank.burningElapsed += dt
            if (tank.burningElapsed >= TANK_BURNING_DURATION_SECONDS) {
                tank.burning = false
                tank.awaitingExplosion = true
                tank.awaitingExplosionElapsed = 0f
            }
        }
    }

    /**
     * A brief silent beat after the burn animation ends and before the final death
     * explosion fires - see [Tank.awaitingExplosion] - so the explosion reads as its own
     * distinct event rather than the fire animation's abrupt tail end. That explosion
     * carves the terrain and damages nearby tanks exactly like a real weapon impact - see
     * [resolveImpact]/[WeaponCatalog.TANK_DEATH_EXPLOSION] - so a tank going out can take
     * others (or its own resting place) with it, same as any other blast would.
     */
    private fun updateAwaitingExplosion(dt: Float) {
        for (tank in tanks) {
            if (!tank.awaitingExplosion) continue
            tank.awaitingExplosionElapsed += dt
            if (tank.awaitingExplosionElapsed >= DEATH_EXPLOSION_PAUSE_SECONDS) {
                tank.awaitingExplosion = false
                tank.exploding = true
                tank.explodingElapsed = 0f
                // Propagates whoever was credited for killing this tank onward to anyone this
                // explosion itself kills - see Tank.deathKillerOwnerId's own doc - so a whole
                // chain of explosions keeps crediting back to whoever started it.
                resolveImpact(WeaponCatalog.TANK_DEATH_EXPLOSION, tank.x, tank.y, tank.deathKillerOwnerId)
                pendingEvents += GameEvent.TankExploded(tank.id)
            }
        }
    }

    /**
     * The death explosion's own growth phase (see [resolveImpact]'s [ImpactEffect], grown
     * over [DEATH_EXPLOSION_GROWTH_SECONDS] by GameRenderer) - the tank body stays visible
     * and [Tank.isAsh] stays false until the blast has actually grown to full size, so the
     * tank doesn't vanish out from under its own still-expanding explosion.
     */
    private fun updateExploding(dt: Float) {
        for (tank in tanks) {
            if (!tank.exploding) continue
            tank.explodingElapsed += dt
            if (tank.explodingElapsed >= DEATH_EXPLOSION_GROWTH_SECONDS) {
                tank.exploding = false
                tank.isAsh = true
            }
        }
    }

    /**
     * Tanks that died this resolution wait in [Tank.pendingBurn] rather than starting
     * their burn animation immediately - only once every projectile explosion (both
     * still-flying projectiles and still-animating impact flashes) has fully finished,
     * and the tank has actually landed ([Tank.falling] clear - see [applyTankGravity]),
     * do they actually start burning. That keeps a death's fire animation from overlapping
     * either the blast that caused it or a still-playing fall.
     */
    private fun startPendingBurns() {
        if (activeProjectiles.isNotEmpty() || activeImpactEffects.isNotEmpty()) return
        for (tank in tanks) {
            if (!tank.pendingBurn || tank.falling) continue
            tank.pendingBurn = false
            tank.burning = true
            tank.burningElapsed = 0f
        }
    }

    private fun ageImpactEffects(dt: Float) {
        activeImpactEffects.forEach { it.age += dt }
        activeImpactEffects.removeAll { it.age > IMPACT_EFFECT_LIFETIME_SECONDS }
    }

    private fun ageBounceEffects(dt: Float) {
        activeBounceEffects.forEach { it.age += dt }
        activeBounceEffects.removeAll { it.age > BOUNCE_EFFECT_LIFETIME_SECONDS }
    }

    /** A round (see [processFloorRound]/[applyPendingDotDamage]) is checked for and processed
     * *before* the win check, not after - so a Water/Lava round (or a Nuke/Napalm DoT tick)
     * that eliminates the last standing side can end the match on this same turn, instead of
     * leaving it stalled one turn behind. Guarded by [awaitingRoundAnimations] so that
     * increment/`processFloorRound()`/`applyPendingDotDamage()` only ever actually run once per
     * round boundary, even though this function can now be re-entered several times in a row
     * while a round-boundary kill's own death animation (Water's drowning, Lava's damage, a DoT
     * tick) plays out - `processFloorRound()`/`applyPendingDotDamage()` can themselves just have
     * started a brand-new one on a tank `tick()`'s own pre-call [deathAnimationsDone] check had
     * no way to know about yet, so the turn can't advance/a win can't be declared until that
     * finishes too. */
    private fun finishResolution() {
        if (!awaitingRoundAnimations) {
            turnsCompletedThisRound++
            if (turnsCompletedThisRound >= tanksAliveAtRoundStart) {
                processFloorRound()
                applyPendingDotDamage()
                expireStaleCredit()
                turnsCompletedThisRound = 0
                tanksAliveAtRoundStart = tanks.count { it.alive }
            }
        }

        if (!deathAnimationsDone()) {
            awaitingRoundAnimations = true
            phase = MatchPhase.RESOLVING
            return
        }
        awaitingRoundAnimations = false

        val result = turnManager.checkWinCondition() ?: mutualEliminationTie()
        if (result != null) {
            winResult = result
            phase = MatchPhase.GAME_OVER
            return
        }
        turnNumber++
        turnManager.advanceToNextAliveTank()
        wind.reroll(maxWindMagnitude, rng)
        phase = MatchPhase.AIMING
    }

    /** Bounds how long a stale [Tank.creditOwnerId] can linger (see its own doc and rule 6 in
     * [resolveImpact]'s doc): once a full round passes with a tank clear of every ongoing
     * hazard threat - not falling, no active DoT, not touching/near lava - whatever blast last
     * touched it is no longer relevant to anything that might kill it later, so credit resets
     * to `null`. Called once per completed round, right alongside [processFloorRound]/
     * [applyPendingDotDamage]. */
    private fun expireStaleCredit() {
        for (tank in tanks) {
            if (!tank.alive || tank.falling || tank.dotRoundsRemaining > 0) continue
            if (isTouchingLava(tank) || isNearLava(tank)) continue
            tank.creditOwnerId = null
        }
    }

    /**
     * [TurnManager.checkWinCondition] can only name a winner when exactly one owner still
     * has a tank standing - it returns null (not a tie) both while the match is still
     * ongoing and when literally every remaining tank was eliminated by the same
     * resolution (e.g. one blast reaching everyone left, or the shooter also dying to a
     * fall from the same shot). This distinguishes the two: only when nobody survived does
     * it declare a tie, among whichever owners [killedThisResolution] shows actually died
     * just now - never among tanks eliminated in earlier, separate turns.
     */
    private fun mutualEliminationTie(): WinResult? {
        if (tanks.any { it.alive } || killedThisResolution.isEmpty()) return null
        val tiedTanks = tanks.filter { it.id in killedThisResolution }
        return WinResult(tiedTanks.map { it.ownerId }.distinct(), tiedTanks.map { it.id })
    }

    companion object {
        private const val FALL_SETTLE_EPSILON = 0.5f

        // Below this fall distance, damage is 0 (just a minor settle, not a real fall).
        // 100 fall pixels beyond that deals 32 damage - notable but not close to lethal
        // for a full-health tank on its own; only a very large fall (or a tank already
        // hurt) can actually kill, matching "not an automatic kill" like a direct hit is.
        private const val FALL_DAMAGE_MIN_DISTANCE = 20f
        private const val FALL_DAMAGE_PER_PIXEL = 0.4f
        private const val TANK_BURNING_DURATION_SECONDS = 2f
        private const val DEATH_EXPLOSION_PAUSE_SECONDS = 0.5f
        // Mirrors GameRenderer's own GROWTH_SECONDS constant (the death explosion's growth
        // sub-phase, out of its three growth/hold/fade phases) - kept separate since that
        // split is a rendering concern, not engine state; this copy exists only so the
        // engine knows when the tank body itself should switch to Tank.isAsh (see
        // updateExploding), not to drive any visual growth animation itself.
        private const val DEATH_EXPLOSION_GROWTH_SECONDS = 0.125f
        // Explosion animation: 0.125s growth + 0.25s hold + 0.25s fade = 0.625s total
        private const val IMPACT_EFFECT_LIFETIME_SECONDS = 0.625f
        private const val BOUNCE_EFFECT_LIFETIME_SECONDS = 0.25f

        // Reuses TANK_BURNING_DURATION_SECONDS's value for a consistent taunt-display length,
        // kept as its own named constant since it's conceptually a different thing (a
        // fall-through death has no burn animation at all - see killByFallingThroughFloor).
        private const val FALL_THROUGH_BUBBLE_SECONDS = TANK_BURNING_DURATION_SECONDS

        private val FALL_THROUGH_FLOOR_TYPES = setOf(FloorType.HOLE, FloorType.WRAP, FloorType.VOID)

        // Void/Lava's own per-round radial spread (see GameEngine.growRegion) - 2% of
        // terrain.width per round, per the horizontal-percentages-use-width decision.
        private const val FLOOR_REGION_GROWTH_FRACTION = 0.02f

        // Water's per-round rise (see raiseWaterLevel) - 2% of terrain.height per round, per
        // the vertical-percentages-use-height decision.
        private const val WATER_RISE_FRACTION = 0.02f

        // The drowning sequence's own 3 phases (see killByDrowning's onward chain) - bubbles,
        // then a speech bubble, then the flip-and-float-to-the-surface animation.
        // DROWNING_SPEECH_DURATION_SECONDS reuses TANK_BURNING_DURATION_SECONDS's value for a
        // consistent taunt-display length, same rationale as FALL_THROUGH_BUBBLE_SECONDS's own
        // reuse of it. GameRenderer keeps its own copy of DROWNING_RISE_DURATION_SECONDS (a
        // rendering-only concern - the actual rise tween), which must be kept numerically in
        // sync with this one by hand.
        private const val DROWNING_BUBBLES_DURATION_SECONDS = 1.5f
        private const val DROWNING_SPEECH_DURATION_SECONDS = TANK_BURNING_DURATION_SECONDS
        private const val DROWNING_RISE_DURATION_SECONDS = 1.5f

        // Lava's own regrowth never carves deeper than this fraction of terrain.height below
        // wherever its region was first seeded (see maybeSeedFloorRegion) - ordinary gunfire on
        // a Lava floor is unaffected, see floorMaxGroundY's own doc.
        private const val LAVA_DEPTH_CAP_FRACTION = 0.04f

        // Lava round damage (see applyLavaRoundDamage) - touching always overrides (never
        // stacks with) the proximity burn for the same tank in the same round.
        private const val LAVA_TOUCH_DAMAGE_FRACTION = 0.25f
        private const val LAVA_PROXIMITY_DAMAGE_FRACTION = 0.10f
        private const val LAVA_PROXIMITY_HORIZONTAL_FRACTION = 0.05f
        private const val LAVA_PROXIMITY_VERTICAL_FRACTION = 0.02f
    }
}
