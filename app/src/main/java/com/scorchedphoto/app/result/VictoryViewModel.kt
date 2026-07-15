package com.scorchedphoto.app.result

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class VictoryViewModel @Inject constructor(
    private val matchResultRepository: MatchResultRepository,
) : ViewModel() {
    val winnerName: String? get() = matchResultRepository.winnerName
    val winnerColor: Int? get() = matchResultRepository.winnerColor
}
