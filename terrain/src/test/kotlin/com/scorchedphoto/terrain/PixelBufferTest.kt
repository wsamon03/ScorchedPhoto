package com.scorchedphoto.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelBufferTest {

    @Test
    fun `white pixel has high luminance and zero saturation`() {
        val buffer = uniformBuffer(4, 4, opaqueWhite())
        assertTrue(buffer.luminance(0, 0) > 0.95f)
        assertEquals(0f, buffer.saturation(0, 0), 1e-6f)
    }

    @Test
    fun `black pixel has zero luminance`() {
        val buffer = uniformBuffer(4, 4, opaqueBlack())
        assertEquals(0f, buffer.luminance(0, 0), 1e-6f)
    }

    @Test
    fun `saturated color has high saturation`() {
        val red = (0xFF shl 24) or (0xFF shl 16)
        val buffer = uniformBuffer(4, 4, red)
        assertEquals(1f, buffer.saturation(0, 0), 1e-6f)
    }

    @Test
    fun `out of bounds coordinates clamp instead of throwing`() {
        val buffer = uniformBuffer(4, 4, opaqueWhite())
        // Should not throw despite negative / overflowing coordinates.
        buffer.luminance(-5, -5)
        buffer.luminance(100, 100)
    }

    @Test
    fun `mismatched array size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PixelBuffer(4, 4, IntArray(10))
        }
    }

    private fun opaqueWhite() = (0xFF shl 24) or 0xFFFFFF
    private fun opaqueBlack() = (0xFF shl 24)
}
