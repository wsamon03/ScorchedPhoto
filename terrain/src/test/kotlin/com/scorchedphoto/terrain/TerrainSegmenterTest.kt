package com.scorchedphoto.terrain

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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
}
