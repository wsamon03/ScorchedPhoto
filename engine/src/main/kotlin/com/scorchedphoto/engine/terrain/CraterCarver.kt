package com.scorchedphoto.engine.terrain

import com.scorchedphoto.terrain.HeightMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Carves a semicircular crater into a [HeightMap] in place. Only ever lowers solidity
 * (groundY only increases, never decreases) and clamps to a maximum depth so a crater
 * can never dig all the way through to the bottom of the frame.
 */
object CraterCarver {

    private const val MAX_DEPTH_FRACTION = 0.95f

    fun carve(terrain: HeightMap, impactX: Int, impactY: Int, radius: Int) {
        if (radius <= 0) return
        val minColumn = max(0, impactX - radius)
        val maxColumn = min(terrain.width - 1, impactX + radius)
        val flooredMaxY = (terrain.height * MAX_DEPTH_FRACTION).roundToInt()

        for (x in minColumn..maxColumn) {
            val dx = x - impactX
            val remainingSquared = radius * radius - dx * dx
            if (remainingSquared < 0) continue
            val craterDepth = sqrt(remainingSquared.toFloat())
            val candidate = (impactY + craterDepth).roundToInt()
            terrain.groundY[x] = max(terrain.groundY[x], candidate).coerceAtMost(flooredMaxY)
        }
    }
}
