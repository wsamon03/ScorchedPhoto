package com.scorchedphoto.engine.physics

import com.scorchedphoto.engine.combat.Weapon

data class Projectile(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val weapon: Weapon,
    val ownerTankId: Int,
    var hasPassedApex: Boolean = false,
)
