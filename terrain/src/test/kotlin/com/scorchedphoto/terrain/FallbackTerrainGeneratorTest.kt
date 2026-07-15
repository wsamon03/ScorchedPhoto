package com.scorchedphoto.terrain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackTerrainGeneratorTest {

    @Test
    fun `same seed produces identical terrain`() {
        val a = FallbackTerrainGenerator.generateProcedural(width = 200, height = 100, seed = 42L)
        val b = FallbackTerrainGenerator.generateProcedural(width = 200, height = 100, seed = 42L)
        assertArrayEquals(a, b)
    }

    @Test
    fun `different seeds produce different terrain`() {
        val a = FallbackTerrainGenerator.generateProcedural(width = 200, height = 100, seed = 1L)
        val b = FallbackTerrainGenerator.generateProcedural(width = 200, height = 100, seed = 2L)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `all values stay within image bounds`() {
        val terrain = FallbackTerrainGenerator.generateProcedural(width = 500, height = 300, seed = 7L)
        assertTrue(terrain.all { it in 0 until 300 })
    }

    @Test
    fun `terrain hovers around the expected base line`() {
        val height = 200
        val terrain = FallbackTerrainGenerator.generateProcedural(width = 400, height = height, seed = 99L)
        val average = terrain.average()
        // Base line is 65% down the frame; sine amplitudes are small relative to height.
        assertTrue(average > height * 0.5 && average < height * 0.8)
    }
}
