package com.scorchedphoto.engine.terrain

import com.scorchedphoto.terrain.HeightMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Carves a circular blast into a [HeightMap] in place, one column at a time, using a single
 * rule with no special-casing between an ordinary surface hit and a deep/embedded one:
 * - The blast at column `x` destroys everything from `impactY - craterDepth(x)` down to
 *   `impactY + craterDepth(x)` (a full circle, not just its lower half).
 * - Whatever solid terrain is left resting above that hole - none of it, for an ordinary
 *   surface hit, since the top of the circle is already above the original surface there -
 *   drops straight down to close the gap, by exactly however much was destroyed beneath it.
 * Expressed as a single clamp (see [carve]'s body) rather than two branches picked between
 * per column or per explosion: `groundY[x]` only ever moves to somewhere between its own
 * current value (an ordinary hit reaching nowhere near it: unchanged) and that same value
 * plus the hole's full height there (an ordinary hit whose circle bottom lands beyond it, or
 * a genuinely embedded hit: the hole closes completely). This is what makes an explosion on
 * undulating terrain read as one smooth circular hollow instead of jagged, right-angled
 * edges - the old per-column/per-explosion branch between two differently-shaped formulas
 * (kept around only for a genuinely embedded case, e.g. ceiling
 * [com.scorchedphoto.engine.EdgeType.WRAP]) disagreed at the point a column crossed between
 * them. Only ever lowers solidity (groundY only increases, never decreases) and clamps to a
 * maximum depth (see [carve]'s `maxGroundY` param, defaulting to [defaultMaxGroundY] - a thin
 * strip of terrain always survives) so a crater can never dig all the way through to the bottom
 * of the frame, unless a caller explicitly passes a looser bound (e.g. GameEngine.floorMaxGroundY
 * for a FloorType that allows a blast to fully open the floor).
 */
object CraterCarver {

    private const val MAX_DEPTH_FRACTION = 0.95f

    /** The default floor clamp - a thin strip of terrain always survives at the very bottom
     * of the map. Callers that want a floor type to allow a blast to fully hollow a column
     * down to the map's true bottom (e.g. GameEngine.floorMaxGroundY for any FloorType other
     * than GROUND/WATER/LAVA) pass their own [carve] `maxGroundY` instead of this default. */
    fun defaultMaxGroundY(terrain: HeightMap): Int = (terrain.height * MAX_DEPTH_FRACTION).roundToInt()

    fun carve(terrain: HeightMap, impactX: Int, impactY: Int, radius: Int, maxGroundY: Int = defaultMaxGroundY(terrain)) {
        if (radius <= 0) return
        val minColumn = max(0, impactX - radius)
        val maxColumn = min(terrain.width - 1, impactX + radius)
        var changed = false

        for (x in minColumn..maxColumn) {
            val dx = x - impactX
            val remainingSquared = radius * radius - dx * dx
            if (remainingSquared < 0) continue
            val craterDepth = sqrt(remainingSquared.toFloat())
            val explosionBottomY = (impactY + craterDepth).roundToInt()
            val existingGroundY = terrain.groundY[x]

            // The blast's full vertical extent at this column (top to bottom of the circle,
            // not just its lower half) - the most this column's surface can possibly drop by,
            // whether that's ordinary erosion or material collapsing to fill a hole beneath it.
            val holeHeight = (2f * craterDepth).roundToInt()

            // Below existingGroundY: the blast doesn't reach this column at all (unchanged).
            // Above existingGroundY + holeHeight: the blast's circle here started strictly
            // below the original surface, so once destroyed there's nothing left to hold up
            // the material that was resting above it - that material drops to close the gap,
            // filling the hole completely (capped here by the hole's own full height, since
            // there's always at least that much solid material above it in that case - the
            // original surface itself sat below the circle's top for this branch to apply).
            // In between (the ordinary case: the circle's top is already above the original
            // surface, nothing to collapse): this reduces to plain erosion down to
            // explosionBottomY, identical to the old "ordinary" formula.
            val newGroundY = explosionBottomY.coerceIn(existingGroundY, existingGroundY + holeHeight)

            // coerceAtLeast(existingGroundY) guards the "never raises terrain" invariant against
            // a caller-supplied maxGroundY that's tighter than a column's already-carved depth
            // (e.g. FloorType.LAVA's own depth-capped regrowth encountering a column an ordinary,
            // less-restricted explosion already dug past that cap) - without it, coerceAtMost
            // alone could pull an already-deeper column back up to the tighter bound.
            val clampedGroundY = newGroundY.coerceAtMost(maxGroundY).coerceAtLeast(existingGroundY)
            if (clampedGroundY != existingGroundY) {
                terrain.groundY[x] = clampedGroundY
                changed = true
            }
        }
        if (changed) terrain.version++
    }
}
