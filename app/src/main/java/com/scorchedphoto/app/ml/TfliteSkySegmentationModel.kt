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
 * Runs the bundled ADE20K-scene-parsing TFLite model and extracts a per-pixel
 * "background" likelihood map - not just the literal sky class, but the sum of a
 * curated set of enclosing/background-like classes ([BACKGROUND_CLASS_INDICES]: sky,
 * wall, ceiling, door, window, curtain). A single sky class only covers outdoor
 * landscape photos; summing this broader set is what lets an indoor photo (e.g. toys
 * on a table against a wall) still produce a sensible boundary - the wall registers as
 * background, the table/toys don't.
 *
 * [MODEL_INPUT_SIZE]/[MODEL_OUTPUT_SIZE], [NUM_CLASSES] and [BACKGROUND_CLASS_INDICES]
 * describe the currently bundled model's actual I/O contract - they must be
 * re-checked against that model's own metadata/label map (e.g. via Netron) whenever
 * the asset is replaced, not assumed from the generic SceneParse150 convention.
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
    fun predictBackgroundLikelihood(buffer: PixelBuffer): Array<FloatArray> {
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
            FloatArray(MODEL_OUTPUT_SIZE) { x ->
                var backgroundProbability = 0f
                for (classIndex in BACKGROUND_CLASS_INDICES) {
                    backgroundProbability += output[0][y][x].getOrElse(classIndex) { 0f }
                }
                backgroundProbability
            }
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
        const val NUM_THREADS = 4
        const val INPUT_MEAN = 0f
        const val INPUT_STD = 255f

        // ADE20K SceneParse150 classes treated as "background" (open air / an enclosing
        // surface) rather than "ground" (an object, or the surface it rests on).
        // Indices follow the standard 1-indexed 150-class label order
        // (https://github.com/CSAILVision/sceneparsing/blob/master/objectInfo150.csv)
        // with output channel 0 reserved for background/void, i.e. class N's
        // probability is at channel index N. Re-verify this whole list against the
        // actual bundled model's own label map once sourced - do not assume it
        // blindly matches every ADE20K export.
        val BACKGROUND_CLASS_INDICES = intArrayOf(
            3, // sky
            1, // wall
            6, // ceiling
            15, // door;double door
            9, // windowpane;window
            19, // curtain;drape;drapery;mantle;pall
        )
    }
}
