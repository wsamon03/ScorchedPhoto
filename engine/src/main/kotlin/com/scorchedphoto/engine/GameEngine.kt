package com.scorchedphoto.engine

import com.scorchedphoto.engine.combat.DamageCalculator
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
    private val pendingEvents = mutableListOf<GameEvent>()

    // Ids of tanks killed since the current shot was fired - reset at the start of each
    // fire(). Used only for the mutual-elimination tie case in finishResolution(), where
    // TurnManager.checkWinCondition() has no alive tank left to identify a winner from.
    private val killedThisResolution = mutableSetOf<Int>()

    var phase: MatchPhase = MatchPhase.AIMING
        private set

    var winResult: WinResult? = null
        private set

    val currentTank: Tank? get() = turnManager.currentTank
    val projectiles: List<Projectile> get() = activeProjectiles

    /** Short-lived flashes at recent impact points, for the renderer to fade out. */
    val impactEffects: List<ImpactEffect> get() = activeImpactEffects

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
        updateBurningTanks(dt)
        updateAwaitingExplosion(dt)
        startPendingBurns()

        // Explosions (from projectiles still flying or still-animating impact flashes)
        // must fully finish before any death animation begins - see startPendingBurns -
        // and death animations (burning, its pause, then its own closing explosion) must
        // fully finish before the turn can advance or a win can be declared.
        val explosionsDone = activeProjectiles.isEmpty() && activeImpactEffects.isEmpty()
        val deathAnimationsDone = tanks.none { it.burning || it.pendingBurn || it.awaitingExplosion }
        if (explosionsDone && tanks.none { it.falling } && deathAnimationsDone) {
            finishResolution()
        } else {
            phase = MatchPhase.RESOLVING
        }
    }

    private fun tickProjectiles(dt: Float) {
        if (activeProjectiles.isEmpty()) return

        val stillFlying = mutableListOf<Projectile>()
        val toSplit = mutableListOf<Projectile>()

        for (p in activeProjectiles) {
            val wasPastApex = p.hasPassedApex
            stepProjectile(p, wind, dt)

            if (p.weapon.childCount > 1 && !wasPastApex && p.hasPassedApex) {
                toSplit += p
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
                touchedTank || p.y >= terrainY -> resolveImpact(p, p.x, terrainY.toFloat())
                p.x < -terrain.width || p.x > 2 * terrain.width -> Unit // fizzle, flew off into the void
                else -> stillFlying += p
            }
        }

        for (parent in toSplit) {
            stillFlying += splitMirv(parent)
        }

        activeProjectiles.clear()
        activeProjectiles += stillFlying
    }

    /**
     * Every alive tank within reach takes damage from [DamageCalculator], keyed purely
     * on its own distance from ([impactX], [impactY]) - a "bullseye" (the tank's central
     * 20%, see [DamageCalculator]) returns `null` and destroys that tank outright
     * regardless of current health; everything else is an ordinary amount that only
     * kills if it actually brings health to 0.
     */
    private fun resolveImpact(projectile: Projectile, impactX: Float, impactY: Float) {
        CraterCarver.carve(terrain, impactX.toInt(), impactY.toInt(), projectile.weapon.blastRadius.toInt())
        activeImpactEffects += ImpactEffect(impactX, impactY, projectile.weapon.blastRadius)
        pendingEvents += GameEvent.Impact
        for (tank in tanks) {
            if (!tank.alive) continue
            val damage = DamageCalculator.computeDamage(projectile.weapon, impactX, impactY, tank)
            when {
                damage == null -> {
                    if (projectile.weapon.maxDamage > 0) {
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
            if (!tank.alive && !tank.pendingBurn) continue
            val surfaceY = terrain.heightAt(tank.x.toInt()).toFloat()
            if (tank.y < surfaceY - FALL_SETTLE_EPSILON) {
                tank.falling = true
                tank.fallVelocity += GRAVITY * dt
                tank.y = min(tank.y + tank.fallVelocity * dt, surfaceY)
            } else {
                if (tank.falling) {
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
     * distinct event rather than the fire animation's abrupt tail end.
     */
    private fun updateAwaitingExplosion(dt: Float) {
        for (tank in tanks) {
            if (!tank.awaitingExplosion) continue
            tank.awaitingExplosionElapsed += dt
            if (tank.awaitingExplosionElapsed >= DEATH_EXPLOSION_PAUSE_SECONDS) {
                tank.awaitingExplosion = false
                tank.isAsh = true
                activeImpactEffects += ImpactEffect(tank.x, tank.y, Tank.RADIUS * 3f)
                pendingEvents += GameEvent.TankExploded(tank.id)
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
        // Explosion animation: 0.125s growth + 0.25s hold + 0.25s fade = 0.625s total
        private const val IMPACT_EFFECT_LIFETIME_SECONDS = 0.625f
    }
}
