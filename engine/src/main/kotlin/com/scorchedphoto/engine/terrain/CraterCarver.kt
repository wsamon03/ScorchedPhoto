package com.scorchedphoto.engine.terrain

import com.scorchedphoto.terrain.HeightMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Carves a semicircular crater into a [HeightMap] in place. Only ever lowers solidity
 * (groundY only increases, never decreases) and clamps to a maximum depth so a crater
 * can never dig all the way through to the bottom of the frame. When the explosion is
 * genuinely embedded - deeper than every column's own original surface across the whole
 * blast radius (e.g. a deep detonation under a hill - currently only ceiling
 * [com.scorchedphoto.engine.EdgeType.WRAP], which teleports well below any real terrain
 * height), the terrain resting above it collapses straight down to fill the hole - by
 * exactly the hole's own height there, or less if not enough material exists above it -
 * rather than simply being erased down to the impact depth (see [carve]'s body). That
 * choice is made once per explosion, not independently per column: comparing each column's
 * own, possibly noisy/undulating (or, at a real wall/cliff, discontinuous) original height
 * against the impact depth let the two formulas interleave column-by-column across a single
 * crater, producing sharp discontinuities - straight edges and right-angle corners - instead
 * of the smooth circular hollow an ordinary surface explosion should leave.
 */
object CraterCarver {

    private const val MAX_DEPTH_FRACTION = 0.95f

    fun carve(terrain: HeightMap, impactX: Int, impactY: Int, radius: Int) {
        if (radius <= 0) return
        val minColumn = max(0, impactX - radius)
        val maxColumn = min(terrain.width - 1, impactX + radius)
        val flooredMaxY = (terrain.height * MAX_DEPTH_FRACTION).roundToInt()
        var changed = false

        // See the class doc for why this is decided once for the whole explosion, rather than
        // per column. Uses the deepest (largest groundY) original surface anywhere in the
        // blast's column range, not just the exact impact column - a real wall/cliff can put
        // the impact column itself on the shallow side of a step even though the blast is an
        // ordinary lateral hit, not a genuine embedded detonation (a fast shallow shot into a
        // vertical wall, for instance, can land exactly on the far/shallow side of the step).
        // Only when impactY is deeper than literally every column's own original surface in
        // range is every column actually embedded in what was already solid ground - the
        // ceiling-WRAP case this branch exists for.
        var deepestGroundYInRange = Int.MIN_VALUE
        for (x in minColumn..maxColumn) {
            if (terrain.groundY[x] > deepestGroundYInRange) deepestGroundYInRange = terrain.groundY[x]
        }
        val isDeepImpact = impactY > deepestGroundYInRange

        for (x in minColumn..maxColumn) {
            val dx = x - impactX
            val remainingSquared = radius * radius - dx * dx
            if (remainingSquared < 0) continue
            val craterDepth = sqrt(remainingSquared.toFloat())
            val explosionBottomY = (impactY + craterDepth).roundToInt()
            val existingGroundY = terrain.groundY[x]

            val newGroundY = if (isDeepImpact) {
                // There's solid terrain resting above the hole with nothing left to hold it
                // up. That block drops straight down by exactly how tall the explosion is
                // here, capped by however much material actually exists above it (never
                // more) - so the hole fills exactly, or only partially if there wasn't enough
                // terrain above to fill it completely. Clamped to >= 0: the impact-column-wide
                // decision above no longer guarantees every column's own surface sits above
                // impactY the way a per-column check used to - not reachable by the real
                // ceiling-WRAP case (impactY there is far deeper than any realistic column's
                // surface), but keeps "terrain only ever lowers" airtight regardless.
                val explosionHeight = explosionBottomY - impactY
                val solidAboveSize = max(0, impactY - existingGroundY)
                existingGroundY + min(explosionHeight, solidAboveSize)
            } else {
                // The explosion directly overlaps/touches the existing surface - an ordinary
                // crater, carved straight down from wherever the ground already was.
                max(existingGroundY, explosionBottomY)
            }
            // coerceAtLeast first, then coerceAtMost (not a single coerceIn): terrain outside
            // this class's own control can already sit below flooredMaxY's usual depth (e.g. a
            // large scripted ground drop), and coerceIn throws if its lower bound ends up
            // greater than its upper one - "never lower than existingGroundY" wins in that case,
            // simply leaving the depth floor unable to raise it back up.
            val clampedGroundY = newGroundY.coerceAtLeast(existingGroundY).coerceAtMost(flooredMaxY)
            if (clampedGroundY != existingGroundY) {
                terrain.groundY[x] = clampedGroundY
                changed = true
            }
        }
        if (changed) terrain.version++
    }
}
