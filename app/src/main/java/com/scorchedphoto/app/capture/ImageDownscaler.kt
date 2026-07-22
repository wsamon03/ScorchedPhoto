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

        return scaleToLongEdge(corrected, WORKING_LONG_EDGE)
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
