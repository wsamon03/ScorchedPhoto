package com.scorchedphoto.app.tournament

/** The player's chosen tournament mode and (for modes where [MultiGameType.usesPointsToWin])
 * the point total needed to win it all - selected once on [MultiGameSetupScreen], before the
 * roster/wall/ceiling/floor are configured on the existing [com.scorchedphoto.app.setup.GameSetupScreen].
 * [pointsToWin] is ignored by Knockout/Survivor. */
data class TournamentConfig(
    val type: MultiGameType,
    val pointsToWin: Int = DEFAULT_POINTS_TO_WIN,
) {
    companion object {
        const val MIN_POINTS_TO_WIN = 1
        const val DEFAULT_POINTS_TO_WIN = 3
    }
}
