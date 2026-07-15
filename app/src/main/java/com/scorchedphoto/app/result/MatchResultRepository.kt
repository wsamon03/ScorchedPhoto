package com.scorchedphoto.app.result

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the winner's display info between the Game screen (whose GameViewModel/GameEngine
 * are destroyed on navigation) and the Victory screen.
 */
@Singleton
class MatchResultRepository @Inject constructor() {
    var winnerName: String? = null
    var winnerColor: Int? = null
}
