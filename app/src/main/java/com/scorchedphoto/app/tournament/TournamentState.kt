package com.scorchedphoto.app.tournament

/** One tank's running tournament record - [index] is its position in the tournament's
 * original, full roster (the same list [com.scorchedphoto.app.setup.GameSetupViewModel]
 * committed at the start of the tournament), which doubles as its stable
 * [com.scorchedphoto.engine.tanks.Tank.ownerId] for every game played within this
 * tournament, whether or not it's still in the running (see [com.scorchedphoto.app.game.GameViewModel]'s
 * own roster-filtering for Knockout/Survivor). */
data class TournamentTankState(
    val index: Int,
    val name: String,
    val color: Int,
    var points: Int = 0,
    var knockedOut: Boolean = false,
    var hasImmunity: Boolean = false,
)

/** A tournament's live, running state - built once (see [com.scorchedphoto.app.setup.GameSetupViewModel.commitAndStart])
 * when the tournament's first game is configured, then mutated in place after every
 * subsequent game by [TournamentScorer]. */
class TournamentState(
    val config: TournamentConfig,
    val tanks: MutableList<TournamentTankState>,
)
