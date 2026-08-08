package com.scorchedphoto.engine.economy

import com.scorchedphoto.engine.DeathRecord
import kotlin.math.floor

/**
 * Computes how much currency each tank's owner earns from a single completed match - see
 * [EconomyConstants] for the tunable formula constants this applies. Purely a function of the
 * match's own already-produced results ([com.scorchedphoto.engine.GameEngine.deathLog]/
 * [com.scorchedphoto.engine.GameEngine.damageDealtByOwner]/who's still alive), so it has no
 * dependency on [com.scorchedphoto.engine.GameEngine] itself and can be unit tested standalone.
 */
object MatchEarnings {
    /**
     * [allOwnerIds] is every owner who had a tank in this match's roster (including anyone
     * eliminated partway through) - every one of them gets at least [EconomyConstants.PARTICIPATION_BASE].
     * [aliveOwnerIds] is whichever of those owners' tanks were still alive once the match's
     * [com.scorchedphoto.engine.turns.WinResult] resolved. [deathLog] and [damageDealtByOwner]
     * are read straight from that same [com.scorchedphoto.engine.GameEngine] instance. Returns a
     * map with exactly one entry per id in [allOwnerIds], never more.
     */
    fun compute(
        allOwnerIds: Collection<Int>,
        aliveOwnerIds: Set<Int>,
        deathLog: List<DeathRecord>,
        damageDealtByOwner: Map<Int, Int>,
    ): Map<Int, Int> {
        // A suicide (killedByOwnerId == the dying tank's own ownerId) is excluded, so a player
        // can never farm kill bonus by finishing themselves off.
        val kills = deathLog
            .filter { it.killedByOwnerId != null && it.killedByOwnerId != it.ownerId }
            .groupingBy { it.killedByOwnerId!! }
            .eachCount()

        return allOwnerIds.associateWith { ownerId ->
            var earnings = EconomyConstants.PARTICIPATION_BASE
            if (ownerId in aliveOwnerIds) earnings += EconomyConstants.SURVIVAL_BONUS
            earnings += EconomyConstants.KILL_BONUS * (kills[ownerId] ?: 0)
            earnings += floor(EconomyConstants.DAMAGE_TO_CURRENCY_RATE * (damageDealtByOwner[ownerId] ?: 0)).toInt()
            earnings
        }
    }
}
