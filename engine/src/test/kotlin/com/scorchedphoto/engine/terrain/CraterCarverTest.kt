package com.scorchedphoto.engine.terrain

import com.scorchedphoto.terrain.HeightMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.sqrt

class CraterCarverTest {

    private fun flatTerrain(width: Int, height: Int, groundY: Int) =
        HeightMap(width, height, IntArray(width) { groundY })

    @Test
    fun `carves a semicircular profile centered on the impact column`() {
        val terrain = flatTerrain(width = 100, height = 200, groundY = 100)
        CraterCarver.carve(terrain, impactX = 50, impactY = 100, radius = 20)

        assertEquals(120, terrain.groundY[50]) // full depth directly under impact
        val expectedAt10Away = 100 + sqrt((20 * 20 - 10 * 10).toDouble()).roundToInt()
        assertEquals(expectedAt10Away, terrain.groundY[60])
        // Outside the radius, terrain is untouched.
        assertEquals(100, terrain.groundY[75])
    }

    @Test
    fun `never raises terrain, only lowers it`() {
        val terrain = flatTerrain(width = 100, height = 200, groundY = 50)
        val before = terrain.groundY.copyOf()
        // Carve somewhere already below groundY - should have no effect.
        CraterCarver.carve(terrain, impactX = 50, impactY = 10, radius = 5)
        for (x in terrain.groundY.indices) {
            assertTrue(terrain.groundY[x] >= before[x])
        }
    }

    @Test
    fun `repeated craters accumulate without ever raising terrain`() {
        val terrain = flatTerrain(width = 100, height = 200, groundY = 50)
        CraterCarver.carve(terrain, impactX = 50, impactY = 60, radius = 15)
        val afterFirst = terrain.groundY.copyOf()
        CraterCarver.carve(terrain, impactX = 52, impactY = 60, radius = 15)
        for (x in terrain.groundY.indices) {
            assertTrue(terrain.groundY[x] >= afterFirst[x])
        }
    }

    @Test
    fun `depth is clamped to the max-depth floor`() {
        val terrain = flatTerrain(width = 100, height = 200, groundY = 100)
        CraterCarver.carve(terrain, impactX = 50, impactY = 190, radius = 60)
        val flooredMax = (200 * 0.95f).roundToInt()
        assertTrue(terrain.groundY.all { it <= flooredMax })
    }

    @Test
    fun `crater near the edge of the map does not throw`() {
        val terrain = flatTerrain(width = 100, height = 200, groundY = 100)
        CraterCarver.carve(terrain, impactX = 0, impactY = 100, radius = 30)
        CraterCarver.carve(terrain, impactX = 99, impactY = 100, radius = 30)
        assertTrue(terrain.groundY.all { it in 0 until 200 })
    }

    @Test
    fun `zero or negative radius is a no-op`() {
        val terrain = flatTerrain(width = 20, height = 50, groundY = 25)
        val before = terrain.groundY.copyOf()
        CraterCarver.carve(terrain, impactX = 10, impactY = 25, radius = 0)
        CraterCarver.carve(terrain, impactX = 10, impactY = 25, radius = -5)
        assertEquals(before.toList(), terrain.groundY.toList())
    }
}
