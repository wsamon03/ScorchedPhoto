package com.scorchedphoto.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scorchedphoto.engine.EdgeType
import com.scorchedphoto.engine.FloorType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val audioSettingsRepository: AudioSettingsRepository,
    private val matchDefaultsRepository: MatchDefaultsRepository,
) : ViewModel() {

    val audioSettings: StateFlow<AudioSettings> = audioSettingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AudioSettings())

    /** Persisted starting values for a new match - see [MatchDefaults]'s own doc. Seeded with
     * the same hardcoded fallback [MatchDefaultsRepository.defaults] itself falls back to, so
     * this screen never flashes some other placeholder before the real persisted row loads. */
    val matchDefaults: StateFlow<ResolvedMatchDefaults> = matchDefaultsRepository.defaults
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            ResolvedMatchDefaults(playerCount = 2, wallType = EdgeType.NONE, ceilingType = EdgeType.NONE, floorType = FloorType.GROUND),
        )

    fun setMusicVolume(volume: Float) {
        viewModelScope.launch { audioSettingsRepository.setMusicVolume(volume) }
    }

    fun setSfxVolume(volume: Float) {
        viewModelScope.launch { audioSettingsRepository.setSfxVolume(volume) }
    }

    fun setVoiceVolume(volume: Float) {
        viewModelScope.launch { audioSettingsRepository.setVoiceVolume(volume) }
    }

    fun setDefaultPlayerCount(count: Int) {
        viewModelScope.launch { matchDefaultsRepository.setPlayerCount(count) }
    }

    fun setDefaultWallType(type: EdgeType?) {
        viewModelScope.launch { matchDefaultsRepository.setWallType(type) }
    }

    fun setDefaultCeilingType(type: EdgeType?) {
        viewModelScope.launch { matchDefaultsRepository.setCeilingType(type) }
    }

    fun setDefaultFloorType(type: FloorType?) {
        viewModelScope.launch { matchDefaultsRepository.setFloorType(type) }
    }
}
