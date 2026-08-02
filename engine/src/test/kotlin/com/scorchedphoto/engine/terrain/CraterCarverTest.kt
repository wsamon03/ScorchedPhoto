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

    @Test
    fun `a genuinely embedded explosion collapses the material above down to fill the hole completely`() {
        // The blast's own circle at the center column spans 290..310 (impactY +- radius) -
        // entirely below the original surface (100), so this column is genuinely embedded:
        // everything from 100 to 310 gets destroyed, and there's plenty of material above
        // (100..290) to fully close the resulting hole. It drops by exactly the hole's own
        // full height (2 * radius = 20), landing at 100 + 20 = 120 - never further, however
        // much material actually exists above it.
        val terrain = flatTerrain(width = 20, height = 1000, groundY = 100)
        CraterCarver.carve(terrain, impactX = 5, impactY = 300, radius = 10)

        assertEquals(120, terrain.groundY[5])
    }

    @Test
    fun `terrain whose surface sits below the blast's own top erodes directly, not via collapse`() {
        // The blast's own circle at the center column still spans 290..310, but this
        // column's original surface (295) already sits below the circle's top (290) - i.e.
        // there's no material resting above the hole to collapse, even though the surface is
        // itself below the impact point (300). This is exactly the case the old per-column/
        // per-explosion "is this column embedded" check used to misjudge, producing jagged
        // edges on ordinary undulating terrain: the single unified formula instead resolves
        // it as plain erosion straight down to the circle's own bottom (310), identical to an
        // ordinary surface hit.
        val terrain = flatTerrain(width = 20, height = 1000, groundY = 295)
        CraterCarver.carve(terrain, impactX = 5, impactY = 300, radius = 10)

        assertEquals(310, terrain.groundY[5])
    }

    @Test
    fun `an explicit maxGroundY of the terrain's own height lets a blast fully open a column`() {
        // A floor type that allows the floor to be fully hollowed (e.g. GameEngine.floorMaxGroundY
        // for FloorType.HOLE) passes terrain.height itself as maxGroundY - no clamp at all.
        val terrain = flatTerrain(width = 20, height = 200, groundY = 100)
        CraterCarver.carve(terrain, impactX = 5, impactY = 190, radius = 60, maxGroundY = terrain.height)

        assertEquals(200, terrain.groundY[5])
    }

    @Test
    fun `omitting maxGroundY still clamps to the default 95 percent floor`() {
        val terrain = flatTerrain(width = 100, height = 200, groundY = 100)
        CraterCarver.carve(terrain, impactX = 50, impactY = 190, radius = 60)
        val flooredMax = (200 * 0.95f).roundToInt()
        assertTrue(terrain.groundY.all { it <= flooredMax })
    }

    @Test
    fun `a maxGroundY tighter than a column's already-carved depth never raises it back up`() {
        // Simulates FloorType.LAVA's own depth-capped regrowth carve() call landing on a column
        // an earlier, less-restricted explosion already dug past that cap - the tighter bound
        // here (120) must not pull the column's already-deeper surface (150) back up to it.
        val terrain = flatTerrain(width = 20, height = 200, groundY = 150)
        CraterCarver.carve(terrain, impactX = 10, impactY = 190, radius = 30, maxGroundY = 120)

        assertEquals(150, terrain.groundY[10])
    }

    // --- fill(): the mirror-image operation Earthmover uses ------------------------------------

    @Test
    fun `fill raises a semicircular mound profile centered on the impact column`() {
        val terrain = flatTerrain(width = 100, height = 200, groundY = 100)
        CraterCarver.fill(terrain, impactX = 50, impactY = 100, radius = 20)

        assertEquals(80, terrain.groundY[50]) // full mound height directly under impact
        val expectedAt10Away = 100 - sqrt((20 * 20 - 10 * 10).toDouble()).roundToInt()
        assertEquals(expectedAt10Away, terrain.groundY[60])
        // Outside the radius, terrain is untouched.
        assertEquals(100, terrain.groundY[75])
    }

    @Test
    fun `fill never lowers terrain, only raises it`() {
        val terrain = flatTerrain(width = 100, height = 200, groundY = 50)
        val before = terrain.groundY.copyOf()
        // Fill somewhere already deep underground (well below groundY) - should have no effect.
        CraterCarver.fill(terrain, impactX = 50, impactY = 190, radius = 5)
        for (x in terrain.groundY.indices) {
            assertTrue(terrain.groundY[x] <= before[x])
        }
    }

    @Test
    fun `repeated fills accumulate without ever lowering terrain`() {
        val terrain = flatTerrain(width = 100, height = 200, groundY = 150)
        CraterCarver.fill(terrain, impactX = 50, impactY = 150, radius = 15)
        val afterFirst = terrain.groundY.copyOf()
        CraterCarver.fill(terrain, impactX = 52, impactY = 150, radius = 15)
        for (x in terrain.groundY.indices) {
            assertTrue(terrain.groundY[x] <= afterFirst[x])
        }
    }

    @Test
    fun `fill height is clamped to the min-height ceiling`() {
        val terrain = flatTerrain(width = 100, height = 200, groundY = 100)
        CraterCarver.fill(terrain, impactX = 50, impactY = 10, radius = 60)
        val ceilingMin = (200 * 0.05f).roundToInt()
        assertTrue(terrain.groundY.all { it >= ceilingMin })
    }

    @Test
    fun `mound near the edge of the map does not throw`() {
        val terrain = flatTerrain(width = 100, height = 200, groundY = 100)
        CraterCarver.fill(terrain, impactX = 0, impactY = 100, radius = 30)
        CraterCarver.fill(terrain, impactX = 99, impactY = 100, radius = 30)
        assertTrue(terrain.groundY.all { it in 0 until 200 })
    }

    @Test
    fun `fill with zero or negative radius is a no-op`() {
        val terrain = flatTerrain(width = 20, height = 50, groundY = 25)
        val before = terrain.groundY.copyOf()
        CraterCarver.fill(terrain, impactX = 10, impactY = 25, radius = 0)
        CraterCarver.fill(terrain, impactX = 10, impactY = 25, radius = -5)
        assertEquals(before.toList(), terrain.groundY.toList())
    }

    @Test
    fun `an explicit minGroundY of 0 lets a mound rise to the very top of the map`() {
        val terrain = flatTerrain(width = 20, height = 200, groundY = 100)
        CraterCarver.fill(terrain, impactX = 5, impactY = 10, radius = 60, minGroundY = 0)

        assertEquals(0, terrain.groundY[5])
    }

    @Test
    fun `a minGroundY looser than a column's already-raised height never lowers it back down`() {
        // Simulates a second, less-restricted fill() call landing on a column an earlier call
        // already raised past a tighter cap - the looser bound here (80) must not pull the
        // column's already-higher surface (50) back down to it.
        val terrain = flatTerrain(width = 20, height = 200, groundY = 50)
        CraterCarver.fill(terrain, impactX = 10, impactY = 10, radius = 30, minGroundY = 80)

        assertEquals(50, terrain.groundY[10])
    }
}
