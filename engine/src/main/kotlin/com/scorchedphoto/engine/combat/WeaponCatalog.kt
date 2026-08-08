package com.scorchedphoto.engine.combat

import com.scorchedphoto.engine.tanks.Tank

object WeaponCatalog {

    val STANDARD_SHELL = Weapon(
        type = WeaponType.STANDARD_SHELL,
        displayName = "Standard Shell",
        blastRadius = 28f,
        maxDamage = 35,
        ammoLimit = 9,
        price = 10,
    )

    val BIG_BERTHA = Weapon(
        type = WeaponType.BIG_BERTHA,
        displayName = "Big Bertha",
        blastRadius = 55f,
        maxDamage = 60,
        ammoLimit = 9,
        price = 80,
    )

    val CLUSTER_MIRV = Weapon(
        type = WeaponType.CLUSTER_MIRV,
        displayName = "Cluster MIRV",
        blastRadius = 26f,
        maxDamage = 30,
        ammoLimit = 9,
        price = 90,
        childCount = 4,
        childSpreadDegrees = 40f,
        splitPattern = SplitPattern.RADIAL_FAN,
    )

    val BABY_MISSILE = Weapon(
        type = WeaponType.BABY_MISSILE,
        displayName = "Baby Missile",
        blastRadius = 14f,
        maxDamage = 15,
        ammoLimit = null,
    )

    /** Splits into 5 children in a straight horizontal line rather than a radial fan (see
     * [com.scorchedphoto.engine.GameEngine.splitHorizontalLine]) - 2 fall short, 1 continues
     * on the original path, 2 fly long, blanketing a wide swath of ground in one shot. */
    val SPREAD_MIRV = Weapon(
        type = WeaponType.SPREAD_MIRV,
        displayName = "Spread MIRV",
        blastRadius = 22f,
        maxDamage = 24,
        ammoLimit = 9,
        price = 85,
        childCount = 5,
        splitPattern = SplitPattern.HORIZONTAL_LINE,
        horizontalSpreadSpeed = 90f,
    )

    /** The ultimate weapon: a blast 4x Big Bertha's own radius, plus lingering radiation -
     * any tank it damages but doesn't outright kill takes another 10% of its max health at
     * the start of each of the next 3 rounds (see [com.scorchedphoto.engine.GameEngine.applyPendingDotDamage]). */
    val NUKE = Weapon(
        type = WeaponType.NUKE,
        displayName = "Nuke",
        blastRadius = BIG_BERTHA.blastRadius * 4f,
        maxDamage = 80,
        ammoLimit = 9,
        price = 220,
        dotFraction = 0.10f,
        dotRounds = 3,
    )

    /** A defensive/utility weapon: raises a mound of terrain instead of carving a crater (see
     * [com.scorchedphoto.engine.terrain.CraterCarver.fill]) - can bury an enemy tank or shore
     * up cover, but deals no direct damage. Not offensive, so [com.scorchedphoto.engine.ai.CpuWeaponSelector]
     * never picks it. */
    val EARTHMOVER = Weapon(
        type = WeaponType.EARTHMOVER,
        displayName = "Earthmover",
        blastRadius = 40f,
        maxDamage = 0,
        ammoLimit = 9,
        price = 25,
        terrainEffect = TerrainEffect.FILL,
    )

    /** A moderate blast that sets survivors burning - 10% of max health at the start of each
     * of the next 4 rounds, reusing the exact same lingering-damage mechanism as [NUKE], with
     * a real fire animation (see [Weapon.dotIsFire]/[com.scorchedphoto.engine.tanks.Tank.dotBurning])
     * for as long as it lingers. */
    val NAPALM = Weapon(
        type = WeaponType.NAPALM,
        displayName = "Napalm",
        blastRadius = 32f,
        maxDamage = 25,
        ammoLimit = 9,
        price = 45,
        dotFraction = 0.10f,
        dotRounds = 4,
        dotIsFire = true,
    )

    /** A precise, heavy single-target round: a small blast radius keeps collateral damage
     * low while still hitting harder than any other single-shot weapon in the roster. */
    val WIDOWMAKER = Weapon(
        type = WeaponType.WIDOWMAKER,
        displayName = "Widowmaker",
        blastRadius = 10f,
        maxDamage = 90,
        ammoLimit = 9,
        price = 110,
    )

    /** A wide, tight radial spray of many weak pellets (see [com.scorchedphoto.engine.GameEngine.splitMirv],
     * the same radial-fan geometry [CLUSTER_MIRV] uses, just more/weaker children over a
     * wider arc) - forgiving of imprecise aim, but no single pellet does much on its own. */
    val FLAK_BURST = Weapon(
        type = WeaponType.FLAK_BURST,
        displayName = "Flak Burst",
        blastRadius = 9f,
        maxDamage = 8,
        ammoLimit = 9,
        price = 60,
        childCount = 9,
        childSpreadDegrees = 70f,
        splitPattern = SplitPattern.RADIAL_FAN,
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

    val all: List<Weapon> = listOf(
        STANDARD_SHELL, BIG_BERTHA, CLUSTER_MIRV, BABY_MISSILE,
        SPREAD_MIRV, NUKE, EARTHMOVER, NAPALM, WIDOWMAKER, FLAK_BURST,
    )

    fun byType(type: WeaponType): Weapon = all.first { it.type == type }
}
