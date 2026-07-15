package com.scorchedphoto.terrain

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural terrain used when [TerrainSegmenter] can't find a coherent ground/sky
 * boundary in a photo (e.g. an overexposed sky-only shot). Deterministic for a given
 * seed so results are reproducible/testable.
 */
object FallbackTerrainGenerator {

    private const val BASE_Y_FRACTION = 0.65
    private const val AMPLITUDE_1_FRACTION = 0.06
    private const val AMPLITUDE_2_FRACTION = 0.03
    private const val TAU = 2.0 * PI

    fun generateProcedural(width: Int, height: Int, seed: Long): IntArray {
        val rnd = Random(seed)
        val baseY = height * BASE_Y_FRACTION
        val amplitude1 = height * AMPLITUDE_1_FRACTION
        val amplitude2 = height * AMPLITUDE_2_FRACTION
        val freq1 = rnd.nextDouble(1.5, 3.0)
        val freq2 = rnd.nextDouble(4.0, 7.0)
        val phase1 = rnd.nextDouble(0.0, TAU)
        val phase2 = rnd.nextDouble(0.0, TAU)
        return IntArray(width) { x ->
            val t = x.toDouble() / width
            (baseY + amplitude1 * sin(t * freq1 * TAU + phase1) + amplitude2 * sin(t * freq2 * TAU + phase2))
                .roundToInt()
                .coerceIn(0, height - 1)
        }
    }
}
