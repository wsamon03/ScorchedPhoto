package com.scorchedphoto.app.result

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
}
