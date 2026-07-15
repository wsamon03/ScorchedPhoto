package com.scorchedphoto.terrain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns a photo into a [HeightMap] by finding, per column, where solid ground gives
 * way to sky/backdrop. Pure classical image processing - no ML, no network - so it
 * runs entirely on-device and is unit-testable with synthetic pixel buffers.
 *
 * Algorithm, see the project plan for the full rationale:
 *  1. Downscale for analysis (speed).
 *  2. Per-pixel luminance/saturation/Sobel-edge-magnitude features.
 *  3. Per-column bottom-up scan for a confirmed run of sky-like rows, refined by
 *     snapping to the nearest strong edge (real horizons/table edges coincide with a
 *     genuine gradient spike).
 *  4. Degenerate-case detection (heuristic failed) -> procedural fallback terrain.
 *  5. Median + moving-average smoothing, with sky-headroom clamping.
 *  6. Upscale the boundary back to the working image's resolution.
 */
object TerrainSegmenter {

    private const val ANALYSIS_LONG_EDGE = 320

    private const val SKY_LUMINANCE_THRESHOLD = 0.62f
    private const val SKY_SATURATION_THRESHOLD = 0.25f
    private const val SKY_EDGE_THRESHOLD = 0.12f
    private const val CONFIRM_RUN = 6
    private const val EDGE_SNAP_WINDOW = 8
    private const val EDGE_SNAP_MIN_MAGNITUDE = 0.15f

    private const val MEDIAN_WINDOW = 5
    private const val MIN_SKY_HEADROOM_FRACTION = 0.08f

    // A column whose raw boundary sits in the bottom band means the scan found almost
    // no solid ground anywhere in it (sky/noise everywhere); if most columns look like
    // that, the heuristic has failed rather than found a real close-up/all-ground shot.
    private const val ALL_SKY_ROW_BAND_FRACTION = 0.05f
    private const val ALL_SKY_COLUMN_FRACTION_THRESHOLD = 0.90f

    // Average jump between neighboring columns; real terrain silhouettes (even jagged
    // mountains) vary gradually at analysis resolution, so a very high value indicates
    // per-column noise rather than a coherent surface.
    private const val NOISE_DELTA_FRACTION = 0.15f

    fun segment(buffer: PixelBuffer, seed: Long = 0L): HeightMap {
        val analysis = boxDownscale(buffer, ANALYSIS_LONG_EDGE)
        val edges = sobelEdgeMagnitude(analysis)

        val rawBoundary = IntArray(analysis.width) { x -> scanColumn(analysis, edges, x) }
        val corrected = applyFallbackIfDegenerate(rawBoundary, analysis.width, analysis.height, seed)
        val smoothed = smooth(corrected, analysis.width, analysis.height)
        val groundY = upscaleBoundary(smoothed, analysis.width, analysis.height, buffer.width, buffer.height)

        return HeightMap(buffer.width, buffer.height, groundY)
    }

    private fun boxDownscale(buffer: PixelBuffer, targetLongEdge: Int): PixelBuffer {
        val longEdge = max(buffer.width, buffer.height)
        if (longEdge <= targetLongEdge) return buffer

        val scale = targetLongEdge.toFloat() / longEdge
        val newWidth = max(1, (buffer.width * scale).roundToInt())
        val newHeight = max(1, (buffer.height * scale).roundToInt())
        val argb = IntArray(newWidth * newHeight)

        for (ny in 0 until newHeight) {
            val ySrcStart = ny * buffer.height / newHeight
            val ySrcEnd = max(ySrcStart + 1, (ny + 1) * buffer.height / newHeight)
            for (nx in 0 until newWidth) {
                val xSrcStart = nx * buffer.width / newWidth
                val xSrcEnd = max(xSrcStart + 1, (nx + 1) * buffer.width / newWidth)

                var rSum = 0L
                var gSum = 0L
                var bSum = 0L
                var count = 0
                for (sy in ySrcStart until ySrcEnd) {
                    for (sx in xSrcStart until xSrcEnd) {
                        val p = buffer.pixel(sx, sy)
                        rSum += (p shr 16) and 0xFF
                        gSum += (p shr 8) and 0xFF
                        bSum += p and 0xFF
                        count++
                    }
                }
                val r = (rSum / count).toInt()
                val g = (gSum / count).toInt()
                val b = (bSum / count).toInt()
                argb[ny * newWidth + nx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return PixelBuffer(newWidth, newHeight, argb)
    }

    private fun sobelEdgeMagnitude(buffer: PixelBuffer): Array<FloatArray> {
        return Array(buffer.height) { y ->
            FloatArray(buffer.width) { x ->
                val tl = buffer.luminance(x - 1, y - 1)
                val tc = buffer.luminance(x, y - 1)
                val tr = buffer.luminance(x + 1, y - 1)
                val ml = buffer.luminance(x - 1, y)
                val mr = buffer.luminance(x + 1, y)
                val bl = buffer.luminance(x - 1, y + 1)
                val bc = buffer.luminance(x, y + 1)
                val br = buffer.luminance(x + 1, y + 1)

                val gx = -tl + tr - 2f * ml + 2f * mr - bl + br
                val gy = -tl - 2f * tc - tr + bl + 2f * bc + br
                (sqrt(gx * gx + gy * gy) / 8f).coerceIn(0f, 1f)
            }
        }
    }

    /**
     * Scans a column bottom-up looking for a confirmed run of sky-like rows. If none is
     * ever found the column is treated as solid ground all the way to the top (boundary
     * 0), not as "no sky" defaulting to the bottom - those two situations must produce
     * different values so the degenerate-case check below can tell them apart.
     */
    private fun scanColumn(buffer: PixelBuffer, edges: Array<FloatArray>, x: Int): Int {
        var skyLikeRun = 0
        var boundary = 0
        var found = false
        for (y in buffer.height - 1 downTo 0) {
            val skyLike = buffer.luminance(x, y) > SKY_LUMINANCE_THRESHOLD &&
                buffer.saturation(x, y) < SKY_SATURATION_THRESHOLD &&
                edges[y][x] < SKY_EDGE_THRESHOLD
            if (skyLike) {
                skyLikeRun++
                if (skyLikeRun >= CONFIRM_RUN) {
                    boundary = y + CONFIRM_RUN - 1
                    found = true
                    break
                }
            } else {
                skyLikeRun = 0
            }
        }
        return if (found) refineToNearestEdge(edges, x, boundary, buffer.height) else boundary
    }

    private fun refineToNearestEdge(edges: Array<FloatArray>, x: Int, boundary: Int, height: Int): Int {
        val lo = max(0, boundary - EDGE_SNAP_WINDOW)
        val hi = min(height - 1, boundary + EDGE_SNAP_WINDOW)
        var bestY = boundary
        var bestMagnitude = edges[boundary][x]
        for (y in lo..hi) {
            val magnitude = edges[y][x]
            if (magnitude > bestMagnitude) {
                bestMagnitude = magnitude
                bestY = y
            }
        }
        return if (bestMagnitude >= EDGE_SNAP_MIN_MAGNITUDE) bestY else boundary
    }

    private fun applyFallbackIfDegenerate(raw: IntArray, width: Int, height: Int, seed: Long): IntArray {
        val bottomBandStart = height * (1f - ALL_SKY_ROW_BAND_FRACTION)
        val allSkyFraction = raw.count { it >= bottomBandStart }.toFloat() / width

        val meanAdjacentDelta = if (width > 1) {
            (1 until width).sumOf { abs(raw[it] - raw[it - 1]) }.toFloat() / (width - 1)
        } else {
            0f
        }
        val isNoisy = meanAdjacentDelta >= height * NOISE_DELTA_FRACTION

        return if (allSkyFraction >= ALL_SKY_COLUMN_FRACTION_THRESHOLD || isNoisy) {
            FallbackTerrainGenerator.generateProcedural(width, height, seed)
        } else {
            raw
        }
    }

    private fun smooth(boundary: IntArray, width: Int, height: Int): IntArray {
        val median = medianFilter(boundary, MEDIAN_WINDOW)
        val windowSize = max(5, width / 40)
        val averaged = movingAverage(median, windowSize)
        val minAllowedY = (height * MIN_SKY_HEADROOM_FRACTION).roundToInt()
        return IntArray(width) { averaged[it].coerceIn(minAllowedY, height - 1) }
    }

    private fun medianFilter(values: IntArray, window: Int): IntArray {
        val half = window / 2
        return IntArray(values.size) { i ->
            val lo = max(0, i - half)
            val hi = min(values.size - 1, i + half)
            values.copyOfRange(lo, hi + 1).sorted()[(hi - lo) / 2]
        }
    }

    private fun movingAverage(values: IntArray, window: Int): IntArray {
        val half = window / 2
        return IntArray(values.size) { i ->
            val lo = max(0, i - half)
            val hi = min(values.size - 1, i + half)
            var sum = 0
            for (j in lo..hi) sum += values[j]
            sum / (hi - lo + 1)
        }
    }

    private fun upscaleBoundary(
        boundary: IntArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
    ): IntArray {
        val heightScale = dstHeight.toFloat() / srcHeight
        return IntArray(dstWidth) { x ->
            val srcPos = if (dstWidth <= 1) 0f else x.toFloat() * (srcWidth - 1) / (dstWidth - 1)
            val i0 = srcPos.toInt().coerceIn(0, srcWidth - 1)
            val i1 = (i0 + 1).coerceIn(0, srcWidth - 1)
            val t = srcPos - i0
            val interpolatedY = boundary[i0] * (1 - t) + boundary[i1] * t
            (interpolatedY * heightScale).roundToInt().coerceIn(0, dstHeight - 1)
        }
    }
}
