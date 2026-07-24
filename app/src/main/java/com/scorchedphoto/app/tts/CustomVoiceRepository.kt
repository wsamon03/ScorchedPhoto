package com.scorchedphoto.app.tts

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomVoiceRepository @Inject constructor(
    private val dao: CustomVoiceDao,
) {
    val customVoices: Flow<List<CustomVoice>> = dao.observeAll()

    suspend fun save(name: String, voiceId: String?, pitch: Float, speechRate: Float) {
        dao.insert(CustomVoice(name = name.trim(), voiceId = voiceId, pitch = pitch, speechRate = speechRate))
    }
}
