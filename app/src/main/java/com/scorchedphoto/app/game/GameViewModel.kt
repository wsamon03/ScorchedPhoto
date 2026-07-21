package com.scorchedphoto.app.game

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import com.scorchedphoto.app.capture.PhotoRepository
import com.scorchedphoto.app.result.MatchResultRepository
import com.scorchedphoto.app.setup.MatchConfigRepository
import com.scorchedphoto.app.terrainpreview.TerrainRepository
import com.scorchedphoto.engine.GameEngine
import com.scorchedphoto.engine.combat.WeaponCatalog
import com.scorchedphoto.engine.combat.WeaponType
import com.scorchedphoto.engine.tanks.Tank
import com.scorchedphoto.engine.tanks.TankPlacement
import com.scorchedphoto.terrain.HeightMap
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import kotlin.random.Random

@HiltViewModel
class GameViewModel @Inject constructor(
    terrainRepository: TerrainRepository,
    matchConfigRepository: MatchConfigRepository,
    photoRepository: PhotoRepository,
    private val matchResultRepository: MatchResultRepository,
) : ViewModel() {

    val engine: GameEngine
    val backgroundPhoto: Bitmap? = photoRepository.workingPhoto

    /** HUD -> engine intents, drained exclusively by [GameLoopThread]. */
    val commandQueue = ConcurrentLinkedQueue<GameCommand>()

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

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

        engine = GameEngine(heightMap, tanks)
        publishState()
    }

    fun submitCommand(command: GameCommand) {
        commandQueue.offer(command)
    }

    /** Called by [GameLoopThread] after each tick; safe to call from any thread. */
    fun publishState() {
        val current = engine.currentTank
        val winResult = engine.winResult
        if (winResult != null) {
            val winnerTank = engine.tanks.firstOrNull { it.ownerId == winResult.winningOwnerId }
            matchResultRepository.winnerName = winnerTank?.name
            matchResultRepository.winnerColor = winnerTank?.color
        }

        _uiState.value = GameUiState(
            phase = engine.phase,
            currentTankId = current?.id,
            currentTankIsCpu = current?.isCpu ?: false,
            currentAngleDeg = current?.angleDeg ?: 45f,
            currentPower = current?.power ?: 50f,
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
            winnerOwnerId = winResult?.winningOwnerId,
        )
    }
}
