package com.scorchedphoto.app.home

import androidx.lifecycle.ViewModel
import com.scorchedphoto.app.tournament.TournamentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val tournamentRepository: TournamentRepository,
) : ViewModel() {

    /** Called when starting an ordinary Single Game, so a tournament mode picked (or a whole
     * tournament played) on an earlier visit can never bleed into a plain single match - see
     * [TournamentRepository.config]'s own doc. */
    fun clearTournament() {
        tournamentRepository.clear()
    }
}
