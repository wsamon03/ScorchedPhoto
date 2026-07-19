package com.scorchedphoto.terrain

/**
 * Deterministic [SkySignalProvider] driven entirely by an explicit boundary row per
 * column, ignoring the buffer's actual pixel content. Lets tests prove
 * [TerrainSegmenter]'s cost-map/DP plumbing correctly follows whatever signal it's
 * given, independent of the classical Otsu/sigmoid heuristics.
 */
class FakeSkySignalProvider(private val boundary: IntArray) : SkySignalProvider {
    override fun skyLikelihood(buffer: PixelBuffer): Array<FloatArray> {
        require(boundary.size == buffer.width) {
            "boundary size ${boundary.size} must match buffer width ${buffer.width}"
        }
        return Array(buffer.height) { y ->
            FloatArray(buffer.width) { x -> if (y < boundary[x]) 1f else 0f }
        }
    }
}
