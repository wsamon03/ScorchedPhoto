package com.scorchedphoto.app.shop

import com.scorchedphoto.engine.combat.WeaponType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the in-progress game/tournament's per-player currency and this round's confirmed
 * purchases between the screens that need it - [com.scorchedphoto.app.setup.GameSetupViewModel]
 * (seeds starting [balances] on the first match of a fresh game/tournament),
 * [com.scorchedphoto.app.shop.ShopViewModel] (reads/spends a balance, writes [purchasedAmmo]),
 * and [com.scorchedphoto.app.game.GameViewModel] (reads [purchasedAmmo] to seed
 * [com.scorchedphoto.engine.GameEngine]'s starting ammo, credits match earnings back into
 * [balances]). Mirrors [com.scorchedphoto.app.tournament.TournamentRepository]'s own plain
 * in-memory singleton pattern. Both maps are keyed by a tank's stable `ownerId` (the tournament
 * roster index - see `TournamentTankState`'s own doc), not by any per-round `Tank` object,
 * since a fresh `Tank` is built every round but this identity persists across them.
 *
 * [purchasedAmmo] deliberately does *not* get cleared once [com.scorchedphoto.app.game.GameViewModel]
 * reads it each match - an ordinary single-game Rematch never revisits the shop, so it needs to
 * keep replaying with whatever was already bought rather than silently reverting to no ammo at
 * all. [balances] persists and accumulates across rounds; only [clear] (a brand-new game or
 * tournament) resets either map.
 */
@Singleton
class EconomyRepository @Inject constructor() {
    var balances: MutableMap<Int, Int> = mutableMapOf()
    var purchasedAmmo: MutableMap<Int, MutableMap<WeaponType, Int>> = mutableMapOf()

    fun clear() {
        balances = mutableMapOf()
        purchasedAmmo = mutableMapOf()
    }
}
