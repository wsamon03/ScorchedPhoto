package com.scorchedphoto.engine.tanks

import com.scorchedphoto.engine.ai.Difficulty
import com.scorchedphoto.engine.combat.WeaponType

data class Tank(
    val id: Int,
    val ownerId: Int,
    val name: String,
    val color: Int,
    val isCpu: Boolean,
    var x: Float,
    var y: Float,
    var health: Int = MAX_HEALTH,
    var angleDeg: Float = 45f,
    var power: Float = 50f,
    var currentWeapon: WeaponType = WeaponType.STANDARD_SHELL,
    var alive: Boolean = true,
    var falling: Boolean = false,
    var fallVelocity: Float = 0f,
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val shape: TankShape = TankShape.CLASSIC,
) {
    companion object {
        const val MAX_HEALTH = 100

        /**
         * Half-width of the tank body / direct-hit collision radius, in the same
         * working-image pixel scale as [com.scorchedphoto.engine.physics.GRAVITY] etc.
         * Shared by rendering (`GameRenderer`) and hit detection (`GameEngine`) so the
         * two always agree on how big a tank actually is - was 14f, 4x'd to 56f, halved
         * back down to 28f.
         */
        const val RADIUS = 28f
    }
}
