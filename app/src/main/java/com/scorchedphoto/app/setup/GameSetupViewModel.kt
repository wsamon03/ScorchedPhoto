package com.scorchedphoto.app.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scorchedphoto.app.game.SkyLook
import com.scorchedphoto.app.settings.MatchDefaultsRepository
import com.scorchedphoto.app.tournament.TournamentRepository
import com.scorchedphoto.app.tournament.TournamentState
import com.scorchedphoto.app.tournament.TournamentTankState
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

private const val PITCH_RATE_MIN = 0.5f
private const val PITCH_RATE_MAX = 2.0f

// kotlin.random.Random has no ranged nextFloat() overload (only nextDouble/nextInt do).
private fun Random.nextFloat(from: Float, until: Float): Float = from + nextFloat() * (until - from)

@HiltViewModel
class GameSetupViewModel @Inject constructor(
    private val matchConfigRepository: MatchConfigRepository,
    private val deathLineSpeaker: DeathLineSpeaker,
    private val customVoiceRepository: CustomVoiceRepository,
    private val matchDefaultsRepository: MatchDefaultsRepository,
    private val tournamentRepository: TournamentRepository,
) : ViewModel() {

    /** Real voices this device's TTS engine has, for the setup screen's voice picker -
     * starts as just [VoiceOption.SYSTEM_DEFAULT] and fills in once enumeration completes.
     * Must be initialized before [_tankConfigs] below, since its initializer (via
     * [defaultConfigs]/[pickRandomVoice]) reads this property - Kotlin runs property
     * initializers in declaration order, so a later declaration would still be null here. */
    val availableVoices: StateFlow<List<VoiceOption>> = deathLineSpeaker.availableVoices

    // Seeded with the hardcoded baseline here (matching MatchDefaults()'s own fallback) so the
    // screen never flashes empty/placeholder state - overwritten once init{} below has read
    // the player's actual persisted starting values, which is normally near-instant (a single
    // indexed Room row) but not synchronous, hence needing a placeholder at all.
    private val _tankConfigs = MutableStateFlow(defaultConfigs(MIN_TANKS))
    val tankConfigs: StateFlow<List<TankConfig>> = _tankConfigs.asStateFlow()

    // Null means "Random" is currently selected in the settings UI - resolved to one concrete
    // EdgeType (see commitAndStart) only once the match actually starts, so it's chosen fresh
    // per match but then stays fixed - never re-rolled mid-match, and never reaches MatchConfig/
    // GameEngine as a live "random" value. See _tankConfigs' own doc on the init{}-overwrite
    // pattern this and the two properties below also follow.
    private val _wallType = MutableStateFlow<EdgeType?>(EdgeType.NONE)
    val wallType: StateFlow<EdgeType?> = _wallType.asStateFlow()

    private val _ceilingType = MutableStateFlow<EdgeType?>(EdgeType.NONE)
    val ceilingType: StateFlow<EdgeType?> = _ceilingType.asStateFlow()

    // Same "Random" sentinel pattern as _wallType/_ceilingType above, but floors have no NONE
    // value (see FloorType's own doc - the floor is never simply "absent") so this defaults to
    // FloorType.GROUND, today's only floor behavior, instead.
    private val _floorType = MutableStateFlow<FloorType?>(FloorType.GROUND)
    val floorType: StateFlow<FloorType?> = _floorType.asStateFlow()

    /** Applies the player's persisted starting values (see [MatchDefaultsRepository]/title
     * screen's own gear menu) exactly once, as soon as they're available - a fresh
     * [GameSetupViewModel] is created every time this screen is (re)entered (it's nav-backstack-
     * scoped, not a singleton), so this always reflects whatever was most recently saved,
     * including changes made after this match's own roster/wall/ceiling/floor were already
     * hand-edited on a previous visit to this screen within the same session. */
    init {
        viewModelScope.launch {
            val defaults = matchDefaultsRepository.defaults.first()
            _tankConfigs.value = defaultConfigs(defaults.playerCount)
            _wallType.value = defaults.wallType
            _ceilingType.value = defaults.ceilingType
            _floorType.value = defaults.floorType
        }
    }

    /** User-saved voice/pitch/rate presets, persisted across app restarts and updates. */
    val customVoices: StateFlow<List<CustomVoice>> = customVoiceRepository.customVoices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** True while a "Test" voice line is actively being spoken - every tank row's Test
     * button is disabled while this is true (see GameSetupScreen), so overlapping test
     * requests can't queue up and play back-to-back for the wrong tank's voice settings. */
    val isTestingVoice: StateFlow<Boolean> = deathLineSpeaker.isSpeaking

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

    /** Resolves "Random" (a null wallType/ceilingType/floorType) to one concrete type per side,
     * each independently, right before the match starts - see _wallType's doc for why this is
     * the one place that needs to happen. Also resolves skyLook/terrainColor fresh here (used
     * only by TERRAIN/SKY photo usage modes respectively - always auto-randomized, with no
     * user-facing control of their own). photoUsageMode itself is left at its default here;
     * TerrainPreviewViewModel patches it into this same repository entry once the user picks
     * it on the terrain-creation screen, which is reached only after this commits.
     *
     * If a tournament mode was picked on [com.scorchedphoto.app.tournament.MultiGameSetupScreen],
     * also seeds [TournamentRepository.state] from this roster - this screen is only ever
     * reached once per tournament (later games loop straight from
     * [com.scorchedphoto.app.result.VictoryScreen] back into [com.scorchedphoto.app.game.GameScreen]
     * without revisiting setup), so `state == null` here reliably means "this is the
     * tournament's first game," guarding against ever re-seeding (and so discarding) an
     * already-in-progress tournament's standings. */
    fun commitAndStart() {
        val resolvedWallType = _wallType.value ?: EdgeType.entries.random()
        val resolvedCeilingType = _ceilingType.value ?: EdgeType.entries.random()
        val resolvedFloorType = _floorType.value ?: FloorType.entries.random()
        val tankConfigs = _tankConfigs.value
        matchConfigRepository.matchConfig = MatchConfig(
            tankConfigs,
            resolvedWallType,
            resolvedCeilingType,
            resolvedFloorType,
            skyLook = SkyLook.entries.random(),
            terrainColor = TERRAIN_COLOR_PALETTE.random(),
        )
        val tournamentConfig = tournamentRepository.config
        if (tournamentConfig != null && tournamentRepository.state == null) {
            tournamentRepository.state = TournamentState(
                tournamentConfig,
                tankConfigs.mapIndexed { index, config -> TournamentTankState(index, config.name, config.color) }.toMutableList(),
            )
        }
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
