package com.scorchedphoto.engine.combat

import com.scorchedphoto.engine.tanks.Tank

object WeaponCatalog {

    val STANDARD_SHELL = Weapon(
        type = WeaponType.STANDARD_SHELL,
        displayName = "Standard Shell",
        blastRadius = 28f,
        maxDamage = 35,
        ammoLimit = null,
    )

    val BIG_BERTHA = Weapon(
        type = WeaponType.BIG_BERTHA,
        displayName = "Big Bertha",
        blastRadius = 55f,
        maxDamage = 60,
        ammoLimit = 3,
    )

    val MIRV = Weapon(
        type = WeaponType.MIRV,
        displayName = "MIRV",
        blastRadius = 26f,
        maxDamage = 30,
        ammoLimit = 2,
        childCount = 4,
        childSpreadDegrees = 40f,
    )

    val BABY_MISSILE = Weapon(
        type = WeaponType.BABY_MISSILE,
        displayName = "Baby Missile",
        blastRadius = 14f,
        maxDamage = 15,
        ammoLimit = null,
    )

    /** A dying tank's own final blast - see [com.scorchedphoto.engine.GameEngine]'s death
     * sequence - reusing the same crater-carve/damage machinery a real weapon impact uses
     * so it affects the ground and nearby tanks the same way. Deliberately excluded from
     * [all]/[byType]: it's never fired, selected, or ammo-tracked. */
    val TANK_DEATH_EXPLOSION = Weapon(
        type = WeaponType.TANK_EXPLOSION,
        displayName = "Tank Explosion",
        blastRadius = Tank.RADIUS * 3f,
        maxDamage = 50,
    )

    val all: List<Weapon> = listOf(STANDARD_SHELL, BIG_BERTHA, MIRV, BABY_MISSILE)

    fun byType(type: WeaponType): Weapon = all.first { it.type == type }
}
