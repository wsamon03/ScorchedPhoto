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
) {
    companion object {
        const val MAX_HEALTH = 100
    }
}
