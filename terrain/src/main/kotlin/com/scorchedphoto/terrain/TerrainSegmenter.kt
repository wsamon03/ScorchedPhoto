package com.scorchedphoto.terrain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns a photo into a [HeightMap] by finding, per column, where solid ground gives
 * way to sky/backdrop.
 *
 * Algorithm:
 *  1. Downscale for analysis (speed).
 *  2. Sobel edge magnitude, plus a soft per-pixel "sky-likelihood" score supplied by
 *     an injected [SkySignalProvider] - classical luminance/saturation heuristics by
 *     default, or a real ML model on Android (see `:app`'s `ml` package).
 *  3. A regional transition score (sky-like above, non-sky-like below, in a small
 *     window) that identifies genuine sky/ground transitions and lets internal ground
 *     texture (grass, gravel) be told apart from the real boundary.
 *  4. Combine edge magnitude and transition score into a per-pixel cost map, and find
 *     the single minimum-cost path across the whole image via dynamic programming (a
 *     "seam search", the same family of technique as seam carving / horizon-line
 *     detection) - this is what makes the result a single globally coherent boundary
 *     that hugs real edges, instead of independent per-column decisions.
 *  5. Degenerate-case detection (heuristic genuinely found no ground anywhere) ->
 *     procedural fallback terrain.
 *  6. Light median smoothing (structural coherence is already handled by the DP's
 *     bounded step cost) and sky-headroom clamping.
 *  7. Upscale the boundary back to the working image's resolution.
 */
object TerrainSegmenter {

    private const val ANALYSIS_LONG_EDGE = 320

    private const val TRANSITION_WINDOW = 4

    private const val MIN_ROW_JUMP = 3

    private const val ALL_SKY_MEAN_LIKELIHOOD_THRESHOLD = 0.85f
    private const val ALL_SKY_MEAN_TRANSITION_THRESHOLD = 0.15f

    private const val MIN_SKY_HEADROOM_FRACTION = 0.08f

    fun segment(
        buffer: PixelBuffer,
        seed: Long = 0L,
        skySignalProvider: SkySignalProvider = ClassicalSkySignalProvider,
        style: TerrainSegmentationStyle = TerrainSegmentationStyle(),
    ): HeightMap {
        val analysis = boxDownscale(buffer, ANALYSIS_LONG_EDGE)
        val edges = sobelEdgeMagnitude(analysis)

        val skyLikelihood = skySignalProvider.skyLikelihood(analysis)
        val transition = transitionScoreMap(skyLikelihood, analysis.width, analysis.height)
        val cost = buildCostMap(edges, transition, analysis.width, analysis.height, style)

        val maxJump = max(MIN_ROW_JUMP, (analysis.height * style.maxRowJumpFraction).roundToInt())
        val rawPath = findMinCostPath(cost, analysis.width, analysis.height, maxJump, style.stepPenaltyWeight)

        val corrected = if (isDegenerateAllSky(skyLikelihood, transition, rawPath, analysis.width, analysis.height)) {
            FallbackTerrainGenerator.generateProcedural(analysis.width, analysis.height, seed)
        } else {
            rawPath
        }

        val smoothed = smooth(corrected, analysis.width, analysis.height, style.medianWindow)
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

    private fun windowAverage(map: Array<FloatArray>, x: Int, yStart: Int, yEnd: Int, height: Int): Float {
        val lo = max(0, yStart)
        val hi = min(height - 1, yEnd)
        if (lo > hi) return 0f
        var sum = 0f
        for (y in lo..hi) sum += map[y][x]
        return sum / (hi - lo + 1)
    }

    /**
     * How much a row looks like a genuine sky-to-ground transition: sky-like in a small
     * window above, non-sky-like in a small window below. This is what tells a real
     * boundary apart from texture/noise edges sitting entirely inside one region.
     */
    private fun transitionScoreMap(skyLikelihood: Array<FloatArray>, width: Int, height: Int): Array<FloatArray> {
        return Array(height) { y ->
            FloatArray(width) { x ->
                val above = windowAverage(skyLikelihood, x, y - TRANSITION_WINDOW, y - 1, height)
                val below = 1f - windowAverage(skyLikelihood, x, y, y + TRANSITION_WINDOW - 1, height)
                (above * below).coerceIn(0f, 1f)
            }
        }
    }

    private fun buildCostMap(
        edges: Array<FloatArray>,
        transition: Array<FloatArray>,
        width: Int,
        height: Int,
        style: TerrainSegmentationStyle,
    ): Array<FloatArray> {
        return Array(height) { y ->
            FloatArray(width) { x ->
                style.costEdgeWeight * (1f - edges[y][x]) + style.costTransitionWeight * (1f - transition[y][x])
            }
        }
    }

    /**
     * Minimum-cost path from the leftmost to the rightmost column, restricted to move
     * at most [maxJump] rows between adjacent columns (a soft penalty, not a hard wall,
     * so the path only spends its jump budget where a real edge/transition justifies
     * it). This is what makes the boundary globally coherent by construction instead of
     * independent per-column guesses patched together afterward.
     */
    private fun findMinCostPath(cost: Array<FloatArray>, width: Int, height: Int, maxJump: Int, stepPenaltyWeight: Float): IntArray {
        // dp/back are indexed [x][y] (column-major, matching the left-to-right DP sweep);
        // cost/edges/transition are indexed [y][x] (row-major, matching how they're built
        // as Array(height) { FloatArray(width) }) - every cost-map access below must flip
        // the index order accordingly.
        val dp = Array(width) { FloatArray(height) }
        val back = Array(width) { IntArray(height) }
        for (y in 0 until height) dp[0][y] = cost[y][0]

        for (x in 1 until width) {
            for (y in 0 until height) {
                var bestPrev = Float.MAX_VALUE
                var bestPy = y
                for (dy in -maxJump..maxJump) {
                    val py = y + dy
                    if (py < 0 || py >= height) continue
                    val candidate = dp[x - 1][py] + stepPenaltyWeight * abs(dy)
                    if (candidate < bestPrev) {
                        bestPrev = candidate
                        bestPy = py
                    }
                }
                dp[x][y] = cost[y][x] + bestPrev
                back[x][y] = bestPy
            }
        }

        var bestY = 0
        var bestFinal = Float.MAX_VALUE
        for (y in 0 until height) {
            if (dp[width - 1][y] < bestFinal) {
                bestFinal = dp[width - 1][y]
                bestY = y
            }
        }

        val path = IntArray(width)
        path[width - 1] = bestY
        for (x in width - 2 downTo 0) path[x] = back[x + 1][path[x + 1]]
        return path
    }

    /**
     * True only when the image overall looks strongly sky-like AND the DP couldn't
     * anchor to any real transition anywhere along its chosen path - the second
     * condition is what tells "genuinely no ground in this photo" apart from "mostly
     * sky, but there's a real (thin) ground strip DP correctly latched onto."
     */
    private fun isDegenerateAllSky(
        skyLikelihood: Array<FloatArray>,
        transition: Array<FloatArray>,
        path: IntArray,
        width: Int,
        height: Int,
    ): Boolean {
        var likelihoodSum = 0.0
        for (row in skyLikelihood) for (v in row) likelihoodSum += v
        val meanSkyLikelihood = likelihoodSum / (width.toLong() * height)

        var transitionSum = 0f
        for (x in path.indices) transitionSum += transition[path[x]][x]
        val meanTransitionAlongPath = transitionSum / path.size

        return meanSkyLikelihood >= ALL_SKY_MEAN_LIKELIHOOD_THRESHOLD &&
            meanTransitionAlongPath < ALL_SKY_MEAN_TRANSITION_THRESHOLD
    }

    private fun smooth(boundary: IntArray, width: Int, height: Int, medianWindow: Int): IntArray {
        val median = medianFilter(boundary, medianWindow)
        val minAllowedY = (height * MIN_SKY_HEADROOM_FRACTION).roundToInt()
        return IntArray(width) { median[it].coerceIn(minAllowedY, height - 1) }
    }

    private fun medianFilter(values: IntArray, window: Int): IntArray {
        val half = window / 2
        return IntArray(values.size) { i ->
            val lo = max(0, i - half)
            val hi = min(values.size - 1, i + half)
            values.copyOfRange(lo, hi + 1).sorted()[(hi - lo) / 2]
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
