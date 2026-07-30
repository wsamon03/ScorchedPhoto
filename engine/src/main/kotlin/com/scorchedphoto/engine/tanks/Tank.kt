package com.scorchedphoto.engine.tanks

import com.scorchedphoto.engine.ai.Difficulty
import com.scorchedphoto.engine.combat.WeaponType

data class Tank(
    val id: Int,
    val ownerId: Int,
    val name: String,
    val color: Int,
    val isCpu: Boolean,
    var x: Float,
    var y: Float,
    var health: Int = MAX_HEALTH,
    var angleDeg: Float = 45f,
    var power: Float = 50f,
    var currentWeapon: WeaponType = WeaponType.STANDARD_SHELL,
    var alive: Boolean = true,
    var falling: Boolean = false,
    var fallVelocity: Float = 0f,
    var burning: Boolean = false,
    var burningElapsed: Float = 0f,
    var pendingBurn: Boolean = false,
    // The beat between the burn animation ending and the final death explosion - see
    // GameEngine.updateAwaitingExplosion.
    var awaitingExplosion: Boolean = false,
    var awaitingExplosionElapsed: Float = 0f,
    // The death explosion's own growth phase, kept visually in sync with
    // GameRenderer.GROWTH_SECONDS - see GameEngine.updateExploding.
    var exploding: Boolean = false,
    var explodingElapsed: Float = 0f,
    // Set once the death explosion fires; permanent for the rest of the match - the
    // renderer draws an ash pile in place of the tank body from then on.
    var isAsh: Boolean = false,
    // Set when this tank settles onto a column with no floor left at all (FloorType.HOLE/
    // WRAP/VOID) - see GameEngine.killByFallingThroughFloor. No burn/explosion/ash animation
    // plays; the renderer draws only this tank's usual death-taunt speech bubble, anchored at
    // fallThroughAnchorY, for as long as this stays true (see
    // GameEngine.updateFallingThroughFloor).
    var fallingThroughFloor: Boolean = false,
    var fallThroughElapsed: Float = 0f,
    var fallThroughAnchorY: Float = 0f,
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val shape: TankShape = TankShape.CLASSIC,
) {
    companion object {
        const val MAX_HEALTH = 100

        /**
         * Half-width of the tank body / direct-hit collision radius, in the same
         * working-image pixel scale as [com.scorchedphoto.engine.physics.GRAVITY] etc.
         * Shared by rendering (`GameRenderer`) and hit detection (`GameEngine`) so the
         * two always agree on how big a tank actually is - was 14f, 4x'd to 56f, halved
         * back down to 28f, halved to 14f, halved again to 7f.
         */
        const val RADIUS = 7f
    }
}
