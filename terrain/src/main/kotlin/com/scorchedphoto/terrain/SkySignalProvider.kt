package com.scorchedphoto.terrain

/**
 * Supplies the per-pixel "how much does this look like sky" signal that
 * [TerrainSegmenter] turns into a boundary. Kept as a seam so the classical
 * heuristic (this module, pure JVM) and a real ML model (Android-side, in
 * `:app`) can be swapped in without touching the DP/cost-map machinery.
 */
interface SkySignalProvider {
    /**
     * Per-pixel sky likelihood in `[0,1]`. Result is indexed `[y][x]` and
     * must match [buffer]'s width/height exactly.
     */
    fun skyLikelihood(buffer: PixelBuffer): Array<FloatArray>
}
