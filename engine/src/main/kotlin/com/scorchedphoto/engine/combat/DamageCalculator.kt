package com.scorchedphoto.engine.combat

import com.scorchedphoto.engine.tanks.Tank
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

object DamageCalculator {

    /** Linear falloff from [weapon].maxDamage at the blast center to 0 just past blastRadius. */
    fun computeDamage(weapon: Weapon, blastX: Float, blastY: Float, tank: Tank): Int {
        val distance = hypot((tank.x - blastX).toDouble(), (tank.y - blastY).toDouble()).toFloat()
        if (distance > weapon.blastRadius) return 0
        val falloff = 1f - (distance / weapon.blastRadius)
        val raw = (weapon.maxDamage * falloff).roundToInt()
        return max(raw, weapon.minDamageIfInRadius)
    }
}
