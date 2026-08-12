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

        // Null (absent) means "still alive when the match ended" - ranks later than any real
        // death turn, the same way DeathRecord.turnNumber already orders deaths within a match.
        val deathTurnByOwner: Map<Int, Int> = deathLog.associate { it.ownerId to it.turnNumber }

        // True if [a] left the match strictly later than [b] did - a real "outsurvive," not a
        // simultaneous ("tied") death, and not two tanks that both survived to the end (that's
        // a tie for the win, not either one outsurviving the other).
        fun outlived(a: Int, b: Int): Boolean {
            val turnA = deathTurnByOwner[a]
            val turnB = deathTurnByOwner[b]
            return when {
                turnA == null && turnB == null -> false
                turnA == null -> true
                turnB == null -> false
                else -> turnA > turnB
            }
        }

        val isSoleSurvivor = aliveOwnerIds.size == 1

        return allOwnerIds.associateWith { ownerId ->
            var earnings = EconomyConstants.PARTICIPATION_BASE
            earnings += EconomyConstants.KILL_BONUS * (kills[ownerId] ?: 0)
            earnings += floor(EconomyConstants.DAMAGE_TO_CURRENCY_RATE * (damageDealtByOwner[ownerId] ?: 0)).toInt()
            val outsurvivedCount = allOwnerIds.count { other -> other != ownerId && outlived(ownerId, other) }
            earnings += EconomyConstants.OUTSURVIVE_BONUS * outsurvivedCount
            if (isSoleSurvivor && ownerId in aliveOwnerIds) earnings += EconomyConstants.LAST_SURVIVOR_BONUS
            earnings
        }
    }
}
