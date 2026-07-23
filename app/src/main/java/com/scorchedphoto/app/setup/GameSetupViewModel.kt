package com.scorchedphoto.app.setup

import androidx.lifecycle.ViewModel
import com.scorchedphoto.app.tts.DeathLineSpeaker
import com.scorchedphoto.app.tts.VoiceOption
import com.scorchedphoto.engine.ai.Difficulty
import com.scorchedphoto.engine.tanks.TankShape
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

private const val MIN_TANKS = 2
private const val MAX_TANKS = 6

// Gives each tank some out-of-the-box voice distinction even before real per-device
// voices have finished enumerating (see DeathLineSpeaker.availableVoices) or the user has
// touched anything - purely a synchronous default, not a claim about any specific voice.
private val DEFAULT_PITCHES = listOf(1.0f, 1.2f, 0.8f, 1.35f, 0.65f, 1.5f)

@HiltViewModel
class GameSetupViewModel @Inject constructor(
    private val matchConfigRepository: MatchConfigRepository,
    private val deathLineSpeaker: DeathLineSpeaker,
) : ViewModel() {

    private val _tankConfigs = MutableStateFlow(defaultConfigs(MIN_TANKS))
    val tankConfigs: StateFlow<List<TankConfig>> = _tankConfigs.asStateFlow()

    /** Real voices this device's TTS engine has, for the setup screen's voice picker -
     * starts as just [VoiceOption.SYSTEM_DEFAULT] and fills in once enumeration completes. */
    val availableVoices: StateFlow<List<VoiceOption>> = deathLineSpeaker.availableVoices

    val canAddTank: Boolean get() = _tankConfigs.value.size < MAX_TANKS
    val canRemoveTank: Boolean get() = _tankConfigs.value.size > MIN_TANKS

    fun addTank() {
        if (!canAddTank) return
        val current = _tankConfigs.value
        _tankConfigs.value = current + defaultConfigs(current.size + 1).last()
    }

    fun removeTank() {
        if (!canRemoveTank) return
        _tankConfigs.value = _tankConfigs.value.dropLast(1)
    }

    fun toggleCpu(index: Int) {
        updateAt(index) { it.copy(isCpu = !it.isCpu) }
    }

    fun setDifficulty(index: Int, difficulty: Difficulty) {
        updateAt(index) { it.copy(difficulty = difficulty) }
    }

    fun setShape(index: Int, shape: TankShape) {
        updateAt(index) { it.copy(shape = shape) }
    }

    fun setVoice(index: Int, voiceId: String?) {
        updateAt(index) { it.copy(voiceId = voiceId) }
    }

    fun setPitch(index: Int, pitch: Float) {
        updateAt(index) { it.copy(pitch = pitch) }
    }

    fun setSpeechRate(index: Int, speechRate: Float) {
        updateAt(index) { it.copy(speechRate = speechRate) }
    }

    /** Speaks the tank's current voice/pitch/rate combo, naming the voice so the effect
     * of the current picks is unambiguous rather than a generic filler phrase. */
    fun testVoice(index: Int) {
        val config = _tankConfigs.value.getOrNull(index) ?: return
        val voiceName = availableVoices.value.firstOrNull { it.id == config.voiceId }?.displayName
            ?: VoiceOption.SYSTEM_DEFAULT.displayName
        deathLineSpeaker.speak("Hello, my name is $voiceName", config.voiceId, config.pitch, config.speechRate)
    }

    fun commitAndStart() {
        matchConfigRepository.matchConfig = MatchConfig(_tankConfigs.value)
    }

    private fun updateAt(index: Int, transform: (TankConfig) -> TankConfig) {
        _tankConfigs.value = _tankConfigs.value.mapIndexed { i, config ->
            if (i == index) transform(config) else config
        }
    }

    private fun defaultConfigs(count: Int): List<TankConfig> = (0 until count).map { i ->
        TankConfig(
            name = "Tank ${i + 1}",
            color = TANK_COLOR_PALETTE[i % TANK_COLOR_PALETTE.size],
            isCpu = i != 0, // first tank defaults to human, rest to CPU
            shape = TankShape.entries[i % TankShape.entries.size],
            pitch = DEFAULT_PITCHES[i % DEFAULT_PITCHES.size],
        )
    }
}
