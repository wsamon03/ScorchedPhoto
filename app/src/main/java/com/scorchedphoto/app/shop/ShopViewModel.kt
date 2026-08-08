package com.scorchedphoto.app.shop

import androidx.lifecycle.ViewModel
import com.scorchedphoto.app.setup.MatchConfigRepository
import com.scorchedphoto.app.tournament.TournamentRepository
import com.scorchedphoto.engine.ai.CpuShopper
import com.scorchedphoto.engine.combat.WeaponCatalog
import com.scorchedphoto.engine.combat.WeaponType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** One human-controlled tank [ShopScreen] still needs to shop for - a stable per-tournament
 * `ownerId` (see [EconomyRepository]'s own doc) paired with its display name. */
data class Shopper(val ownerId: Int, val name: String)

/**
 * Drives [ShopScreen] - the pre-first-match and between-tournament-rounds weapon shop. On
 * construction, immediately auto-purchases a loadout for every CPU-controlled tank in this
 * round's active roster (via [CpuShopper], no UI shown for them) and exposes [humanShoppers],
 * the human-controlled tanks left to shop for one at a time.
 *
 * Every mutation ([buy]/[sell]) writes straight into [EconomyRepository] - there's no separate
 * staged/pending state to confirm, since a shop transaction is already atomic per tap. [version]
 * exists purely so [ShopScreen] can observe those otherwise-plain-map mutations and recompose;
 * it carries no meaning of its own beyond "something changed."
 */
@HiltViewModel
class ShopViewModel @Inject constructor(
    matchConfigRepository: MatchConfigRepository,
    tournamentRepository: TournamentRepository,
    private val economyRepository: EconomyRepository,
) : ViewModel() {

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    val humanShoppers: List<Shopper>

    init {
        val matchConfig = requireNotNull(matchConfigRepository.matchConfig) {
            "ShopScreen reached with no match configured"
        }
        // Mirrors GameViewModel.init's own roster-filtering rule exactly - a no-op (every
        // index passes straight through) for an ordinary Single Game or a tournament's own
        // first game, where nothing is knocked out yet.
        val tournamentTanks = tournamentRepository.state?.tanks
        val activeConfigs = matchConfig.tankConfigs.withIndex()
            .filter { (index, _) -> tournamentTanks?.getOrNull(index)?.knockedOut != true }

        humanShoppers = activeConfigs.filter { !it.value.isCpu }.map { Shopper(it.index, it.value.name) }

        for ((ownerId, config) in activeConfigs) {
            if (!config.isCpu) continue
            val budget = economyRepository.balances[ownerId] ?: 0
            val loadout = CpuShopper.chooseLoadout(budget)
            val spent = loadout.entries.sumOf { (type, count) -> WeaponCatalog.byType(type).price * count }
            economyRepository.purchasedAmmo[ownerId] = loadout.toMutableMap()
            economyRepository.balances[ownerId] = budget - spent
        }
    }

    fun balanceFor(ownerId: Int): Int = economyRepository.balances[ownerId] ?: 0

    fun ownedFor(ownerId: Int, type: WeaponType): Int = economyRepository.purchasedAmmo[ownerId]?.get(type) ?: 0

    fun canBuy(ownerId: Int, type: WeaponType): Boolean {
        val weapon = WeaponCatalog.byType(type)
        val ammoLimit = weapon.ammoLimit ?: return false // Baby Missile - never purchasable
        return weapon.price in 1..balanceFor(ownerId) && ownedFor(ownerId, type) < ammoLimit
    }

    fun buy(ownerId: Int, type: WeaponType) {
        if (!canBuy(ownerId, type)) return
        val weapon = WeaponCatalog.byType(type)
        economyRepository.balances[ownerId] = balanceFor(ownerId) - weapon.price
        economyRepository.purchasedAmmo.getOrPut(ownerId) { mutableMapOf() }[type] = ownedFor(ownerId, type) + 1
        _version.value++
    }

    fun sell(ownerId: Int, type: WeaponType) {
        val owned = ownedFor(ownerId, type)
        if (owned <= 0) return
        val weapon = WeaponCatalog.byType(type)
        economyRepository.balances[ownerId] = balanceFor(ownerId) + weapon.price
        economyRepository.purchasedAmmo.getOrPut(ownerId) { mutableMapOf() }[type] = owned - 1
        _version.value++
    }
}
