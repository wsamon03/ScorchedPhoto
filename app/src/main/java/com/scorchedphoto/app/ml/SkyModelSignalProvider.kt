package com.scorchedphoto.app.ml

import com.scorchedphoto.terrain.PixelBuffer
import com.scorchedphoto.terrain.SkySignalProvider
import javax.inject.Inject

/**
 * Adapts [TfliteSkySegmentationModel] to [SkySignalProvider]: the terrain module's
 * "sky likelihood" is, from the model's side, a broader "background" likelihood
 * (sky/wall/ceiling/door/window/curtain) - see [TfliteSkySegmentationModel]'s doc.
 */
class SkyModelSignalProvider @Inject constructor(
    private val model: TfliteSkySegmentationModel,
) : SkySignalProvider {
    override fun skyLikelihood(buffer: PixelBuffer): Array<FloatArray> = model.predictBackgroundLikelihood(buffer)
}
