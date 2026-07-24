package com.scorchedphoto.app.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scorchedphoto.app.tts.CustomVoice
import com.scorchedphoto.app.tts.CustomVoiceRepository
import com.scorchedphoto.app.tts.DeathLineSpeaker
import com.scorchedphoto.app.tts.VoiceOption
import com.scorchedphoto.engine.ai.Difficulty
import com.scorchedphoto.engine.tanks.TankShape
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

private const val MIN_TANKS = 2
private const val MAX_TANKS = 6
private const val PITCH_RATE_MIN = 0.5f
private const val PITCH_RATE_MAX = 2.0f

// kotlin.random.Random has no ranged nextFloat() overload (only nextDouble/nextInt do).
private fun Random.nextFloat(from: Float, until: Float): Float = from + nextFloat() * (until - from)

@HiltViewModel
class GameSetupViewModel @Inject constructor(
    private val matchConfigRepository: MatchConfigRepository,
    private val deathLineSpeaker: DeathLineSpeaker,
    private val customVoiceRepository: CustomVoiceRepository,
) : ViewModel() {

    private val _tankConfigs = MutableStateFlow(defaultConfigs(MIN_TANKS))
    val tankConfigs: StateFlow<List<TankConfig>> = _tankConfigs.asStateFlow()

    /** Real voices this device's TTS engine has, for the setup screen's voice picker -
     * starts as just [VoiceOption.SYSTEM_DEFAULT] and fills in once enumeration completes. */
    val availableVoices: StateFlow<List<VoiceOption>> = deathLineSpeaker.availableVoices

    /** User-saved voice/pitch/rate presets, persisted across app restarts and updates. */
    val customVoices: StateFlow<List<CustomVoice>> = customVoiceRepository.customVoices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val canAddTank: Boolean get() = _tankConfigs.value.size < MAX_TANKS
    val canRemoveTank: Boolean get() = _tankConfigs.value.size > MIN_TANKS

    fun addTank() {
        if (!canAddTank) return
        val current = _tankConfigs.value
        val newTank = defaultConfigs(current.size + 1).last()
        _tankConfigs.value = current + newTank
        if (newTank.isCpu) {
            randomizeVoiceSettings(current.size)
        }
    }

    fun removeTank() {
        if (!canRemoveTank) return
        _tankConfigs.value = _tankConfigs.value.dropLast(1)
    }

    fun toggleCpu(index: Int) {
        updateAt(index) { config ->
            val newConfig = config.copy(isCpu = !config.isCpu)
            if (newConfig.isCpu) {
                newConfig.copy(
                    voiceId = pickRandomVoice(),
                    pitch = Random.nextFloat(PITCH_RATE_MIN, PITCH_RATE_MAX),
                    speechRate = Random.nextFloat(PITCH_RATE_MIN, PITCH_RATE_MAX),
                )
            } else {
                newConfig
            }
        }
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

    fun applyCustomVoice(index: Int, customVoice: CustomVoice) {
        updateAt(index) {
            it.copy(voiceId = customVoice.voiceId, pitch = customVoice.pitch, speechRate = customVoice.speechRate)
        }
    }

    fun saveCustomVoice(index: Int, name: String) {
        val config = _tankConfigs.value.getOrNull(index) ?: return
        viewModelScope.launch {
            customVoiceRepository.save(name, config.voiceId, config.pitch, config.speechRate)
        }
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

    private fun pickRandomVoice(): String? {
        val voices = availableVoices.value
        return if (voices.size > 1) {
            voices.drop(1).randomOrNull()?.id
        } else {
            null
        }
    }

    private fun randomizeVoiceSettings(index: Int) {
        updateAt(index) { config ->
            config.copy(
                voiceId = pickRandomVoice(),
                pitch = Random.nextFloat(PITCH_RATE_MIN, PITCH_RATE_MAX),
                speechRate = Random.nextFloat(PITCH_RATE_MIN, PITCH_RATE_MAX),
            )
        }
    }

    private fun defaultConfigs(count: Int): List<TankConfig> = (0 until count).map { i ->
        val isCpuTank = i != 0
        TankConfig(
            name = "Tank ${i + 1}",
            color = TANK_COLOR_PALETTE[i % TANK_COLOR_PALETTE.size],
            isCpu = isCpuTank,
            shape = TankShape.entries[i % TankShape.entries.size],
            voiceId = if (isCpuTank) pickRandomVoice() else null,
            pitch = if (isCpuTank) Random.nextFloat(PITCH_RATE_MIN, PITCH_RATE_MAX) else 1.0f,
            speechRate = if (isCpuTank) Random.nextFloat(PITCH_RATE_MIN, PITCH_RATE_MAX) else 1.0f,
        )
    }
}
