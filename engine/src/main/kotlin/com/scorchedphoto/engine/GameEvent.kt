package com.scorchedphoto.engine

/**
 * Sound-relevant things that happened inside [GameEngine] since the last [GameEngine.drainEvents]
 * call. Kept decoupled from *how* the app layer reacts (which sound plays, whether it loops)
 * so the engine stays free of any Android/audio dependency.
 */
sealed interface GameEvent {
    /** A tank just fired a projectile. */
    data object ShotFired : GameEvent

    /** A projectile just landed and carved/damaged something. */
    data object Impact : GameEvent

    /** A tank's death animation just finished burning and let out its final blast. */
    data class TankExploded(val tankId: Int) : GameEvent
}
