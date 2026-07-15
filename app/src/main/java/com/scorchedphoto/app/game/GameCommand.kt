package com.scorchedphoto.app.game

import com.scorchedphoto.engine.combat.WeaponType

/**
 * Discrete intents from the Compose HUD (main thread) into the running match. Queued by
 * [GameViewModel] and drained/applied by [GameLoopThread] once per tick, so only the loop
 * thread ever mutates [com.scorchedphoto.engine.GameEngine] state.
 */
sealed interface GameCommand {
    data class SetAngle(val angleDeg: Float) : GameCommand
    data class SetPower(val power: Float) : GameCommand
    data class SetWeapon(val weaponType: WeaponType) : GameCommand
    data object Fire : GameCommand
}
