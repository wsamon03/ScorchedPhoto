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
    private val maxWindMagnitude: Float = 15f,
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

    fun fire(): Boolean {
        val shooter = currentTank ?: return false
        if (phase != MatchPhase.AIMING) return false
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
        return true
    }

    fun tick(dt: Float) {
        if (phase != MatchPhase.FIRING && phase != MatchPhase.RESOLVING) return

        tickProjectiles(dt)
        applyTankGravity(dt)
        ageImpactEffects(dt)

        if (activeProjectiles.isEmpty() && tanks.none { it.falling }) {
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
        for (tank in tanks) {
            if (!tank.alive) continue
            val damage = DamageCalculator.computeDamage(projectile.weapon, impactX, impactY, tank)
            when {
                damage == null -> {
                    if (projectile.weapon.maxDamage > 0) {
                        tank.health = 0
                        tank.alive = false
                    }
                }
                damage > 0 -> {
                    tank.health = (tank.health - damage).coerceAtLeast(0)
                    if (tank.health == 0) tank.alive = false
                }
            }
        }
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
            if (!tank.alive) continue
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
            if (tank.health == 0) tank.alive = false
        }
    }

    private fun ageImpactEffects(dt: Float) {
        activeImpactEffects.forEach { it.age += dt }
        activeImpactEffects.removeAll { it.age > IMPACT_EFFECT_LIFETIME_SECONDS }
    }

    private fun finishResolution() {
        val result = turnManager.checkWinCondition()
        if (result != null) {
            winResult = result
            phase = MatchPhase.GAME_OVER
            return
        }
        turnManager.advanceToNextAliveTank()
        wind.reroll(maxWindMagnitude, rng)
        phase = MatchPhase.AIMING
    }

    companion object {
        private const val FALL_SETTLE_EPSILON = 0.5f

        // Below this fall distance, damage is 0 (just a minor settle, not a real fall).
        // 100 fall pixels beyond that deals 32 damage - notable but not close to lethal
        // for a full-health tank on its own; only a very large fall (or a tank already
        // hurt) can actually kill, matching "not an automatic kill" like a direct hit is.
        private const val FALL_DAMAGE_MIN_DISTANCE = 20f
        private const val FALL_DAMAGE_PER_PIXEL = 0.4f
        private const val IMPACT_EFFECT_LIFETIME_SECONDS = 0.4f
    }
}
