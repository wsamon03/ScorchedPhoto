package com.scorchedphoto.terrain

private const val SKY_RGB = (235 shl 16) or (235 shl 8) or 235
private const val GROUND_RGB = (60 shl 16) or (90 shl 8) or 40

private fun opaque(rgb: Int) = (0xFF shl 24) or rgb

/** Uniform "sky" color: bright and unsaturated, passes the sky-like feature checks. */
fun skyColor() = opaque(SKY_RGB)

/** Uniform "ground" color: dark and saturated, fails the sky-like feature checks. */
fun groundColor() = opaque(GROUND_RGB)

fun uniformBuffer(width: Int, height: Int, color: Int): PixelBuffer =
    PixelBuffer(width, height, IntArray(width * height) { color })

/** Sky color in the top [skyRows] rows, ground color below. */
fun horizonBuffer(width: Int, height: Int, skyRows: Int): PixelBuffer {
    val argb = IntArray(width * height)
    for (y in 0 until height) {
        val color = if (y < skyRows) skyColor() else groundColor()
        for (x in 0 until width) {
            argb[y * width + x] = color
        }
    }
    return PixelBuffer(width, height, argb)
}
