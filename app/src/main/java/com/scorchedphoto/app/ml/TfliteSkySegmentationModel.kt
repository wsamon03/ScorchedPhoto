package com.scorchedphoto.app.ml

import android.content.Context
import android.graphics.Bitmap
import com.scorchedphoto.terrain.PixelBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the bundled ADE20K-scene-parsing TFLite model and extracts the sky-class
 * channel as a per-pixel likelihood map. [MODEL_INPUT_SIZE]/[MODEL_OUTPUT_SIZE],
 * [NUM_CLASSES] and [SKY_CLASS_INDEX] describe the currently bundled model's actual
 * I/O contract - they must be re-checked against that model's own metadata/label map
 * (e.g. via Netron) whenever the asset is replaced, not assumed from the generic
 * SceneParse150 convention.
 */
@Singleton
class TfliteSkySegmentationModel @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val lock = Any()

    @Volatile
    private var interpreter: Interpreter? = null

    private val imageProcessor by lazy {
        ImageProcessor.Builder()
            .add(ResizeOp(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(INPUT_MEAN, INPUT_STD))
            .build()
    }

    /** Blocking - callers must invoke this off the main thread. */
    fun predictSkyLikelihood(buffer: PixelBuffer): Array<FloatArray> {
        synchronized(lock) {
            return try {
                val model = interpreter ?: loadInterpreter().also { interpreter = it }
                runInference(model, buffer)
            } catch (e: SkyModelUnavailableException) {
                throw e
            } catch (e: Exception) {
                throw SkyModelUnavailableException("Sky segmentation inference failed", e)
            }
        }
    }

    private fun loadInterpreter(): Interpreter {
        return try {
            val modelBuffer = FileUtil.loadMappedFile(context, MODEL_ASSET_NAME)
            Interpreter(modelBuffer, Interpreter.Options().setNumThreads(NUM_THREADS))
        } catch (e: Exception) {
            throw SkyModelUnavailableException("Failed to load $MODEL_ASSET_NAME", e)
        }
    }

    private fun runInference(interpreter: Interpreter, buffer: PixelBuffer): Array<FloatArray> {
        var tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(toBitmap(buffer))
        tensorImage = imageProcessor.process(tensorImage)

        val output = Array(1) { Array(MODEL_OUTPUT_SIZE) { Array(MODEL_OUTPUT_SIZE) { FloatArray(NUM_CLASSES) } } }
        interpreter.run(tensorImage.buffer, output)

        val modelLikelihood = Array(MODEL_OUTPUT_SIZE) { y ->
            FloatArray(MODEL_OUTPUT_SIZE) { x -> output[0][y][x].getOrElse(SKY_CLASS_INDEX) { 0f } }
        }
        return resizeLikelihood(modelLikelihood, MODEL_OUTPUT_SIZE, MODEL_OUTPUT_SIZE, buffer.width, buffer.height)
    }

    private fun toBitmap(buffer: PixelBuffer): Bitmap {
        val pixels = IntArray(buffer.width * buffer.height)
        for (y in 0 until buffer.height) {
            for (x in 0 until buffer.width) {
                pixels[y * buffer.width + x] = buffer.pixel(x, y)
            }
        }
        val bitmap = Bitmap.createBitmap(buffer.width, buffer.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, buffer.width, 0, 0, buffer.width, buffer.height)
        return bitmap
    }

    private fun resizeLikelihood(
        source: Array<FloatArray>,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
    ): Array<FloatArray> {
        return Array(dstHeight) { dy ->
            val srcY = if (dstHeight <= 1) 0f else dy.toFloat() * (srcHeight - 1) / (dstHeight - 1)
            val y0 = srcY.toInt().coerceIn(0, srcHeight - 1)
            val y1 = (y0 + 1).coerceIn(0, srcHeight - 1)
            val ty = srcY - y0
            FloatArray(dstWidth) { dx ->
                val srcX = if (dstWidth <= 1) 0f else dx.toFloat() * (srcWidth - 1) / (dstWidth - 1)
                val x0 = srcX.toInt().coerceIn(0, srcWidth - 1)
                val x1 = (x0 + 1).coerceIn(0, srcWidth - 1)
                val tx = srcX - x0
                val top = source[y0][x0] * (1 - tx) + source[y0][x1] * tx
                val bottom = source[y1][x0] * (1 - tx) + source[y1][x1] * tx
                top * (1 - ty) + bottom * ty
            }
        }
    }

    private companion object {
        const val MODEL_ASSET_NAME = "sky_segmentation.tflite"
        const val MODEL_INPUT_SIZE = 257
        const val MODEL_OUTPUT_SIZE = 257
        const val NUM_CLASSES = 151
        const val SKY_CLASS_INDEX = 3
        const val NUM_THREADS = 4
        const val INPUT_MEAN = 0f
        const val INPUT_STD = 255f
    }
}
