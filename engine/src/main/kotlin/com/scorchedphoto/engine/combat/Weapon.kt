package com.scorchedphoto.engine.combat

enum class WeaponType {
    STANDARD_SHELL,
    BIG_BERTHA,
    MIRV,
    BABY_MISSILE,
}

data class Weapon(
    val type: WeaponType,
    val displayName: String,
    val blastRadius: Float,
    val maxDamage: Int,
    val minDamageIfInRadius: Int = 5,
    val ammoLimit: Int? = null,
    val childCount: Int = 1,
    val childSpreadDegrees: Float = 0f,
)
