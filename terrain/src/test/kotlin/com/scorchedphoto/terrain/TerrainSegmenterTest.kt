package com.scorchedphoto.terrain

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class TerrainSegmenterTest {

    @Test
    fun `finds the ground boundary in a clear sky-over-ground photo`() {
        val width = 200
        val height = 200
        val skyRows = 80 // horizon at 40% down the frame

        val map = TerrainSegmenter.segment(horizonBuffer(width, height, skyRows))

        val average = map.groundY.average()
        assertTrue(
            "expected average groundY near $skyRows, was $average",
            abs(average - skyRows) < 25,
        )
        // Every column should agree reasonably closely - it's a flat horizon.
        for (x in 0 until width) {
            assertTrue(
                "column $x groundY=${map.groundY[x]} too far from $skyRows",
                abs(map.groundY[x] - skyRows) < 30,
            )
        }
    }

    @Test
    fun `all-sky photo falls back to procedural terrain instead of leaving no ground`() {
        val width = 200
        val height = 200
        val map = TerrainSegmenter.segment(uniformBuffer(width, height, skyColor()), seed = 5L)

        val average = map.groundY.average()
        // The fallback generator centers around 65% down the frame; a "no fallback
        // triggered" all-sky result would instead collapse to the very bottom edge.
        assertTrue(
            "expected fallback terrain near 65% of height, average was $average",
            average > height * 0.45 && average < height * 0.85,
        )
    }

    @Test
    fun `all-ground photo is treated as fully solid, not a degenerate case`() {
        val width = 200
        val height = 200
        val map = TerrainSegmenter.segment(uniformBuffer(width, height, groundColor()))

        // Every column should be (close to) fully solid: groundY pinned at the sky
        // headroom clamp, nowhere near the fallback generator's ~65%-down band.
        val average = map.groundY.average()
        assertTrue(
            "expected near-fully-solid terrain (low groundY), average was $average",
            average < height * 0.2,
        )
    }

    @Test
    fun `boundary is smooth - no wild jumps between adjacent columns`() {
        val width = 300
        val height = 200
        val map = TerrainSegmenter.segment(horizonBuffer(width, height, skyRows = 90))

        for (x in 1 until width) {
            val delta = abs(map.groundY[x] - map.groundY[x - 1])
            assertTrue("large jump at column $x: $delta", delta < 15)
        }
    }

    @Test
    fun `works for images larger than the analysis resolution`() {
        val width = 1280
        val height = 720
        val skyRows = 300

        val map = TerrainSegmenter.segment(horizonBuffer(width, height, skyRows))

        assertTrue(map.width == width && map.height == height)
        val average = map.groundY.average()
        assertTrue(
            "expected average groundY near $skyRows, was $average",
            abs(average - skyRows) < 60,
        )
    }

    @Test
    fun `single column image does not crash`() {
        val map = TerrainSegmenter.segment(horizonBuffer(width = 1, height = 50, skyRows = 20))
        assertTrue(map.width == 1)
    }

    @Test
    fun `result stays within playable sky headroom bounds`() {
        val width = 200
        val height = 200
        val map = TerrainSegmenter.segment(uniformBuffer(width, height, groundColor()))

        val minAllowed = (height * 0.08).toInt()
        assertTrue(map.groundY.all { it >= minAllowed })
    }

    @Test
    fun `tracks a sloped boundary instead of only ever finding a flat line`() {
        val width = 200
        val height = 200
        // Max slope ~1.9px/column, well under the DP's row-jump budget (~8px/column at
        // this resolution) - this is the core "does it actually follow the shape"
        // assertion the seam-search redesign exists for.
        val (buffer, expectedBoundary) = slopedHorizonBuffer(width, height, baseY = 100, amplitude = 30, cycles = 2.0)

        val map = TerrainSegmenter.segment(buffer)

        var totalAbsoluteError = 0.0
        for (x in 0 until width) {
            val error = abs(map.groundY[x] - expectedBoundary[x])
            totalAbsoluteError += error
            assertTrue("column $x groundY=${map.groundY[x]} too far from expected ${expectedBoundary[x]}", error < 15)
        }
        val meanAbsoluteError = totalAbsoluteError / width
        assertTrue("mean absolute error $meanAbsoluteError too high", meanAbsoluteError < 8)
    }

    @Test
    fun `textured ground does not pull the boundary or misfire as noise`() {
        val width = 200
        val height = 200
        val skyRows = 80
        val buffer = texturedGroundHorizonBuffer(width, height, skyRows, noiseSeed = 42L)

        val map = TerrainSegmenter.segment(buffer)

        val average = map.groundY.average()
        assertTrue(
            "expected average groundY near $skyRows despite ground texture, was $average",
            abs(average - skyRows) < 25,
        )
    }

    @Test
    fun `bright low-saturation ground under a brighter sky is not merged into one region`() {
        // Under the old fixed threshold (luminance > 0.62) both this sky (~0.98) and
        // this ground (~0.71) would pass the sky-like test and collapse into one region.
        val width = 200
        val height = 200
        val skyRows = 80
        val buffer = brightGroundHorizonBuffer(width, height, skyRows)

        val map = TerrainSegmenter.segment(buffer)

        val average = map.groundY.average()
        assertTrue(
            "expected average groundY near $skyRows, was $average (adaptive threshold likely not splitting bright-on-bright)",
            abs(average - skyRows) < 25,
        )
    }

    @Test
    fun `adjacent-column deltas stay bounded on non-trivial input`() {
        val width = 200
        val height = 200
        val fixtures = listOf(
            slopedHorizonBuffer(width, height, baseY = 100, amplitude = 30, cycles = 2.0).first,
            texturedGroundHorizonBuffer(width, height, skyRows = 80, noiseSeed = 7L),
            brightGroundHorizonBuffer(width, height, skyRows = 80),
        )

        for (buffer in fixtures) {
            val map = TerrainSegmenter.segment(buffer)
            for (x in 1 until width) {
                val delta = abs(map.groundY[x] - map.groundY[x - 1])
                assertTrue("large jump at column $x: $delta", delta < 15)
            }
        }
    }

    @Test
    fun `injected sky signal provider drives the boundary, not pixel content`() {
        val width = 200
        val height = 200
        // A single flat color has ~zero real Sobel edges anywhere - if the result still
        // tracks this sloped boundary, the DP/cost-map plumbing is following the injected
        // provider rather than the (edge-less) pixel content.
        val boundary = IntArray(width) { x ->
            (100 + 30 * sin(2.0 * Math.PI * 2.0 * x / width)).roundToInt().coerceIn(0, height - 1)
        }
        val buffer = uniformBuffer(width, height, groundColor())

        val map = TerrainSegmenter.segment(buffer, skySignalProvider = FakeSkySignalProvider(boundary))

        var totalAbsoluteError = 0.0
        for (x in 0 until width) {
            totalAbsoluteError += abs(map.groundY[x] - boundary[x])
        }
        val meanAbsoluteError = totalAbsoluteError / width
        assertTrue("mean absolute error $meanAbsoluteError too high", meanAbsoluteError < 8)
    }

    @Test
    fun `different sky signal providers on the same buffer produce different boundaries`() {
        val width = 200
        val height = 200
        val buffer = uniformBuffer(width, height, groundColor())

        val lowBoundary = IntArray(width) { 30 }
        val highBoundary = IntArray(width) { 160 }

        val lowMap = TerrainSegmenter.segment(buffer, skySignalProvider = FakeSkySignalProvider(lowBoundary))
        val highMap = TerrainSegmenter.segment(buffer, skySignalProvider = FakeSkySignalProvider(highBoundary))

        assertTrue(
            "expected the two providers to produce clearly different average groundY",
            highMap.groundY.average() - lowMap.groundY.average() > 50,
        )
    }
}
