package com.scorchedphoto.engine.tanks

import com.scorchedphoto.terrain.HeightMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Picks x-positions for [count] tanks along the terrain. Divides the usable width into
 * one slot per tank and keeps each tank's final position strictly inside its own slot,
 * so slots (and therefore tanks) never overlap regardless of the local flattening search
 * below; a randomized starting point within the slot, nudged toward flatter ground,
 * keeps placement from looking perfectly evenly spaced.
 */
object TankPlacement {

    fun placeX(terrain: HeightMap, count: Int, rng: Random = Random.Default): List<Int> {
        require(count > 0) { "count must be positive" }

        val margin = max(1, terrain.width / 20)
        val usableWidth = (terrain.width - 2 * margin).coerceAtLeast(count)
        val slotWidth = usableWidth / count

        return (0 until count).map { i ->
            val slotStart = margin + i * slotWidth
            val slotEnd = if (i == count - 1) margin + usableWidth else slotStart + slotWidth
            val slotHi = (slotEnd - 1).coerceAtLeast(slotStart)
            val candidate = if (slotHi > slotStart) rng.nextInt(slotStart, slotHi + 1) else slotStart
            findFlattestWithinSlot(terrain, candidate, slotStart, slotHi)
        }
    }

    private fun findFlattestWithinSlot(terrain: HeightMap, center: Int, slotLo: Int, slotHi: Int): Int {
        var bestX = center.coerceIn(slotLo, slotHi)
        var bestSlope = Int.MAX_VALUE
        for (x in slotLo..slotHi) {
            val left = terrain.heightAt(max(0, x - 1))
            val right = terrain.heightAt(min(terrain.width - 1, x + 1))
            val slope = abs(right - left)
            if (slope < bestSlope) {
                bestSlope = slope
                bestX = x
            }
        }
        return bestX
    }
}
