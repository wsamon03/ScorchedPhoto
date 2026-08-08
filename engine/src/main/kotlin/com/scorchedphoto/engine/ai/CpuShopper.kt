package com.scorchedphoto.engine.ai

import com.scorchedphoto.engine.combat.Weapon
import com.scorchedphoto.engine.combat.WeaponCatalog
import com.scorchedphoto.engine.combat.WeaponType
import com.scorchedphoto.engine.tanks.Tank

/**
 * Chooses a CPU tank's shop purchases for one visit (before the first match, or between
 * tournament rounds - see :app's `shop` package) given [budget] currency to spend. Mirrors
 * [CpuWeaponSelector]'s own exclusion of purely defensive/unlimited weapons - a CPU never
 * spends currency on Earthmover (deals no damage) or Baby Missile (already free/unlimited,
 * never gated by the economy at all) - so it never wastes currency on something its own
 * in-match firing AI would never pick anyway. Spends greedily and exhaustively: on each pass,
 * buys one more unit of whichever affordable, not-yet-capped weapon currently has the best
 * damage-per-price ratio, repeating until nothing more can be bought - so a CPU always spends
 * as much of [budget] as the catalog's prices/caps allow, with no saving-up behavior.
 */
object CpuShopper {
    fun chooseLoadout(budget: Int, catalog: List<Weapon> = WeaponCatalog.all): Map<WeaponType, Int> {
        val candidates = catalog.filter { it.maxDamage > 0 && it.ammoLimit != null && it.price > 0 }
        val counts = mutableMapOf<WeaponType, Int>()
        var remaining = budget
        while (true) {
            val best = candidates
                .filter { weapon -> weapon.price <= remaining && (counts[weapon.type] ?: 0) < weapon.ammoLimit!! }
                .maxByOrNull { effectivePower(it) / it.price }
                ?: break
            counts[best.type] = (counts[best.type] ?: 0) + 1
            remaining -= best.price
        }
        return counts
    }

    /** A weapon's total offensive value per purchase - direct hit across every child (see
     * [Weapon.childCount]) plus any lingering damage-over-time - mirroring
     * [CpuWeaponSelector]'s own `effectivePower`, extended to account for multi-warhead
     * weapons since a purchase decision (unlike a single in-match shot) has no target to weigh
     * splash coverage against. */
    private fun effectivePower(weapon: Weapon): Float =
        weapon.maxDamage * weapon.childCount + weapon.dotFraction * weapon.dotRounds * Tank.MAX_HEALTH
}
