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
import com.scorchedphoto.app.setup.TankConfig
import com.scorchedphoto.app.shop.EconomyRepository
import com.scorchedphoto.app.terrainpreview.TerrainRepository
import com.scorchedphoto.app.tournament.TournamentRepository
import com.scorchedphoto.app.tournament.TournamentScorer
import com.scorchedphoto.app.tts.DeathLineSpeaker
import com.scorchedphoto.app.tts.VoiceOption
import com.scorchedphoto.engine.GameEngine
import com.scorchedphoto.engine.combat.WeaponCatalog
import com.scorchedphoto.engine.combat.WeaponType
import com.scorchedphoto.engine.economy.MatchEarnings
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
    private val tournamentRepository: TournamentRepository,
    private val economyRepository: EconomyRepository,
    private val deathLineSpeaker: DeathLineSpeaker,
    val soundController: GameSoundController,
) : ViewModel() {

    val engine: GameEngine
    val backgroundPhoto: Bitmap? = photoRepository.workingPhoto
    // Set inside init, where matchConfig is in scope - a plain val read straight off it, the
    // same pattern backgroundPhoto above already uses, since these have no physics role and so
    // (unlike wallType/ceilingType/floorType) never pass through GameEngine at all.
    val photoUsageMode: PhotoUsageMode
    val skyLook: SkyLook
    val terrainColor: Int

    // Fixed for the whole match (a tank's isCpu never changes mid-match) - lets GameScreen skip
    // the pass-device screen entirely when there's nobody to actually pass the device to.
    val humanTankCount: Int

    /** HUD -> engine intents, drained exclusively by [GameLoopThread]. */
    val commandQueue = ConcurrentLinkedQueue<GameCommand>()

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    private data class TankVoiceSettings(val voiceId: String?, val pitch: Float, val speechRate: Float)

    // Keyed by tank id (== ownerId, == the tournament's own original roster index - see
    // activeConfigs' own doc below) rather than a plain positional List, since a Knockout/
    // Survivor game's roster can be a non-dense subset of the tournament's full original one.
    private val voiceSettings: Map<Int, TankVoiceSettings>

    // Set once publishState() has scored a finished match into the active tournament (see its
    // own doc) - guards against re-scoring the same match's result on every later tick, since
    // publishState() itself keeps being called every tick for as long as this ViewModel lives,
    // long after phase first reaches GAME_OVER.
    private var tournamentScored = false

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
        photoUsageMode = matchConfig.photoUsageMode
        skyLook = matchConfig.skyLook
        terrainColor = matchConfig.terrainColor
        humanTankCount = matchConfig.tankConfigs.count { !it.isCpu }

        // Knockout/Survivor tournaments drop knocked-out tanks from later games - every
        // surviving tank keeps its *original* tournament roster index as both Tank.id/ownerId
        // (matching TournamentTankState.index) rather than being renumbered from 0, so
        // tournament scoring never needs to remap a game-local id back to the tournament's own
        // roster identity. A no-op (every index passes straight through) for an ordinary
        // Single Game or a tournament's own first game, where nothing is knocked out yet -
        // tournamentRepository.state is seeded with every tank still in the running right
        // alongside matchConfigRepository.matchConfig itself, see GameSetupViewModel.commitAndStart.
        val tournamentTanks = tournamentRepository.state?.tanks
        val activeConfigs: List<Pair<Int, TankConfig>> = matchConfig.tankConfigs.withIndex()
            .filter { (index, _) -> tournamentTanks?.getOrNull(index)?.knockedOut != true }
            .map { it.index to it.value }

        // Freshly time-seeded (not a fixed/injected Random) so tanks land somewhere new
        // each time this ViewModel is constructed - including "Rematch", which navigates
        // to a brand-new Game.route backstack entry (see ScorchedNavGraph) and therefore
        // a brand-new GameViewModel/init run rather than reusing the previous match's.
        val positions = TankPlacement.placeX(heightMap, activeConfigs.size, Random(System.nanoTime()))
        val tanks = activeConfigs.mapIndexed { placementIndex, (originalIndex, config) ->
            Tank(
                id = originalIndex,
                ownerId = originalIndex,
                name = config.name,
                color = config.color,
                isCpu = config.isCpu,
                x = positions[placementIndex].toFloat(),
                y = 0f,
                currentWeapon = WeaponType.STANDARD_SHELL,
                difficulty = config.difficulty,
                shape = config.shape,
            )
        }

        // Owner id and tank id are always the same value in this engine (see the Tank(...)
        // construction above), so economyRepository.purchasedAmmo's ownerId keys already line
        // up with GameEngine's own tank-id-keyed startingAmmoOverride - no remapping needed,
        // just narrowed to whichever tanks are actually in this round's roster.
        val startingAmmoOverride = activeConfigs.associate { (originalIndex, _) ->
            originalIndex to (economyRepository.purchasedAmmo[originalIndex] ?: emptyMap())
        }
        engine = GameEngine(
            heightMap,
            tanks,
            wallType = matchConfig.wallType,
            ceilingType = matchConfig.ceilingType,
            floorType = matchConfig.floorType,
            startingAmmoOverride = startingAmmoOverride,
        )
        voiceSettings = activeConfigs.associate { (originalIndex, config) ->
            originalIndex to TankVoiceSettings(config.voiceId, config.pitch, config.speechRate)
        }
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
        val settings = voiceSettings[tankId] ?: TankVoiceSettings(null, 1f, 1f)
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
            matchResultRepository.deathLog = engine.deathLog
            matchResultRepository.winnerOwnerIds = winResult.winningOwnerIds
            if (!tournamentScored) {
                tournamentScored = true
                tournamentRepository.state?.let { tournamentState ->
                    tournamentRepository.overallWinnerOwnerId =
                        TournamentScorer.score(tournamentState, engine.deathLog, winResult.winningOwnerIds)
                }
                // Runs for every match, tournament or not - harmless for an ordinary Single
                // Game, since its shop never reappears to spend the credited balance, but keeps
                // this one code path uniform rather than special-cased on tournament state.
                val aliveOwnerIds = engine.tanks.filter { it.alive }.map { it.ownerId }.toSet()
                val earnings = MatchEarnings.compute(
                    allOwnerIds = engine.tanks.map { it.ownerId },
                    aliveOwnerIds = aliveOwnerIds,
                    deathLog = engine.deathLog,
                    damageDealtByOwner = engine.damageDealtByOwner,
                )
                for ((ownerId, amount) in earnings) {
                    economyRepository.balances[ownerId] = (economyRepository.balances[ownerId] ?: 0) + amount
                }
            }
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
