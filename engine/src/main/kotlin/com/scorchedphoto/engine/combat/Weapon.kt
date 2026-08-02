package com.scorchedphoto.engine.combat

enum class WeaponType {
    STANDARD_SHELL,
    BIG_BERTHA,
    CLUSTER_MIRV,
    BABY_MISSILE,
    SPREAD_MIRV,
    NUKE,
    EARTHMOVER,
    NAPALM,
    WIDOWMAKER,
    FLAK_BURST,

    /** Not player-selectable - see [com.scorchedphoto.engine.combat.WeaponCatalog.TANK_DEATH_EXPLOSION]. */
    TANK_EXPLOSION,
}

/** How a weapon's children (see [Weapon.childCount]) fan out from the parent projectile at
 * apex - see [com.scorchedphoto.engine.GameEngine.splitMirv]/`splitHorizontalLine`. [NONE] is
 * the default for every non-splitting weapon ([Weapon.childCount] == 1). */
enum class SplitPattern { NONE, RADIAL_FAN, HORIZONTAL_LINE }

/** Whether an impact carves a crater (the default, for every ordinary weapon) or raises a
 * mound of terrain instead (Earthmover) - see [com.scorchedphoto.engine.terrain.CraterCarver]. */
enum class TerrainEffect { CARVE, FILL }

data class Weapon(
    val type: WeaponType,
    val displayName: String,
    val blastRadius: Float,
    val maxDamage: Int,
    val ammoLimit: Int? = null,
    val childCount: Int = 1,
    // RADIAL_FAN only - the full arc children fan out across, centered on the parent's own
    // current heading at the moment of split (see splitMirv).
    val childSpreadDegrees: Float = 0f,
    val splitPattern: SplitPattern = SplitPattern.NONE,
    // HORIZONTAL_LINE only - the fixed per-step horizontal speed offset each child gets from
    // the parent's own vx (see splitHorizontalLine).
    val horizontalSpreadSpeed: Float = 0f,
    val terrainEffect: TerrainEffect = TerrainEffect.CARVE,
    // 0 = no damage-over-time. Otherwise, any tank this weapon damages (and doesn't kill
    // outright) takes dotFraction of its max health at the start of each of the next
    // dotRounds round boundaries - see GameEngine.applyPendingDotDamage.
    val dotFraction: Float = 0f,
    val dotRounds: Int = 0,
)
