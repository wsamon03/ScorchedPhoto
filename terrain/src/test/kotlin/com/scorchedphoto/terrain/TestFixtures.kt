package com.scorchedphoto.terrain

import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val SKY_RGB = (235 shl 16) or (235 shl 8) or 235
private const val GROUND_RGB = (60 shl 16) or (90 shl 8) or 40

// Both pass the *old* fixed threshold (luminance > 0.62) and would previously get merged
// into a single "sky" region - brightGroundHorizonBuffer() exercises the Otsu fix.
private const val BRIGHT_SKY_RGB = (250 shl 16) or (250 shl 8) or 250 // lum~0.98, sat=0
private const val BRIGHT_GROUND_RGB = (195 shl 16) or (180 shl 8) or 150 // lum~0.71, sat~0.23

private const val JITTERED_GROUND_BASE_R = 60
private const val JITTERED_GROUND_BASE_G = 90
private const val JITTERED_GROUND_BASE_B = 40

private fun opaque(rgb: Int) = (0xFF shl 24) or rgb

/** Uniform "sky" color: bright and unsaturated, passes the sky-like feature checks. */
fun skyColor() = opaque(SKY_RGB)

/** Uniform "ground" color: dark and saturated, fails the sky-like feature checks. */
fun groundColor() = opaque(GROUND_RGB)

fun uniformBuffer(width: Int, height: Int, color: Int): PixelBuffer =
    PixelBuffer(width, height, IntArray(width * height) { color })

/** Sky color in the top [skyRows] rows, ground color below. */
fun horizonBuffer(width: Int, height: Int, skyRows: Int): PixelBuffer {
    val argb = IntArray(width * height)
    for (y in 0 until height) {
        val color = if (y < skyRows) skyColor() else groundColor()
        for (x in 0 until width) {
            argb[y * width + x] = color
        }
    }
    return PixelBuffer(width, height, argb)
}

/**
 * Sky above a sine-wave boundary, ground below. Returns the buffer alongside the exact
 * per-column boundary row used to generate it, so a test can assert the segmenter's
 * output actually tracks a non-flat shape instead of just a single hard horizontal line.
 */
fun slopedHorizonBuffer(width: Int, height: Int, baseY: Int, amplitude: Int, cycles: Double): Pair<PixelBuffer, IntArray> {
    val boundary = IntArray(width) { x ->
        (baseY + amplitude * sin(2.0 * Math.PI * cycles * x / width)).roundToInt().coerceIn(0, height - 1)
    }
    val argb = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            argb[y * width + x] = if (y < boundary[x]) skyColor() else groundColor()
        }
    }
    return PixelBuffer(width, height, argb) to boundary
}

/**
 * Flat sky, ground with per-pixel deterministic luminance jitter simulating a textured
 * surface (grass, gravel). Tests that internal ground texture doesn't pull the boundary
 * or read as noise.
 */
fun texturedGroundHorizonBuffer(width: Int, height: Int, skyRows: Int, noiseSeed: Long, jitter: Int = 25): PixelBuffer {
    val random = Random(noiseSeed)
    val argb = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            argb[y * width + x] = if (y < skyRows) skyColor() else jitteredGroundColor(random, jitter)
        }
    }
    return PixelBuffer(width, height, argb)
}

private fun jitteredGroundColor(random: Random, jitter: Int): Int {
    val r = (JITTERED_GROUND_BASE_R + random.nextInt(-jitter, jitter + 1)).coerceIn(0, 255)
    val g = (JITTERED_GROUND_BASE_G + random.nextInt(-jitter, jitter + 1)).coerceIn(0, 255)
    val b = (JITTERED_GROUND_BASE_B + random.nextInt(-jitter, jitter + 1)).coerceIn(0, 255)
    return opaque((r shl 16) or (g shl 8) or b)
}

/**
 * Bright, low-saturation ground (e.g. sand/concrete) under a brighter sky. Under the old
 * fixed absolute thresholds (luminance > 0.62, saturation < 0.25) both regions would pass
 * the sky-like test and get merged into one region - this fixture exercises the
 * per-photo adaptive (Otsu) threshold that replaced those fixed cutoffs.
 */
fun brightGroundHorizonBuffer(width: Int, height: Int, skyRows: Int): PixelBuffer {
    val argb = IntArray(width * height)
    for (y in 0 until height) {
        val color = if (y < skyRows) opaque(BRIGHT_SKY_RGB) else opaque(BRIGHT_GROUND_RGB)
        for (x in 0 until width) {
            argb[y * width + x] = color
        }
    }
    return PixelBuffer(width, height, argb)
}
