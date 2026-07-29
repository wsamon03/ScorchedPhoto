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

    /** Bumped by whatever mutates [groundY] (e.g. `CraterCarver.carve` in `:engine`) each time it
     * actually changes, so callers that cache work derived from the terrain (e.g. a rendered
     * outline) can cheaply detect "did this change since I last looked" with a single Int
     * comparison instead of re-deriving or re-scanning [groundY] every time. Public (not
     * `internal`) since terrain mutation happens in `:engine`, a separate Gradle module from
     * this one. */
    var version: Int = 0

    fun heightAt(x: Int): Int = groundY[x.coerceIn(0, width - 1)]

    fun isSolid(x: Int, y: Int): Boolean = y >= heightAt(x)
}
