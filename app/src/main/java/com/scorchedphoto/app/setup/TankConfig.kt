package com.scorchedphoto.app.setup

import com.scorchedphoto.engine.EdgeType
import com.scorchedphoto.engine.ai.Difficulty
import com.scorchedphoto.engine.tanks.TankShape

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
)

val TANK_COLOR_PALETTE: List<Int> = listOf(
    0xFFE53935L.toInt(),
    0xFF1E88E5L.toInt(),
    0xFF43A047L.toInt(),
    0xFFF9A825L.toInt(),
    0xFF8E24AAL.toInt(),
    0xFFFB8C00L.toInt(),
)
