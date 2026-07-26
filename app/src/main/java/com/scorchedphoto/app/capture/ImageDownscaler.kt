package com.scorchedphoto.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.scorchedphoto.terrain.PixelBuffer
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val WORKING_LONG_EDGE = 720

/** Bitmap <-> :terrain's platform-agnostic PixelBuffer, kept as the only Android-aware seam. */
object ImageDownscaler {

    fun loadDownscaledAndCorrected(context: Context, uri: Uri): Bitmap {
        val (width, height) = readBounds(context, uri)
        val sampleSize = calculateInSampleSize(width, height, WORKING_LONG_EDGE)

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val sampled = openInputStream(context, uri) { input -> BitmapFactory.decodeStream(input, null, options) }
            ?: error("Unable to decode image at $uri")

        val orientation = readExifOrientation(context, uri)
        val corrected = applyExifOrientation(sampled, orientation)
        val cropped = cropToScreenAspectRatio(context, corrected)

        return scaleToLongEdge(cropped, WORKING_LONG_EDGE)
    }

    fun toPixelBuffer(bitmap: Bitmap): PixelBuffer {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return PixelBuffer(bitmap.width, bitmap.height, pixels)
    }

    private fun readBounds(context: Context, uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openInputStream(context, uri) { input -> BitmapFactory.decodeStream(input, null, options) }
        return options.outWidth to options.outHeight
    }

    private fun calculateInSampleSize(width: Int, height: Int, targetLongEdge: Int): Int {
        val longEdge = max(width, height)
        var sampleSize = 1
        while (longEdge / (sampleSize * 2) >= targetLongEdge) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Center-crops [bitmap] to the device's own landscape aspect ratio, *before* anything
     * downstream (terrain segmentation, tank placement across the terrain's width) ever
     * sees it. Without this, a source photo whose own aspect ratio doesn't match the
     * landscape-locked game screen's segments a terrain shaped like the photo, not the
     * screen - and [com.scorchedphoto.app.game.WorldTransform]'s uniform-scale "cover" fit
     * (see its doc) then has to crop the *rendered* terrain to actually fill the screen,
     * potentially cutting away a large share of the real playable battlefield - tanks
     * placed across the terrain's full original width could end up entirely outside the
     * visible screen. Cropping here instead means the terrain is already shaped like the
     * screen, so that render-time crop never needs to remove more than a sliver.
     */
    private fun cropToScreenAspectRatio(context: Context, bitmap: Bitmap): Bitmap {
        val metrics = context.resources.displayMetrics
        val longEdgePx = max(metrics.widthPixels, metrics.heightPixels)
        val shortEdgePx = min(metrics.widthPixels, metrics.heightPixels)
        if (shortEdgePx <= 0) return bitmap
        val targetAspect = longEdgePx.toFloat() / shortEdgePx // width:height, landscape
        val currentAspect = bitmap.width.toFloat() / bitmap.height

        return when {
            currentAspect > targetAspect -> {
                val newWidth = (bitmap.height * targetAspect).roundToInt().coerceIn(1, bitmap.width)
                val x = (bitmap.width - newWidth) / 2
                Bitmap.createBitmap(bitmap, x, 0, newWidth, bitmap.height)
            }
            currentAspect < targetAspect -> {
                val newHeight = (bitmap.width / targetAspect).roundToInt().coerceIn(1, bitmap.height)
                val y = (bitmap.height - newHeight) / 2
                Bitmap.createBitmap(bitmap, 0, y, bitmap.width, newHeight)
            }
            else -> bitmap
        }
    }

    private fun scaleToLongEdge(bitmap: Bitmap, targetLongEdge: Int): Bitmap {
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= targetLongEdge) return bitmap
        val scale = targetLongEdge.toFloat() / longEdge
        val newWidth = max(1, (bitmap.width * scale).roundToInt())
        val newHeight = max(1, (bitmap.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun readExifOrientation(context: Context, uri: Uri): Int {
        return openInputStream(context, uri) { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun <T> openInputStream(context: Context, uri: Uri, block: (InputStream) -> T): T? {
        return context.contentResolver.openInputStream(uri)?.use(block)
    }
}
