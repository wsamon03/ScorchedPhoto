package com.scorchedphoto.engine.terrain

import com.scorchedphoto.terrain.HeightMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Carves a semicircular crater into a [HeightMap] in place. Only ever lowers solidity
 * (groundY only increases, never decreases) and clamps to a maximum depth so a crater
 * can never dig all the way through to the bottom of the frame. When the explosion's own
 * footprint at a column starts strictly below that column's existing surface (e.g. a deep
 * detonation under a hill), the terrain resting above it collapses straight down to fill the
 * hole - by exactly the hole's own height there, or less if not enough material exists above
 * it - rather than simply being erased down to the impact depth (see [carve]'s body).
 */
object CraterCarver {

    private const val MAX_DEPTH_FRACTION = 0.95f

    fun carve(terrain: HeightMap, impactX: Int, impactY: Int, radius: Int) {
        if (radius <= 0) return
        val minColumn = max(0, impactX - radius)
        val maxColumn = min(terrain.width - 1, impactX + radius)
        val flooredMaxY = (terrain.height * MAX_DEPTH_FRACTION).roundToInt()
        var changed = false

        for (x in minColumn..maxColumn) {
            val dx = x - impactX
            val remainingSquared = radius * radius - dx * dx
            if (remainingSquared < 0) continue
            val craterDepth = sqrt(remainingSquared.toFloat())
            val explosionBottomY = (impactY + craterDepth).roundToInt()
            val existingGroundY = terrain.groundY[x]

            val newGroundY = if (existingGroundY < impactY) {
                // The explosion's footprint at this column starts (impactY) strictly below
                // the existing surface - there's solid terrain resting above the hole with
                // nothing left to hold it up. That block drops straight down by exactly how
                // tall the explosion is here, capped by however much material actually exists
                // above it (never more) - so the hole fills exactly, or only partially if
                // there wasn't enough terrain above to fill it completely.
                val explosionHeight = explosionBottomY - impactY
                val solidAboveSize = impactY - existingGroundY
                existingGroundY + min(explosionHeight, solidAboveSize)
            } else {
                // The explosion directly overlaps/touches the existing surface - an ordinary
                // crater, carved straight down from wherever the ground already was.
                max(existingGroundY, explosionBottomY)
            }
            val clampedGroundY = newGroundY.coerceAtMost(flooredMaxY)
            if (clampedGroundY != existingGroundY) {
                terrain.groundY[x] = clampedGroundY
                changed = true
            }
        }
        if (changed) terrain.version++
    }
}
