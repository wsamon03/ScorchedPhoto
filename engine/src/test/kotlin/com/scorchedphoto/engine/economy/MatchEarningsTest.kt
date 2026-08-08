package com.scorchedphoto.engine.economy

import com.scorchedphoto.engine.DeathRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class MatchEarningsTest {

    @Test
    fun `a tank that died with no kills or damage only earns the participation base`() {
        val result = MatchEarnings.compute(
            allOwnerIds = listOf(1),
            aliveOwnerIds = emptySet(),
            deathLog = listOf(DeathRecord(ownerId = 1, tankId = 1, turnNumber = 1, impactId = 0, killedByOwnerId = 2)),
            damageDealtByOwner = emptyMap(),
        )
        assertEquals(EconomyConstants.PARTICIPATION_BASE, result[1])
    }

    @Test
    fun `survival bonus is only awarded to owners still alive at match end`() {
        val result = MatchEarnings.compute(
            allOwnerIds = listOf(1, 2),
            aliveOwnerIds = setOf(1),
            deathLog = emptyList(),
            damageDealtByOwner = emptyMap(),
        )
        assertEquals(EconomyConstants.PARTICIPATION_BASE + EconomyConstants.SURVIVAL_BONUS, result[1])
        assertEquals(EconomyConstants.PARTICIPATION_BASE, result[2])
    }

    @Test
    fun `kills are counted per killedByOwnerId, and a suicide never counts`() {
        val deathLog = listOf(
            DeathRecord(ownerId = 2, tankId = 2, turnNumber = 1, impactId = 0, killedByOwnerId = 1),
            DeathRecord(ownerId = 3, tankId = 3, turnNumber = 2, impactId = 1, killedByOwnerId = 1),
            // A self-inflicted death (e.g. a stray shot, fall damage credited back to the same
            // owner) - killedByOwnerId equals the dying tank's own ownerId, so it must not count.
            DeathRecord(ownerId = 4, tankId = 4, turnNumber = 3, impactId = 2, killedByOwnerId = 4),
        )
        val result = MatchEarnings.compute(
            allOwnerIds = listOf(1, 4),
            aliveOwnerIds = setOf(1),
            deathLog = deathLog,
            damageDealtByOwner = emptyMap(),
        )
        assertEquals(EconomyConstants.PARTICIPATION_BASE + EconomyConstants.SURVIVAL_BONUS + EconomyConstants.KILL_BONUS * 2, result[1])
        // Owner 4 killed themselves - no kill bonus for it, and they're not in aliveOwnerIds.
        assertEquals(EconomyConstants.PARTICIPATION_BASE, result[4])
    }

    @Test
    fun `damage dealt is scaled and rounded down to a whole currency amount`() {
        val result = MatchEarnings.compute(
            allOwnerIds = listOf(1),
            aliveOwnerIds = emptySet(),
            deathLog = emptyList(),
            damageDealtByOwner = mapOf(1 to 37), // 37 * 0.4 = 14.8 -> floors to 14
        )
        assertEquals(EconomyConstants.PARTICIPATION_BASE + 14, result[1])
    }

    @Test
    fun `an owner absent from allOwnerIds never appears in the result, even if credited a kill`() {
        val deathLog = listOf(DeathRecord(ownerId = 2, tankId = 2, turnNumber = 1, impactId = 0, killedByOwnerId = 99))
        val result = MatchEarnings.compute(
            allOwnerIds = listOf(1),
            aliveOwnerIds = setOf(1),
            deathLog = deathLog,
            damageDealtByOwner = mapOf(99 to 500),
        )
        assertEquals(setOf(1), result.keys)
    }
}
