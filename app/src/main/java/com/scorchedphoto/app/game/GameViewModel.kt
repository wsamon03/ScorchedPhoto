package com.scorchedphoto.app.game

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import com.scorchedphoto.app.audio.GameSoundController
import com.scorchedphoto.app.capture.PhotoRepository
import com.scorchedphoto.app.result.MatchResultRepository
import com.scorchedphoto.app.result.MatchWinner
import com.scorchedphoto.app.settings.PhraseCategory
import com.scorchedphoto.app.settings.PhraseRepository
import com.scorchedphoto.app.setup.MatchConfigRepository
import com.scorchedphoto.app.terrainpreview.TerrainRepository
import com.scorchedphoto.app.tts.DeathLineSpeaker
import com.scorchedphoto.app.tts.VoiceOption
import com.scorchedphoto.engine.GameEngine
import com.scorchedphoto.engine.combat.WeaponCatalog
import com.scorchedphoto.engine.combat.WeaponType
import com.scorchedphoto.engine.physics.maxPowerForHealth
import com.scorchedphoto.engine.tanks.Tank
import com.scorchedphoto.engine.tanks.TankPlacement
import com.scorchedphoto.terrain.HeightMap
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import kotlin.random.Random

@HiltViewModel
class GameViewModel @Inject constructor(
    terrainRepository: TerrainRepository,
    matchConfigRepository: MatchConfigRepository,
    photoRepository: PhotoRepository,
    phraseRepository: PhraseRepository,
    private val matchResultRepository: MatchResultRepository,
    private val deathLineSpeaker: DeathLineSpeaker,
    val soundController: GameSoundController,
) : ViewModel() {

    val engine: GameEngine
    val backgroundPhoto: Bitmap? = photoRepository.workingPhoto

    /** HUD -> engine intents, drained exclusively by [GameLoopThread]. */
    val commandQueue = ConcurrentLinkedQueue<GameCommand>()

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    private data class TankVoiceSettings(val voiceId: String?, val pitch: Float, val speechRate: Float)
    private val voiceSettings: List<TankVoiceSettings>

    // Snapshotted once per match, the same way voiceSettings/heightMap are - a player isn't
    // expected to edit phrases mid-match, and GameSurfaceView/GameRenderer/GameLoopThread
    // need a plain synchronous List at construction time, not a Flow. runBlocking is safe
    // here: this only runs once, during ViewModel init, and Room's tiny phrases table
    // resolves essentially instantly.
    val deathPhrases: List<String> = runBlocking { phraseRepository.getEnabledTexts(PhraseCategory.DEATH) }
    val attackPhrases: List<String> = runBlocking { phraseRepository.getEnabledTexts(PhraseCategory.ATTACK) }

    init {
        val storedHeightMap = requireNotNull(terrainRepository.heightMap) {
            "GameScreen reached with no terrain generated"
        }
        // Copy the terrain: CraterCarver mutates groundY in place as the match plays out,
        // and TerrainRepository's copy must stay pristine so "Rematch" starts from the
        // original shape instead of the previous match's battle scars.
        val heightMap = HeightMap(storedHeightMap.width, storedHeightMap.height, storedHeightMap.groundY.copyOf())
        val matchConfig = requireNotNull(matchConfigRepository.matchConfig) {
            "GameScreen reached with no match configured"
        }

        // Freshly time-seeded (not a fixed/injected Random) so tanks land somewhere new
        // each time this ViewModel is constructed - including "Rematch", which navigates
        // to a brand-new Game.route backstack entry (see ScorchedNavGraph) and therefore
        // a brand-new GameViewModel/init run rather than reusing the previous match's.
        val positions = TankPlacement.placeX(heightMap, matchConfig.tankConfigs.size, Random(System.nanoTime()))
        val tanks = matchConfig.tankConfigs.mapIndexed { index, config ->
            Tank(
                id = index,
                ownerId = index,
                name = config.name,
                color = config.color,
                isCpu = config.isCpu,
                x = positions[index].toFloat(),
                y = 0f,
                currentWeapon = WeaponType.STANDARD_SHELL,
                difficulty = config.difficulty,
                shape = config.shape,
            )
        }

        engine = GameEngine(
            heightMap,
            tanks,
            wallType = matchConfig.wallType,
            ceilingType = matchConfig.ceilingType,
            floorType = matchConfig.floorType,
        )
        voiceSettings = matchConfig.tankConfigs.map { TankVoiceSettings(it.voiceId, it.pitch, it.speechRate) }
        publishState()
    }

    fun submitCommand(command: GameCommand) {
        commandQueue.offer(command)
    }

    /** Called by [GameRenderer] (via [GameSurfaceView]) the moment a tank's death taunt is
     * assigned - see GameRenderer.burnMessageFor - so it's spoken exactly once per death,
     * in that tank's own chosen voice/pitch/rate. Safe to call from any thread. */
    fun onBurnMessageAssigned(tankId: Int, spokenText: String) {
        speakForTank(tankId, spokenText)
    }

    /** Called by [GameLoopThread] the moment a tank's pre-fire taunt is chosen - see
     * GameLoopThread.beginFireSequence - so it's spoken once per shot, in that tank's own
     * chosen voice/pitch/rate, the same way [onBurnMessageAssigned] speaks a death taunt.
     * Safe to call from any thread. */
    fun onFireMessageAssigned(tankId: Int, spokenText: String) {
        speakForTank(tankId, spokenText)
    }

    private fun speakForTank(tankId: Int, spokenText: String) {
        val settings = voiceSettings.getOrElse(tankId) { TankVoiceSettings(null, 1f, 1f) }
        if (settings.voiceId == VoiceOption.NONE.id) return
        deathLineSpeaker.speak(spokenText, settings.voiceId, settings.pitch, settings.speechRate)
    }

    /** Called by [GameLoopThread] after each tick; safe to call from any thread. */
    fun publishState() {
        val current = engine.currentTank
        val winResult = engine.winResult
        if (winResult != null) {
            val winnerTanks = engine.tanks.filter { it.ownerId in winResult.winningOwnerIds }
            matchResultRepository.winners = winnerTanks.map { MatchWinner(it.name, it.color) }
        }

        _uiState.value = GameUiState(
            phase = engine.phase,
            currentTankId = current?.id,
            currentTankIsCpu = current?.isCpu ?: false,
            currentAngleDeg = current?.angleDeg ?: 45f,
            currentPower = current?.power ?: 50f,
            currentMaxPower = current?.let { maxPowerForHealth(it.health, Tank.MAX_HEALTH) } ?: 100f,
            currentTankX = current?.x ?: 0f,
            currentTankY = current?.y ?: 0f,
            terrainWidth = engine.terrain.width,
            terrainHeight = engine.terrain.height,
            weapons = WeaponCatalog.all.map { weapon ->
                WeaponHudInfo(
                    weaponType = weapon.type,
                    ammoRemaining = current?.let { engine.ammoFor(it.id, weapon.type) },
                    selected = current?.currentWeapon == weapon.type,
                )
            },
            tanks = engine.tanks.map { TankHudInfo(it.id, it.name, it.color, it.health, it.alive) },
            windVelocity = engine.wind.velocity,
            windMaxMagnitude = engine.maxWindMagnitude,
            winnerOwnerIds = winResult?.winningOwnerIds ?: emptyList(),
        )
    }
}
