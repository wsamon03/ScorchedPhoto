package com.scorchedphoto.app.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioSettingsRepository @Inject constructor(
    private val dao: AudioSettingsDao,
) {
    /** Falls back to all-defaults if the seed row is somehow missing - see
     * [SettingsDatabase]'s creation callback, which normally guarantees it exists. */
    val settings: Flow<AudioSettings> = dao.observe().map { it ?: AudioSettings() }

    suspend fun setMusicVolume(volume: Float) = update { it.copy(musicVolume = volume) }
    suspend fun setSfxVolume(volume: Float) = update { it.copy(sfxVolume = volume) }
    suspend fun setVoiceVolume(volume: Float) = update { it.copy(voiceVolume = volume) }

    private suspend fun update(transform: (AudioSettings) -> AudioSettings) {
        dao.upsert(transform(settings.first()))
    }
}
