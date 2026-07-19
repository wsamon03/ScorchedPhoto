package com.scorchedphoto.terrain

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Sky likelihood from luminance/saturation alone, adaptively thresholded per
 * photo via Otsu's method. No ML, no network - the default [SkySignalProvider]
 * and the fallback used when a real model is unavailable.
 */
object ClassicalSkySignalProvider : SkySignalProvider {

    private const val SKY_LUMINANCE_DEFAULT = 0.62f
    private const val SKY_LUMINANCE_MIN = 0.45f
    private const val SKY_LUMINANCE_MAX = 0.85f
    private const val SKY_SATURATION_DEFAULT = 0.25f
    private const val SKY_SATURATION_MIN = 0.10f
    private const val SKY_SATURATION_MAX = 0.45f
    private const val OTSU_HISTOGRAM_BINS = 64
    private const val OTSU_MIN_SPREAD = 1e-3f

    private const val SKY_LUM_SOFTNESS = 0.08f
    private const val SKY_SAT_SOFTNESS = 0.08f

    override fun skyLikelihood(buffer: PixelBuffer): Array<FloatArray> {
        val luminanceValues = FloatArray(buffer.width * buffer.height) { i ->
            buffer.luminance(i % buffer.width, i / buffer.width)
        }
        val saturationValues = FloatArray(buffer.width * buffer.height) { i ->
            buffer.saturation(i % buffer.width, i / buffer.width)
        }
        val lumThreshold = adaptiveThreshold(
            luminanceValues,
            SKY_LUMINANCE_DEFAULT,
            SKY_LUMINANCE_MIN,
            SKY_LUMINANCE_MAX,
        )
        val satThreshold = adaptiveThreshold(
            saturationValues,
            SKY_SATURATION_DEFAULT,
            SKY_SATURATION_MIN,
            SKY_SATURATION_MAX,
        )

        return Array(buffer.height) { y ->
            FloatArray(buffer.width) { x ->
                val lumTerm = sigmoid((buffer.luminance(x, y) - lumThreshold) / SKY_LUM_SOFTNESS)
                val satTerm = sigmoid((satThreshold - buffer.saturation(x, y)) / SKY_SAT_SOFTNESS)
                lumTerm * satTerm
            }
        }
    }

    /**
     * Otsu's method: finds the value that best splits [values] into two classes by
     * maximizing between-class variance. Returns NaN if the values have near-zero
     * spread (a flat/uniform image), which the caller treats as "no reliable split
     * exists here, use the default constant."
     */
    private fun otsuThreshold(values: FloatArray, bins: Int = OTSU_HISTOGRAM_BINS): Float {
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (v in values) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        val range = maxV - minV
        if (range < OTSU_MIN_SPREAD) return Float.NaN

        val histogram = IntArray(bins)
        for (v in values) {
            val bin = (((v - minV) / range) * (bins - 1)).roundToInt().coerceIn(0, bins - 1)
            histogram[bin]++
        }

        val total = values.size
        var sumAll = 0.0
        for (bin in 0 until bins) sumAll += bin.toDouble() * histogram[bin]

        var sumBackground = 0.0
        var weightBackground = 0
        var bestVariance = -1.0
        var bestBin = 0
        for (bin in 0 until bins) {
            weightBackground += histogram[bin]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break

            sumBackground += bin.toDouble() * histogram[bin]
            val meanBackground = sumBackground / weightBackground
            val meanForeground = (sumAll - sumBackground) / weightForeground
            val diff = meanBackground - meanForeground
            val between = weightBackground.toDouble() * weightForeground * diff * diff
            if (between > bestVariance) {
                bestVariance = between
                bestBin = bin
            }
        }
        return minV + (bestBin.toFloat() / (bins - 1)) * range
    }

    private fun adaptiveThreshold(values: FloatArray, default: Float, lo: Float, hi: Float): Float {
        val otsu = otsuThreshold(values)
        return if (otsu.isNaN()) default else otsu.coerceIn(lo, hi)
    }

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()
}
