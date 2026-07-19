package com.scorchedphoto.app.ml

import com.scorchedphoto.terrain.PixelBuffer
import com.scorchedphoto.terrain.SkySignalProvider
import javax.inject.Inject

/** Adapts [TfliteSkySegmentationModel] to [SkySignalProvider]. */
class SkyModelSignalProvider @Inject constructor(
    private val model: TfliteSkySegmentationModel,
) : SkySignalProvider {
    override fun skyLikelihood(buffer: PixelBuffer): Array<FloatArray> = model.predictSkyLikelihood(buffer)
}
