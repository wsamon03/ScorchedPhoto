package com.scorchedphoto.engine

import com.scorchedphoto.engine.combat.DamageCalculator
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
import com.scorchedphoto.engine.turns.TurnManager
import com.scorchedphoto.engine.turns.WinResult
import com.scorchedphoto.terrain.HeightMap
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

enum class MatchPhase { AIMING, FIRING, RESOLVING, GAME_OVER }

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
    private val rng: Random = Random.Default,
) {
    private val turnManager = TurnManager(tanks)

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
        startPendingBurns()

        // Explosions (from projectiles still flying or still-animating impact flashes)
        // must fully finish before any death animation begins - see startPendingBurns -
        // and death animations (burning, its pause, then its own closing explosion) must
        // fully finish before the turn can advance or a win can be declared.
        val explosionsDone = activeProjectiles.isEmpty() && activeImpactEffects.isEmpty()
        // `it.exploding` is included for clarity/defense-in-depth even though it's currently
        // redundant against explosionsDone's activeImpactEffects.isEmpty() gate:
        // DEATH_EXPLOSION_GROWTH_SECONDS is strictly less than the impact effect's own
        // IMPACT_EFFECT_LIFETIME_SECONDS, so activeImpactEffects is guaranteed still
        // non-empty for as long as any tank.exploding is true.
        val deathAnimationsDone = tanks.none { it.burning || it.pendingBurn || it.awaitingExplosion || it.exploding }
        if (explosionsDone && tanks.none { it.falling } && deathAnimationsDone) {
            finishResolution()
        } else {
            phase = MatchPhase.RESOLVING
        }
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
            stepProjectile(p, wind, dt)

            if (p.weapon.childCount > 1 && !wasPastApex && p.hasPassedApex) {
                (spawned ?: mutableListOf<Projectile>().also { spawned = it }) += splitMirv(p)
                iterator.remove()
                continue
            }

            val column = p.x.toInt().coerceIn(0, terrain.width - 1)
            val terrainY = terrain.heightAt(column)
            val touchedTank = tanks.any { tank ->
                tank.alive && tank.id != p.ownerTankId &&
                    hypot((tank.x - p.x).toDouble(), (tank.y - p.y).toDouble()) < Tank.RADIUS
            }

            when {
                // Impact point is always anchored to the ground surface at the
                // projectile's own x, whether it stopped by touching a tank's body (which
                // can happen while the projectile is still somewhat elevated - tanks are
                // sizable now) or by reaching the ground directly - an explosion doesn't
                // float in mid-air, and this keeps DamageCalculator's per-tank distance
                // primarily a function of horizontal aim precision, matching how a real
                // shot's accuracy is judged. Each affected tank's own distance to this
                // point (not snapped to any specific tank's center) drives its damage.
                touchedTank || p.y >= terrainY -> {
                    resolveImpact(p.weapon, p.x, terrainY.toFloat())
                    pendingEvents += GameEvent.Impact
                    iterator.remove()
                }
                wallType != EdgeType.NONE && (p.x <= 0f || p.x >= terrain.width) ->
                    handleEdgeBounce(iterator, p, wallType, isWall = true)
                ceilingType != EdgeType.NONE && p.y <= 0f ->
                    handleEdgeBounce(iterator, p, ceilingType, isWall = false)
                p.x < -terrain.width || p.x > 2 * terrain.width -> iterator.remove() // fizzle, flew off into the void
            }
        }

        spawned?.let { activeProjectiles += it }
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
                resolveImpact(p.weapon, p.x, p.y)
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
                    resolveImpact(p.weapon, p.x, ceilingWrapDepthY)
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
        EdgeType.PADDED -> 0.2f
        EdgeType.RUBBER -> 0.6f
        EdgeType.SPRING -> 1.2f
        EdgeType.REFLECTIVE -> 1.0f
        else -> error("velocityRetention called for non-bounce EdgeType $edgeType")
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
     */
    private fun resolveImpact(weapon: Weapon, impactX: Float, impactY: Float) {
        CraterCarver.carve(terrain, impactX.toInt(), impactY.toInt(), weapon.blastRadius.toInt())
        activeImpactEffects += ImpactEffect(impactX, impactY, weapon.blastRadius)
        for (tank in tanks) {
            if (!tank.alive) continue
            val damage = DamageCalculator.computeDamage(weapon, impactX, impactY, tank)
            when {
                damage == null -> {
                    if (weapon.maxDamage > 0) {
                        tank.health = 0
                        kill(tank)
                    }
                }
                damage > 0 -> {
                    tank.health = (tank.health - damage).coerceAtLeast(0)
                    if (tank.health == 0) {
                        kill(tank)
                    }
                }
            }
        }
    }

    /** Marks [tank] dead and queues its death animation - see [Tank.pendingBurn] - and
     * records it in [killedThisResolution] so a mutual-elimination tie (see
     * [finishResolution]) can still name who was actually tied. */
    private fun kill(tank: Tank) {
        tank.alive = false
        tank.pendingBurn = true
        killedThisResolution += tank.id
    }

    private fun splitMirv(parent: Projectile): List<Projectile> {
        val weapon = parent.weapon
        val childWeapon = weapon.copy(childCount = 1, childSpreadDegrees = 0f)
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

    private fun applyTankGravity(dt: Float) {
        for (tank in tanks) {
            // A tank that just died keeps falling until it actually lands - skipping
            // gravity for it the instant it dies (like every other dead tank) would leave
            // it frozen hovering wherever the killing blow found it, even when the blast
            // that killed it also blew away the ground underneath - see startPendingBurns,
            // which waits for tank.falling to clear before starting the death animation.
            // An ash pile (see Tank.isAsh) keeps settling the same way for as long as the
            // match goes on, so a later blast digging out the ground underneath it makes
            // it fall too, instead of hanging in mid-air over its own crater.
            if (!tank.alive && !tank.pendingBurn && !tank.isAsh) continue
            val surfaceY = terrain.heightAt(tank.x.toInt()).toFloat()
            if (tank.y < surfaceY - FALL_SETTLE_EPSILON) {
                tank.falling = true
                tank.fallVelocity += GRAVITY * dt
                tank.y = min(tank.y + tank.fallVelocity * dt, surfaceY)
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
        // v^2 = 2*g*distance (constant acceleration from rest), using the fall's final
        // velocity right before it's reset below - avoids needing to track a separate
        // "fall started at" position on Tank.
        val fallDistance = (tank.fallVelocity * tank.fallVelocity) / (2f * GRAVITY)
        if (fallDistance <= FALL_DAMAGE_MIN_DISTANCE) return
        val damage = ((fallDistance - FALL_DAMAGE_MIN_DISTANCE) * FALL_DAMAGE_PER_PIXEL).toInt()
        if (damage > 0) {
            tank.health = (tank.health - damage).coerceAtLeast(0)
            if (tank.health == 0) {
                kill(tank)
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
                resolveImpact(WeaponCatalog.TANK_DEATH_EXPLOSION, tank.x, tank.y)
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

    private fun finishResolution() {
        val result = turnManager.checkWinCondition() ?: mutualEliminationTie()
        if (result != null) {
            winResult = result
            phase = MatchPhase.GAME_OVER
            return
        }
        turnManager.advanceToNextAliveTank()
        wind.reroll(maxWindMagnitude, rng)
        phase = MatchPhase.AIMING
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
    }
}
