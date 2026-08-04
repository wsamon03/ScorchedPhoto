package com.scorchedphoto.app.tournament

import javax.inject.Inject
import javax.inject.Singleton

/** Holds the in-progress tournament's config/state between the screens that need it -
 * [com.scorchedphoto.app.setup.GameSetupScreen] (seeds [state] on the first game),
 * [com.scorchedphoto.app.game.GameViewModel] (filters the roster for Knockout/Survivor,
 * scores each game via [TournamentScorer]), and [com.scorchedphoto.app.result.VictoryScreen]
 * (shows running standings or the final result). Mirrors
 * [com.scorchedphoto.app.setup.MatchConfigRepository]'s own plain in-memory singleton pattern.
 * [config] is `null` for an ordinary Single Game - the same nullability is used throughout the
 * tournament/game/result packages to branch tournament-specific behavior on or off. */
@Singleton
class TournamentRepository @Inject constructor() {
    var config: TournamentConfig? = null
    var state: TournamentState? = null

    /** Set by [com.scorchedphoto.app.game.GameViewModel] once a game's [TournamentScorer] call
     * determines an overall winner - `null` while the tournament is still ongoing. Read by
     * [com.scorchedphoto.app.result.VictoryViewModel] to decide between "this game's result,
     * play on" and "tournament complete" once [VictoryScreen][com.scorchedphoto.app.result.VictoryScreen]
     * is shown. */
    var overallWinnerOwnerId: Int? = null

    /** Clears every field above - called both when starting an ordinary Single Game (so no
     * stale tournament from an earlier session bleeds in) and once a tournament's own final
     * result screen is dismissed. */
    fun clear() {
        config = null
        state = null
        overallWinnerOwnerId = null
    }
}
