package com.scorchedphoto.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HeightMapTest {

    @Test
    fun `isSolid is true at and below the ground row`() {
        val map = HeightMap(width = 4, height = 10, groundY = IntArray(4) { 5 })
        assertTrue(map.isSolid(0, 5))
        assertTrue(map.isSolid(0, 9))
        assertFalse(map.isSolid(0, 4))
        assertFalse(map.isSolid(0, 0))
    }

    @Test
    fun `heightAt clamps out of range columns`() {
        val map = HeightMap(width = 4, height = 10, groundY = intArrayOf(1, 2, 3, 4))
        assertEquals(1, map.heightAt(-5))
        assertEquals(4, map.heightAt(100))
    }

    @Test
    fun `mismatched groundY size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            HeightMap(width = 4, height = 10, groundY = IntArray(3))
        }
    }
}
