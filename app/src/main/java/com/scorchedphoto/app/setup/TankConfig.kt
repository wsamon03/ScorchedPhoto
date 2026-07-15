package com.scorchedphoto.app.setup

import com.scorchedphoto.engine.ai.Difficulty

data class TankConfig(
    val name: String,
    val color: Int,
    val isCpu: Boolean,
    val difficulty: Difficulty = Difficulty.MEDIUM,
)

data class MatchConfig(val tankConfigs: List<TankConfig>)

val TANK_COLOR_PALETTE: List<Int> = listOf(
    0xFFE53935L.toInt(),
    0xFF1E88E5L.toInt(),
    0xFF43A047L.toInt(),
    0xFFF9A825L.toInt(),
    0xFF8E24AAL.toInt(),
    0xFFFB8C00L.toInt(),
)
