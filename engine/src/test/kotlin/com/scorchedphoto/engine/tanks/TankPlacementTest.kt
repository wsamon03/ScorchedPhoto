package com.scorchedphoto.engine.tanks

import com.scorchedphoto.terrain.HeightMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TankPlacementTest {

    private fun flatTerrain(width: Int, groundY: Int = 100) = HeightMap(width, 200, IntArray(width) { groundY })

    @Test
    fun `no two tanks ever overlap, in any left-right order`() {
        val minGap = (2 * Tank.RADIUS).toInt()
        repeat(50) { seed ->
            val terrain = flatTerrain(width = 500)
            val positions = TankPlacement.placeX(terrain, count = 6, rng = Random(seed.toLong()))
            for (i in positions.indices) {
                for (j in i + 1 until positions.size) {
                    assertTrue(
                        "seed=$seed tanks overlap (need >= $minGap apart): $positions",
                        kotlin.math.abs(positions[i] - positions[j]) >= minGap,
                    )
                }
            }
        }
    }

    @Test
    fun `placements are not confined to per-tank slots in index order`() {
        // With a fixed seed, at least one run out of many should land tank 0 to the
        // right of tank 1 - proof placement isn't secretly still assigning tanks a
        // fixed left-to-right slot by index.
        val sawOutOfOrder = (0 until 50).any { seed ->
            val terrain = flatTerrain(width = 500)
            val positions = TankPlacement.placeX(terrain, count = 6, rng = Random(seed.toLong()))
            positions[0] > positions[1]
        }
        assertTrue("tank 0 was never placed to the right of tank 1 across 50 seeds", sawOutOfOrder)
    }

    @Test
    fun `all positions stay within terrain bounds`() {
        val terrain = flatTerrain(width = 300)
        val positions = TankPlacement.placeX(terrain, count = 4, rng = Random(1))
        assertTrue(positions.all { it in 0 until 300 })
    }

    @Test
    fun `single tank is placed somewhere reasonable`() {
        val terrain = flatTerrain(width = 300)
        val positions = TankPlacement.placeX(terrain, count = 1, rng = Random(1))
        assertEquals(1, positions.size)
        assertTrue(positions[0] in 0 until 300)
    }

    @Test
    fun `prefers flatter ground nearby over a spike`() {
        val width = 100
        val groundY = IntArray(width) { 100 }
        // A single-column spike right in the middle of the map.
        groundY[50] = 20
        val terrain = HeightMap(width, 200, groundY)

        val positions = TankPlacement.placeX(terrain, count = 1, rng = Random(1))
        assertTrue("landed on the spike: $positions", positions[0] != 50)
    }
}
