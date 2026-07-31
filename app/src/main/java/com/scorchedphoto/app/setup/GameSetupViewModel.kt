package com.scorchedphoto.app.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scorchedphoto.app.game.PhotoUsageMode
import com.scorchedphoto.app.game.SkyLook
import com.scorchedphoto.app.tts.CustomVoice
import com.scorchedphoto.app.tts.CustomVoiceRepository
import com.scorchedphoto.app.tts.DeathLineSpeaker
import com.scorchedphoto.app.tts.VoiceOption
import com.scorchedphoto.engine.EdgeType
import com.scorchedphoto.engine.FloorType
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

    /** Real voices this device's TTS engine has, for the setup screen's voice picker -
     * starts as just [VoiceOption.SYSTEM_DEFAULT] and fills in once enumeration completes.
     * Must be initialized before [_tankConfigs] below, since its initializer (via
     * [defaultConfigs]/[pickRandomVoice]) reads this property - Kotlin runs property
     * initializers in declaration order, so a later declaration would still be null here. */
    val availableVoices: StateFlow<List<VoiceOption>> = deathLineSpeaker.availableVoices

    private val _tankConfigs = MutableStateFlow(defaultConfigs(MIN_TANKS))
    val tankConfigs: StateFlow<List<TankConfig>> = _tankConfigs.asStateFlow()

    // Null means "Random" is currently selected in the settings UI - resolved to one concrete
    // EdgeType (see commitAndStart) only once the match actually starts, so it's chosen fresh
    // per match but then stays fixed - never re-rolled mid-match, and never reaches MatchConfig/
    // GameEngine as a live "random" value.
    private val _wallType = MutableStateFlow<EdgeType?>(EdgeType.NONE)
    val wallType: StateFlow<EdgeType?> = _wallType.asStateFlow()

    private val _ceilingType = MutableStateFlow<EdgeType?>(EdgeType.NONE)
    val ceilingType: StateFlow<EdgeType?> = _ceilingType.asStateFlow()

    // Same "Random" sentinel pattern as _wallType/_ceilingType above, but floors have no NONE
    // value (see FloorType's own doc - the floor is never simply "absent") so this defaults to
    // FloorType.GROUND, today's only floor behavior, instead.
    private val _floorType = MutableStateFlow<FloorType?>(FloorType.GROUND)
    val floorType: StateFlow<FloorType?> = _floorType.asStateFlow()

    // No "Random" sentinel, unlike _wallType/_ceilingType/_floorType above - this is a plain
    // user pick with a real default (BACKGROUND, matching pre-existing behavior), never
    // resolved at commit time. skyLook/terrainColor (used only by TERRAIN/SKY modes
    // respectively) are always auto-randomized instead - resolved fresh in commitAndStart,
    // mirroring how Random wall/ceiling/floor picks resolve there, but with no user-facing
    // control of their own.
    private val _photoUsageMode = MutableStateFlow(PhotoUsageMode.BACKGROUND)
    val photoUsageMode: StateFlow<PhotoUsageMode> = _photoUsageMode.asStateFlow()

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
        if (config.voiceId == VoiceOption.NONE.id) return
        val voiceName = availableVoices.value.firstOrNull { it.id == config.voiceId }?.displayName
            ?: VoiceOption.SYSTEM_DEFAULT.displayName
        deathLineSpeaker.speak("Hello, my name is $voiceName", config.voiceId, config.pitch, config.speechRate)
    }

    fun setWallType(type: EdgeType?) {
        _wallType.value = type
    }

    fun setCeilingType(type: EdgeType?) {
        _ceilingType.value = type
    }

    fun setFloorType(type: FloorType?) {
        _floorType.value = type
    }

    fun setPhotoUsageMode(mode: PhotoUsageMode) {
        _photoUsageMode.value = mode
    }

    /** Resolves "Random" (a null wallType/ceilingType/floorType) to one concrete type per side,
     * each independently, right before the match starts - see _wallType's doc for why this is
     * the one place that needs to happen. Also resolves skyLook/terrainColor fresh here, the
     * same way - see _photoUsageMode's own doc for why those two have no user-facing control
     * of their own. */
    fun commitAndStart() {
        val resolvedWallType = _wallType.value ?: EdgeType.entries.random()
        val resolvedCeilingType = _ceilingType.value ?: EdgeType.entries.random()
        val resolvedFloorType = _floorType.value ?: FloorType.entries.random()
        matchConfigRepository.matchConfig = MatchConfig(
            _tankConfigs.value,
            resolvedWallType,
            resolvedCeilingType,
            resolvedFloorType,
            photoUsageMode = _photoUsageMode.value,
            skyLook = SkyLook.entries.random(),
            terrainColor = TERRAIN_COLOR_PALETTE.random(),
        )
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
