package com.scorchedphoto.terrain

/**
 * Platform-agnostic RGBA pixel buffer (no android.graphics.Bitmap dependency), so
 * segmentation stays testable on a plain JVM with no Android SDK.
 */
class PixelBuffer(val width: Int, val height: Int, private val argb: IntArray) {

    init {
        require(argb.size == width * height) {
            "argb.size (${argb.size}) must equal width*height (${width * height})"
        }
    }

    private fun clampX(x: Int) = x.coerceIn(0, width - 1)
    private fun clampY(y: Int) = y.coerceIn(0, height - 1)

    fun pixel(x: Int, y: Int): Int = argb[clampY(y) * width + clampX(x)]

    fun luminance(x: Int, y: Int): Float {
        val p = pixel(x, y)
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }

    fun saturation(x: Int, y: Int): Float {
        val p = pixel(x, y)
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max == 0) return 0f
        return (max - min).toFloat() / max.toFloat()
    }
}
