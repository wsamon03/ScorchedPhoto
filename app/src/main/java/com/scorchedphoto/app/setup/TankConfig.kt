package com.scorchedphoto.app.setup

import com.scorchedphoto.app.game.PhotoUsageMode
import com.scorchedphoto.app.game.SkyLook
import com.scorchedphoto.engine.EdgeType
import com.scorchedphoto.engine.FloorType
import com.scorchedphoto.engine.ai.Difficulty
import com.scorchedphoto.engine.tanks.TankShape

/** Bounds for both a single match's live tank roster ([GameSetupViewModel.addTank]/[GameSetupViewModel.removeTank])
 * and the persisted default player count edited from the title screen's settings (see
 * [com.scorchedphoto.app.settings.MatchDefaults]) - shared so the two can never drift apart. */
const val MIN_TANKS = 2
const val MAX_TANKS = 6

data class TankConfig(
    val name: String,
    val color: Int,
    val isCpu: Boolean,
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val shape: TankShape = TankShape.CLASSIC,
    // null voiceId means "use the TTS engine's own default voice" (see VoiceOption.SYSTEM_DEFAULT).
    val voiceId: String? = null,
    val pitch: Float = 1.0f,
    val speechRate: Float = 1.0f,
)

data class MatchConfig(
    val tankConfigs: List<TankConfig>,
    val wallType: EdgeType = EdgeType.NONE,
    val ceilingType: EdgeType = EdgeType.NONE,
    val floorType: FloorType = FloorType.GROUND,
    val photoUsageMode: PhotoUsageMode = PhotoUsageMode.BACKGROUND,
    val skyLook: SkyLook = SkyLook.CLEAR,
    val terrainColor: Int = TERRAIN_COLOR_PALETTE.first(),
)

val TANK_COLOR_PALETTE: List<Int> = listOf(
    0xFFE53935L.toInt(),
    0xFF1E88E5L.toInt(),
    0xFF43A047L.toInt(),
    0xFFF9A825L.toInt(),
    0xFF8E24AAL.toInt(),
    0xFFFB8C00L.toInt(),
)

/** Fixed ground/dirt/grass/stone/sand palette for PhotoUsageMode.SKY - the solid color shown
 * wherever the sky-clipped photo doesn't cover (below the terrain line). Resolved via
 * .random() once per match in GameSetupViewModel.commitAndStart, mirroring how EdgeType/
 * FloorType "Random" already resolves there - never user-picked, no UI lists these directly. */
val TERRAIN_COLOR_PALETTE: List<Int> = listOf(
    0xFF6D4C41L.toInt(), // brown dirt
    0xFF8D6E63L.toInt(), // tan dirt
    0xFF558B2FL.toInt(), // dark grass green
    0xFF7CB342L.toInt(), // grass green
    0xFF9E9E76L.toInt(), // dusty olive
    0xFFC2B280L.toInt(), // desert sand
    0xFF757575L.toInt(), // grey stone
    0xFF5D4037L.toInt(), // dark earth/mud
    0xFFA1887FL.toInt(), // pale clay
)
