package com.scorchedphoto.app.game

import com.scorchedphoto.engine.MatchPhase
import com.scorchedphoto.engine.combat.WeaponType

data class TankHudInfo(
    val id: Int,
    val name: String,
    val color: Int,
    val health: Int,
    val alive: Boolean,
)

/** Ammo remaining for a weapon; null means unlimited. */
data class WeaponHudInfo(val weaponType: WeaponType, val ammoRemaining: Int?, val selected: Boolean)

/**
 * Low-frequency, UI-relevant snapshot of [com.scorchedphoto.engine.GameEngine] state.
 * Published by [GameViewModel] for the Compose HUD to collect - never the mutable engine
 * objects themselves, since those are touched every physics tick on the render thread.
 */
data class GameUiState(
    val phase: MatchPhase = MatchPhase.AIMING,
    val currentTankId: Int? = null,
    val currentTankIsCpu: Boolean = false,
    val currentAngleDeg: Float = 45f,
    val currentPower: Float = 50f,
    /** The current tank's actual power ceiling (see [com.scorchedphoto.engine.physics.maxPowerForHealth]) - less than 100 once it's taken damage. */
    val currentMaxPower: Float = 100f,
    /** Current tank's world position, for the [com.scorchedphoto.app.game.hud.AngleRing] overlay. */
    val currentTankX: Float = 0f,
    val currentTankY: Float = 0f,
    val terrainWidth: Int = 1,
    val terrainHeight: Int = 1,
    val weapons: List<WeaponHudInfo> = emptyList(),
    val tanks: List<TankHudInfo> = emptyList(),
    val windVelocity: Float = 0f,
    val winnerOwnerId: Int? = null,
)
