package com.scorchedphoto.engine.physics

import kotlin.random.Random

class Wind(var velocity: Float = 0f) {

    /** Re-rolls to a new signed value in [-maxMagnitude, maxMagnitude]. */
    fun reroll(maxMagnitude: Float, rng: Random) {
        velocity = rng.nextFloat() * 2f * maxMagnitude - maxMagnitude
    }

    companion object {
        fun random(maxMagnitude: Float, rng: Random): Wind = Wind().apply { reroll(maxMagnitude, rng) }
    }
}
