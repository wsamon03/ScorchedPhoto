package com.scorchedphoto.app.tournament

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class MultiGameSetupViewModel @Inject constructor(
    private val tournamentRepository: TournamentRepository,
) : ViewModel() {

    private val _selectedType = MutableStateFlow(MultiGameType.KNOCKOUT)
    val selectedType: StateFlow<MultiGameType> = _selectedType.asStateFlow()

    private val _pointsToWin = MutableStateFlow(TournamentConfig.DEFAULT_POINTS_TO_WIN)
    val pointsToWin: StateFlow<Int> = _pointsToWin.asStateFlow()

    fun selectType(type: MultiGameType) {
        _selectedType.value = type
    }

    fun setPointsToWin(points: Int) {
        _pointsToWin.value = points.coerceAtLeast(TournamentConfig.MIN_POINTS_TO_WIN)
    }

    /** Writes the chosen mode into [TournamentRepository.config], read by
     * [com.scorchedphoto.app.setup.GameSetupViewModel.commitAndStart] once the roster/wall/
     * ceiling/floor are configured next, on the existing [com.scorchedphoto.app.setup.GameSetupScreen]. */
    fun commit() {
        tournamentRepository.config = TournamentConfig(_selectedType.value, _pointsToWin.value)
    }
}
