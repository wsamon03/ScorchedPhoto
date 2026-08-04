package com.scorchedphoto.app.result

import androidx.lifecycle.ViewModel
import com.scorchedphoto.app.tournament.TournamentConfig
import com.scorchedphoto.app.tournament.TournamentRepository
import com.scorchedphoto.app.tournament.TournamentTankState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class VictoryViewModel @Inject constructor(
    private val matchResultRepository: MatchResultRepository,
    private val tournamentRepository: TournamentRepository,
) : ViewModel() {
    val winners: List<MatchWinner> get() = matchResultRepository.winners

    /** Non-null while a tournament is active - drives [VictoryScreen]'s standings/Next-Game
     * rendering instead of the ordinary Single Game Rematch/New Photo/Home one. */
    val tournamentConfig: TournamentConfig? get() = tournamentRepository.config
    val tournamentTanks: List<TournamentTankState> get() = tournamentRepository.state?.tanks.orEmpty()

    /** The overall tournament victor, once [com.scorchedphoto.app.tournament.TournamentScorer]
     * has decided one - `null` while the tournament is still ongoing. */
    val overallWinner: TournamentTankState?
        get() = tournamentRepository.overallWinnerOwnerId?.let { ownerId -> tournamentTanks.firstOrNull { it.index == ownerId } }

    /** Clears any tournament state - called whenever the player leaves back to the title
     * screen from here, whether quitting an in-progress tournament or leaving after one just
     * finished, so it can never bleed into a later Single Game or a new tournament. A no-op
     * when no tournament was active. */
    fun onHome() {
        tournamentRepository.clear()
    }
}
