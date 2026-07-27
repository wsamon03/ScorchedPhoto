package com.scorchedphoto.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val audioSettingsRepository: AudioSettingsRepository,
) : ViewModel() {

    val audioSettings: StateFlow<AudioSettings> = audioSettingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AudioSettings())

    fun setMusicVolume(volume: Float) {
        viewModelScope.launch { audioSettingsRepository.setMusicVolume(volume) }
    }

    fun setSfxVolume(volume: Float) {
        viewModelScope.launch { audioSettingsRepository.setSfxVolume(volume) }
    }

    fun setVoiceVolume(volume: Float) {
        viewModelScope.launch { audioSettingsRepository.setVoiceVolume(volume) }
    }
}
