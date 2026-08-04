package com.scorchedphoto.app.tournament

import com.scorchedphoto.engine.DeathRecord

/**
 * Applies one just-finished game's result to a [TournamentState], mutating its tanks' points/
 * knockout/immunity in place per [state]'s own [MultiGameType], and returns the overall
 * tournament winner's [TournamentTankState.index] once one is decided (`null` while the
 * tournament continues). [deathLog] is that game's [com.scorchedphoto.engine.GameEngine.deathLog];
 * [winnerOwnerIds] its [com.scorchedphoto.engine.turns.WinResult.winningOwnerIds] (empty or more
 * than one entry means a tie - no tank actually "won" that game).
 */
object TournamentScorer {

    fun score(state: TournamentState, deathLog: List<DeathRecord>, winnerOwnerIds: List<Int>): Int? =
        when (state.config.type) {
            MultiGameType.KNOCKOUT -> scoreKnockout(state, deathLog)
            MultiGameType.SURVIVOR -> scoreSurvivor(state, deathLog, winnerOwnerIds)
            MultiGameType.LAST_MAN_STANDING -> scoreLastManStanding(state, winnerOwnerIds)
            MultiGameType.KILL_COUNT -> scoreKillCount(state, deathLog)
            MultiGameType.STANDING -> scoreStanding(state, deathLog, winnerOwnerIds)
        }

    /** The single owner who died on the lowest [DeathRecord.turnNumber] this game, or `null`
     * if nobody died (shouldn't happen in a completed match) or two-or-more owners tied for
     * that minimum turn - a tie for first-to-die knocks nobody out, per Knockout/Survivor's own
     * rule. */
    private fun firstToDieAlone(deathLog: List<DeathRecord>): Int? {
        val minTurn = deathLog.minOfOrNull { it.turnNumber } ?: return null
        val tiedForFirst = deathLog.filter { it.turnNumber == minTurn }.map { it.ownerId }.distinct()
        return tiedForFirst.singleOrNull()
    }

    private fun tankFor(state: TournamentState, ownerId: Int): TournamentTankState? =
        state.tanks.firstOrNull { it.index == ownerId }

    /** Once every tank but one has been knocked out, that one tank is the overall winner - see
     * [scoreKnockout]/[scoreSurvivor], the only two modes that ever set [TournamentTankState.knockedOut]. */
    private fun overallWinnerIfOneTankRemains(state: TournamentState): Int? =
        state.tanks.filter { !it.knockedOut }.singleOrNull()?.index

    /** First tank (in roster order - an arbitrary but deterministic tie-break for the rare case
     * more than one tank crosses the threshold in the same game) whose points reach
     * [TournamentConfig.pointsToWin] - see Last Man Standing/Kill Count/Standing, the only three
     * modes with a points target at all. */
    private fun firstToReachPointsToWin(state: TournamentState): Int? =
        state.tanks.firstOrNull { it.points >= state.config.pointsToWin }?.index

    private fun scoreKnockout(state: TournamentState, deathLog: List<DeathRecord>): Int? {
        firstToDieAlone(deathLog)?.let { ownerId -> tankFor(state, ownerId)?.knockedOut = true }
        return overallWinnerIfOneTankRemains(state)
    }

    private fun scoreSurvivor(state: TournamentState, deathLog: List<DeathRecord>, winnerOwnerIds: List<Int>): Int? {
        firstToDieAlone(deathLog)?.let { ownerId ->
            val candidate = tankFor(state, ownerId)
            if (candidate != null && !candidate.hasImmunity) {
                candidate.knockedOut = true
            }
        }
        // Immunity lasts exactly one game, used or not - clear it before granting this game's
        // own winner a fresh one. A tie (no single winner) grants nobody immunity.
        state.tanks.forEach { it.hasImmunity = false }
        winnerOwnerIds.singleOrNull()?.let { ownerId -> tankFor(state, ownerId)?.hasImmunity = true }
        return overallWinnerIfOneTankRemains(state)
    }

    private fun scoreLastManStanding(state: TournamentState, winnerOwnerIds: List<Int>): Int? {
        winnerOwnerIds.singleOrNull()?.let { ownerId -> tankFor(state, ownerId)?.let { it.points++ } }
        return firstToReachPointsToWin(state)
    }

    /** Every death costs its own tank a point, always. A live blast or DoT tick's kill credit
     * (the [DeathRecord.impactId]-grouped tanks it killed) goes to [DeathRecord.killedByOwnerId]
     * *unless* that same owner is among the tanks that blast killed (a self-kill), which voids
     * credit for everyone else killed by that same shot too - see [MultiGameType.KILL_COUNT]'s
     * own doc. A death with no `impactId` (fall/lava/drowning/fall-through - see [DeathRecord]'s
     * own doc) has no shot to share credit with another death, so it's scored as its own
     * singleton group instead of being grouped with anything else. */
    private fun scoreKillCount(state: TournamentState, deathLog: List<DeathRecord>): Int? {
        for (record in deathLog) {
            tankFor(state, record.ownerId)?.let { it.points -= 1 }
        }
        val (fromABlast, unattributedGrouping) = deathLog.partition { it.impactId != null }
        val groups = fromABlast.groupBy { it.impactId }.values + unattributedGrouping.map { listOf(it) }
        for (group in groups) {
            val killer = group.first().killedByOwnerId ?: continue
            val killedOwnerIds = group.map { it.ownerId }
            if (killer !in killedOwnerIds) {
                tankFor(state, killer)?.let { it.points += killedOwnerIds.size }
            }
        }
        return firstToReachPointsToWin(state)
    }

    /** Ranks every tank by death order this game - tied deaths (same [DeathRecord.turnNumber])
     * share the lowest rank among them - plus one trailing group for whoever's still alive at
     * match end ([winnerOwnerIds]; empty for a mutual-elimination tie, granting nobody a rank).
     * A group's rank is the total count of tanks in every strictly earlier group (0 for the
     * first group) - see [MultiGameType.STANDING]'s own doc and worked example. */
    private fun scoreStanding(state: TournamentState, deathLog: List<DeathRecord>, winnerOwnerIds: List<Int>): Int? {
        val deathOrderGroups = deathLog.groupBy { it.turnNumber }
            .toSortedMap()
            .values
            .map { group -> group.map { it.ownerId }.distinct() }
        val groups = if (winnerOwnerIds.isEmpty()) deathOrderGroups else deathOrderGroups + listOf(winnerOwnerIds)

        var rank = 0
        for (group in groups) {
            for (ownerId in group) {
                tankFor(state, ownerId)?.let { it.points += rank }
            }
            rank += group.size
        }
        return firstToReachPointsToWin(state)
    }
}
