package com.scorchedphoto.app.ml

import android.util.Log
import com.scorchedphoto.terrain.ClassicalSkySignalProvider
import com.scorchedphoto.terrain.PixelBuffer
import com.scorchedphoto.terrain.SkySignalProvider
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FallbackSkySignal"

/**
 * The [SkySignalProvider] actually wired into [com.scorchedphoto.terrain.TerrainSegmenter]:
 * tries the ML model first, degrading to the classical heuristic if the model is
 * missing or fails to run, so a broken/absent asset never crashes segmentation.
 */
@Singleton
class FallbackSkySignalProvider @Inject constructor(
    private val mlProvider: SkyModelSignalProvider,
) : SkySignalProvider {
    override fun skyLikelihood(buffer: PixelBuffer): Array<FloatArray> {
        return try {
            mlProvider.skyLikelihood(buffer)
        } catch (e: SkyModelUnavailableException) {
            Log.w(TAG, "ML sky model unavailable, falling back to classical segmentation", e)
            ClassicalSkySignalProvider.skyLikelihood(buffer)
        }
    }
}
