package com.scorchedphoto.app.result

import com.scorchedphoto.engine.DeathRecord
import javax.inject.Inject
import javax.inject.Singleton

data class MatchWinner(val name: String, val color: Int)

/**
 * Holds the winner(s)' display info between the Game screen (whose GameViewModel/GameEngine
 * are destroyed on navigation) and the Victory screen. More than one entry means a tie -
 * see [com.scorchedphoto.engine.turns.WinResult].
 */
@Singleton
class MatchResultRepository @Inject constructor() {
    var winners: List<MatchWinner> = emptyList()

    /** The just-finished match's full [com.scorchedphoto.engine.GameEngine.deathLog] and
     * [com.scorchedphoto.engine.turns.WinResult.winningOwnerIds] - populated by
     * [com.scorchedphoto.app.game.GameViewModel] alongside [winners], and consumed by
     * [com.scorchedphoto.app.tournament.TournamentScorer] once the match ends, when a
     * tournament is active. Unused for an ordinary Single Game. */
    var deathLog: List<DeathRecord> = emptyList()
    var winnerOwnerIds: List<Int> = emptyList()
}
