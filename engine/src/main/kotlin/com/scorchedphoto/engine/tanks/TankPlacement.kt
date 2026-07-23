package com.scorchedphoto.engine.tanks

import com.scorchedphoto.terrain.HeightMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Picks x-positions for [count] tanks anywhere along the terrain - no fixed left-to-right
 * ordering tying tank index to horizontal position, just [MIN_GAP] of clearance between
 * every pair so tank bodies (each [Tank.RADIUS] wide) never overlap. Rejection-sampled:
 * for each tank in turn, retry random candidates until one clears every already-placed
 * tank, then nudge it toward flatter nearby ground without ever giving up that clearance.
 */
object TankPlacement {

    // A full tank diameter - the closest two tank centers can ever end up while their
    // bodies (each Tank.RADIUS wide) still avoid touching.
    private val MIN_GAP = (2 * Tank.RADIUS).toInt().coerceAtLeast(1)
    private const val MAX_SAMPLE_ATTEMPTS = 200
    private const val FLATTEN_WINDOW = 10

    fun placeX(terrain: HeightMap, count: Int, rng: Random = Random.Default): List<Int> {
        require(count > 0) { "count must be positive" }

        val margin = max(1, terrain.width / 20)
        val lo = margin
        val hi = (terrain.width - margin - 1).coerceAtLeast(lo)

        val placed = mutableListOf<Int>()
        repeat(count) {
            val candidate = sampleCandidate(lo, hi, placed, rng)
            placed += findFlattestNearby(terrain, candidate, lo, hi, placed)
        }
        return placed
    }

    /**
     * Uniform rejection sampling for an x that clears every already-placed tank by
     * [MIN_GAP]. Falls back to whichever x in range is farthest from its nearest
     * neighbor if the space is packed tight enough that random sampling can't find a
     * clean spot within [MAX_SAMPLE_ATTEMPTS] - only reachable with an unusually narrow
     * map or unusually large tank count, never in normal play.
     */
    private fun sampleCandidate(lo: Int, hi: Int, placed: List<Int>, rng: Random): Int {
        repeat(MAX_SAMPLE_ATTEMPTS) {
            val candidate = if (hi > lo) rng.nextInt(lo, hi + 1) else lo
            if (placed.none { abs(it - candidate) < MIN_GAP }) return candidate
        }
        return (lo..hi).maxByOrNull { x -> placed.minOfOrNull { abs(it - x) } ?: Int.MAX_VALUE } ?: lo
    }

    /** Nudges [center] toward the flattest ground within [FLATTEN_WINDOW] of it, never
     * moving to an x that's closer than [MIN_GAP] to any already-placed tank. */
    private fun findFlattestNearby(terrain: HeightMap, center: Int, lo: Int, hi: Int, placed: List<Int>): Int {
        var bestX = center
        var bestSlope = Int.MAX_VALUE
        for (x in max(lo, center - FLATTEN_WINDOW)..min(hi, center + FLATTEN_WINDOW)) {
            if (placed.any { abs(it - x) < MIN_GAP }) continue
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
