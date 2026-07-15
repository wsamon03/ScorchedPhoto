package com.scorchedphoto.terrain

/**
 * Per-column ground-surface height. groundY[x] is the row where solid ground begins:
 * rows with y >= groundY[x] are solid, rows with y < groundY[x] are open air/sky.
 * This is the literal destructible-terrain representation used by both the segmentation
 * output and the physics/crater-carving engine - there is no separate intermediate form.
 */
class HeightMap(val width: Int, val height: Int, val groundY: IntArray) {

    init {
        require(groundY.size == width) {
            "groundY.size (${groundY.size}) must equal width ($width)"
        }
    }

    fun heightAt(x: Int): Int = groundY[x.coerceIn(0, width - 1)]

    fun isSolid(x: Int, y: Int): Boolean = y >= heightAt(x)
}
