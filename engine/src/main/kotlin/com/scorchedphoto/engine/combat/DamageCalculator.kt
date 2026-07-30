package com.scorchedphoto.engine.combat

import com.scorchedphoto.engine.tanks.Tank
import kotlin.math.hypot
import kotlin.math.roundToInt

object DamageCalculator {

    /** Impacts within this fraction of Tank.RADIUS of a tank's center are a "bullseye". */
    private const val BULLSEYE_FRACTION = 0.2f

    /** Damage fraction of [Weapon.maxDamage] right at the outer edge of a hit's reach. */
    private const val MIN_DAMAGE_FRACTION = 0.05f

    /**
     * Damage a blast at ([impactX], [impactY]) deals to [tank], measured from the tank's
     * *center* but accounting for its size: the tank's own [Tank.RADIUS] extends the
     * blast's effective reach past the impact point itself, exactly like a blast whose
     * edge only needs to reach a solid object's surface, not its center.
     *
     * Tiers by distance `d` from the impact to the tank's center:
     *  - `d <= Tank.RADIUS * BULLSEYE_FRACTION` (a "bullseye", the tank's central 20%):
     *    returns `null` - an instant kill regardless of the tank's current health.
     *  - `d <= Tank.RADIUS` (anywhere else on the tank's body): full [Weapon.maxDamage].
     *  - `d <= Tank.RADIUS + weapon.blastRadius` (the blast's edge still reaches the
     *    tank's surface): damage falls off linearly from 100% (at the tank's edge) to
     *    [MIN_DAMAGE_FRACTION] (right at the outer limit of the blast's reach).
     *  - Beyond that: 0, out of reach entirely.
     */
    fun computeDamage(weapon: Weapon, impactX: Float, impactY: Float, tank: Tank): Int? {
        val distance = hypot((tank.x - impactX).toDouble(), (tank.y - impactY).toDouble()).toFloat()
        val bullseyeRadius = Tank.RADIUS * BULLSEYE_FRACTION
        if (distance <= bullseyeRadius) return null

        val maxReach = Tank.RADIUS + weapon.blastRadius
        if (distance > maxReach) return 0
        if (distance <= Tank.RADIUS) return weapon.maxDamage

        val t = (distance - Tank.RADIUS) / weapon.blastRadius
        val fraction = 1f - t * (1f - MIN_DAMAGE_FRACTION)
        return (weapon.maxDamage * fraction).roundToInt()
    }

    /** A flat amount of damage expressed as a fraction of [Tank.MAX_HEALTH] rather than
     * derived from a [Weapon] - used by GameEngine's per-round floor mechanics (Lava's touch/
     * proximity burn), which deal damage relative to a tank's own max health regardless of
     * distance from any particular impact point. */
    fun percentOfMaxHealth(fraction: Float): Int = (Tank.MAX_HEALTH * fraction).roundToInt()
}
