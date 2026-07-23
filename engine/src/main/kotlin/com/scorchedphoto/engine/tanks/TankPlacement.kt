package com.scorchedphoto.engine.tanks

import com.scorchedphoto.terrain.HeightMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Picks x-positions for [count] tanks along the terrain. Divides the usable width into
 * one slot per tank and keeps each tank's final position strictly inside its own slot,
 * so slots never overlap regardless of the local flattening search below; a randomized
 * starting point within the slot, nudged toward flatter ground, keeps placement from
 * looking perfectly evenly spaced. Every slot but the last also reserves [MIN_GAP] at its
 * trailing edge (see below) so tanks themselves - not just their slots - never overlap.
 */
object TankPlacement {

    // A full tank diameter: even in the worst case, where one tank lands at the very
    // edge of its search range and the next lands at the very start of its own slot,
    // this is the closest their centers can end up - guaranteeing their bodies (each
    // Tank.RADIUS wide) never overlap.
    private val MIN_GAP = (2 * Tank.RADIUS).toInt().coerceAtLeast(1)

    fun placeX(terrain: HeightMap, count: Int, rng: Random = Random.Default): List<Int> {
        require(count > 0) { "count must be positive" }

        val margin = max(1, terrain.width / 20)
        val usableWidth = (terrain.width - 2 * margin).coerceAtLeast(count)
        val slotWidth = usableWidth / count

        return (0 until count).map { i ->
            val slotStart = margin + i * slotWidth
            val slotEnd = if (i == count - 1) margin + usableWidth else slotStart + slotWidth
            val slotHi = (slotEnd - 1).coerceAtLeast(slotStart)
            // Reserving MIN_GAP off the trailing edge of every slot but the last means
            // this tank's furthest possible position and the next slot's earliest
            // possible position are always at least MIN_GAP apart, regardless of where
            // within each (reduced) range the flattest-ground search actually lands.
            val searchHi = if (i == count - 1) slotHi else (slotHi - MIN_GAP).coerceAtLeast(slotStart)
            val candidate = if (searchHi > slotStart) rng.nextInt(slotStart, searchHi + 1) else slotStart
            findFlattestWithinSlot(terrain, candidate, slotStart, searchHi)
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
