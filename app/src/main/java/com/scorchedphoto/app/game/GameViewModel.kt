package com.scorchedphoto.app.game

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import com.scorchedphoto.app.capture.PhotoRepository
import com.scorchedphoto.app.setup.MatchConfigRepository
import com.scorchedphoto.app.terrainpreview.TerrainRepository
import com.scorchedphoto.engine.GameEngine
import com.scorchedphoto.engine.combat.WeaponCatalog
import com.scorchedphoto.engine.combat.WeaponType
import com.scorchedphoto.engine.tanks.Tank
import com.scorchedphoto.engine.tanks.TankPlacement
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
) : ViewModel() {

    val engine: GameEngine
    val backgroundPhoto: Bitmap? = photoRepository.workingPhoto

    /** HUD -> engine intents, drained exclusively by [GameLoopThread]. */
    val commandQueue = ConcurrentLinkedQueue<GameCommand>()

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    init {
        val heightMap = requireNotNull(terrainRepository.heightMap) {
            "GameScreen reached with no terrain generated"
        }
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
        _uiState.value = GameUiState(
            phase = engine.phase,
            currentTankId = current?.id,
            currentTankIsCpu = current?.isCpu ?: false,
            currentAngleDeg = current?.angleDeg ?: 45f,
            currentPower = current?.power ?: 50f,
            weapons = WeaponCatalog.all.map { weapon ->
                WeaponHudInfo(
                    weaponType = weapon.type,
                    ammoRemaining = current?.let { engine.ammoFor(it.id, weapon.type) },
                    selected = current?.currentWeapon == weapon.type,
                )
            },
            tanks = engine.tanks.map { TankHudInfo(it.id, it.name, it.color, it.health, it.alive) },
            windVelocity = engine.wind.velocity,
            winnerOwnerId = engine.winResult?.winningOwnerId,
        )
    }
}
