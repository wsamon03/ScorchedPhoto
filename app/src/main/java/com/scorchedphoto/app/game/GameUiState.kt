package com.scorchedphoto.app.game

import com.scorchedphoto.engine.MatchPhase

data class TankHudInfo(
    val id: Int,
    val name: String,
    val color: Int,
    val health: Int,
    val alive: Boolean,
)

/**
 * Low-frequency, UI-relevant snapshot of [com.scorchedphoto.engine.GameEngine] state.
 * Published by [GameViewModel] for the Compose HUD to collect - never the mutable engine
 * objects themselves, since those are touched every physics tick on the render thread.
 */
data class GameUiState(
    val phase: MatchPhase = MatchPhase.AIMING,
    val currentTankId: Int? = null,
    val tanks: List<TankHudInfo> = emptyList(),
    val windVelocity: Float = 0f,
    val winnerOwnerId: Int? = null,
)
