package com.scorchedphoto.engine.physics

import kotlin.random.Random

// A full independent re-roll each turn let wind swing wildly turn to turn (e.g. a strong
// left gust could be immediately followed by an almost-as-strong right gust, since a
// fresh uniform draw has no relation to the previous value). Stepping by at most this
// fraction of maxMagnitude instead keeps wind changing turn to turn without those wild
// flips, while still reaching the full range over a few turns.
private const val REROLL_MAX_STEP_FRACTION = 0.35f

class Wind(var velocity: Float = 0f) {

    /**
     * Nudges toward a new signed value, at most [REROLL_MAX_STEP_FRACTION] of
     * [maxMagnitude] away from the current one, clamped to [-maxMagnitude, maxMagnitude].
     */
    fun reroll(maxMagnitude: Float, rng: Random) {
        val maxStep = maxMagnitude * REROLL_MAX_STEP_FRACTION
        val delta = rng.nextFloat() * 2f * maxStep - maxStep
        velocity = (velocity + delta).coerceIn(-maxMagnitude, maxMagnitude)
    }

    companion object {
        fun random(maxMagnitude: Float, rng: Random): Wind = Wind().apply { reroll(maxMagnitude, rng) }
    }
}
