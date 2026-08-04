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
    // Set once this projectile has bounced off the map's true bottom under a
    // PADDED/RUBBER/SPRING/REFLECTIVE floor - see GameEngine.handleFloorEdge's own doc on why
    // this matters specifically for Spring/Reflective (retention >= 1f, so gravity alone can
    // never bring the bounce to rest).
    var hasBouncedOffFloor: Boolean = false,
)
