package com.scorchedphoto.terrain

import kotlin.random.Random

/**
 * "Shape/character" knobs for [TerrainSegmenter]'s seam search - defaults reproduce the
 * historical hardcoded behavior byte-for-byte, so passing none is identical to today.
 * [random] derives a varied-but-safe style so the terrain-preview "Regenerate" action
 * yields a different boundary each tap. Correctness/safety knobs (degenerate detection,
 * minimum jump floor, sky headroom, transition window) are deliberately not here - they
 * stay fixed constants in [TerrainSegmenter], since randomizing them risks flipping a
 * currently-correct classification (e.g. all-sky vs. all-ground) on an unlucky seed.
 */
data class TerrainSegmentationStyle(
    val costEdgeWeight: Float = 0.5f,
    val costTransitionWeight: Float = 0.5f,
    val maxRowJumpFraction: Float = 0.04f,
    val stepPenaltyWeight: Float = 0.02f,
    val medianWindow: Int = 3,
) {
    companion object {
        // Must stay odd - medianFilter's window/2 half-window math assumes it.
        private val MEDIAN_WINDOW_CHOICES = intArrayOf(3, 5, 7)

        fun random(rng: Random): TerrainSegmentationStyle {
            val edgeWeight = rng.nextDouble(0.35, 0.65).toFloat()
            return TerrainSegmentationStyle(
                costEdgeWeight = edgeWeight,
                // Sum locked at 1.0: keeps the per-pixel cost range fixed at [0,1] so
                // stepPenaltyWeight's relative strength doesn't drift, and neither signal
                // is ever fully zeroed out.
                costTransitionWeight = 1f - edgeWeight,
                maxRowJumpFraction = rng.nextDouble(0.03, 0.05).toFloat(),
                // Lower bound pinned at today's value, so a randomized result is never
                // jaggier than today - only ever equal-or-smoother.
                stepPenaltyWeight = rng.nextDouble(0.02, 0.05).toFloat(),
                medianWindow = MEDIAN_WINDOW_CHOICES[rng.nextInt(MEDIAN_WINDOW_CHOICES.size)],
            )
        }
    }
}
