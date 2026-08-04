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
    // Seconds since this projectile was fired, incremented every stepProjectile() call - see
    // GameEngine.PROJECTILE_FUSE_SECONDS, the match-wide guarantee that every shot eventually
    // resolves however many times it's bounced, wrapped, or otherwise kept flying.
    var elapsedSeconds: Float = 0f,
)
