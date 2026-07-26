package com.scorchedphoto.engine.combat

enum class WeaponType {
    STANDARD_SHELL,
    BIG_BERTHA,
    MIRV,
    BABY_MISSILE,

    /** Not player-selectable - see [com.scorchedphoto.engine.combat.WeaponCatalog.TANK_DEATH_EXPLOSION]. */
    TANK_EXPLOSION,
}

data class Weapon(
    val type: WeaponType,
    val displayName: String,
    val blastRadius: Float,
    val maxDamage: Int,
    val ammoLimit: Int? = null,
    val childCount: Int = 1,
    val childSpreadDegrees: Float = 0f,
)
