package com.scorchedphoto.engine.economy

import com.scorchedphoto.engine.DeathRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class MatchEarningsTest {

    @Test
    fun `a tank alone in the roster with no kills or damage only earns the participation base`() {
        val result = MatchEarnings.compute(
            allOwnerIds = listOf(1),
            aliveOwnerIds = emptySet(),
            deathLog = listOf(DeathRecord(ownerId = 1, tankId = 1, turnNumber = 1, impactId = 0, killedByOwnerId = 2)),
            damageDealtByOwner = emptyMap(),
        )
        assertEquals(EconomyConstants.PARTICIPATION_BASE, result[1])
    }

    @Test
    fun `outsurviving earns 30 per tank that died earlier, and the sole survivor also earns the last-survivor bonus`() {
        // Owner 1 dies turn 1, owner 2 dies turn 3, owner 3 survives to the end.
        val deathLog = listOf(
            DeathRecord(ownerId = 1, tankId = 1, turnNumber = 1, impactId = 0, killedByOwnerId = null),
            DeathRecord(ownerId = 2, tankId = 2, turnNumber = 3, impactId = 1, killedByOwnerId = null),
        )
        val result = MatchEarnings.compute(
            allOwnerIds = listOf(1, 2, 3),
            aliveOwnerIds = setOf(3),
            deathLog = deathLog,
            damageDealtByOwner = emptyMap(),
        )
        // Owner 3 outsurvived both 1 and 2, and is the sole survivor.
        assertEquals(
            EconomyConstants.PARTICIPATION_BASE + EconomyConstants.OUTSURVIVE_BONUS * 2 + EconomyConstants.LAST_SURVIVOR_BONUS,
            result[3],
        )
        // Owner 2 outsurvived only 1 (died earlier) - not 3, which outlived owner 2 by surviving.
        assertEquals(EconomyConstants.PARTICIPATION_BASE + EconomyConstants.OUTSURVIVE_BONUS, result[2])
        // Owner 1 died first - outsurvived nobody.
        assertEquals(EconomyConstants.PARTICIPATION_BASE, result[1])
    }

    @Test
    fun `a simultaneous (tied) death earns no outsurvive credit between the two tanks that died together`() {
        // Owners 1 and 2 die on the same turn - a tie between them. Owner 3 survives and
        // outsurvives both.
        val deathLog = listOf(
            DeathRecord(ownerId = 1, tankId = 1, turnNumber = 2, impactId = 0, killedByOwnerId = null),
            DeathRecord(ownerId = 2, tankId = 2, turnNumber = 2, impactId = 1, killedByOwnerId = null),
        )
        val result = MatchEarnings.compute(
            allOwnerIds = listOf(1, 2, 3),
            aliveOwnerIds = setOf(3),
            deathLog = deathLog,
            damageDealtByOwner = emptyMap(),
        )
        assertEquals(EconomyConstants.PARTICIPATION_BASE, result[1])
        assertEquals(EconomyConstants.PARTICIPATION_BASE, result[2])
        assertEquals(
            EconomyConstants.PARTICIPATION_BASE + EconomyConstants.OUTSURVIVE_BONUS * 2 + EconomyConstants.LAST_SURVIVOR_BONUS,
            result[3],
        )
    }

    @Test
    fun `a multi-survivor tie for the win earns outsurvive credit against the eliminated but never the last-survivor bonus`() {
        // Owner 1 dies; owners 2 and 3 both survive to the end - a tie for the win.
        val deathLog = listOf(DeathRecord(ownerId = 1, tankId = 1, turnNumber = 1, impactId = 0, killedByOwnerId = null))
        val result = MatchEarnings.compute(
            allOwnerIds = listOf(1, 2, 3),
            aliveOwnerIds = setOf(2, 3),
            deathLog = deathLog,
            damageDealtByOwner = emptyMap(),
        )
        // Both survivors outsurvived owner 1, but neither outsurvived the other (both alive at
        // the end - a tie, not an outsurvive) and neither is the *sole* survivor.
        assertEquals(EconomyConstants.PARTICIPATION_BASE + EconomyConstants.OUTSURVIVE_BONUS, result[2])
        assertEquals(EconomyConstants.PARTICIPATION_BASE + EconomyConstants.OUTSURVIVE_BONUS, result[3])
        assertEquals(EconomyConstants.PARTICIPATION_BASE, result[1])
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
        // Owner 1 (the only other roster member) outsurvived owner 4 and is the sole survivor,
        // on top of 2 credited kills (owners 2/3, neither of whom are in the roster at all).
        assertEquals(
            EconomyConstants.PARTICIPATION_BASE + EconomyConstants.KILL_BONUS * 2 +
                EconomyConstants.OUTSURVIVE_BONUS + EconomyConstants.LAST_SURVIVOR_BONUS,
            result[1],
        )
        // Owner 4 killed themselves - no kill bonus for it, and they didn't outsurvive owner 1.
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
