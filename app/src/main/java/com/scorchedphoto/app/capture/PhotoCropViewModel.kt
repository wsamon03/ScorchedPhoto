package com.scorchedphoto.app.capture

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PhotoCropUiState(
    val photo: Bitmap? = null,
    val scale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
)

/**
 * Drives [PhotoCropScreen]'s pinch-zoom-and-pan crop selection: the raw, orientation-corrected
 * photo is always scaled to at least fully cover the on-screen crop frame (no blank edges),
 * and panned within whatever slack that scale leaves - see [coverScale]/[maxPan]. All state
 * here is plain (no Compose types) so the screen's gesture handling is the only place that
 * deals with [androidx.compose.ui.geometry.Offset] - mirrors how
 * [com.scorchedphoto.app.terrainpreview.TerrainPreviewScreen]'s draw gesture converts to plain
 * column/row values before calling into its ViewModel.
 */
@HiltViewModel
class PhotoCropViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PhotoCropUiState(photo = photoRepository.rawPhoto))
    val uiState: StateFlow<PhotoCropUiState> = _uiState.asStateFlow()

    private var frameWidthPx = 0f
    private var frameHeightPx = 0f
    private var frameSizeKnown = false

    /** Called once the crop frame's on-screen size is known - seeds the initial zoom/pan to
     * exactly cover the frame, centered, matching the old automatic center-crop's default
     * framing before the user drags/pinches away from it. A no-op after the first call, so a
     * later relayout can't silently discard the user's in-progress crop. */
    fun onFrameSizeChanged(widthPx: Float, heightPx: Float) {
        if (widthPx <= 0f || heightPx <= 0f || frameSizeKnown) return
        frameWidthPx = widthPx
        frameHeightPx = heightPx
        frameSizeKnown = true
        val photo = _uiState.value.photo ?: return
        _uiState.value = _uiState.value.copy(scale = coverScale(photo), panX = 0f, panY = 0f)
    }

    /** Applies one frame of a pinch/drag gesture: [zoomFactor] multiplies the current scale
     * (1f = no change), [panDeltaX]/[panDeltaY] are added to the current pan - both clamped so
     * the frame stays fully covered by the photo at all times, at any zoom level. */
    fun onTransform(panDeltaX: Float, panDeltaY: Float, zoomFactor: Float) {
        val photo = _uiState.value.photo ?: return
        val minScale = coverScale(photo)
        val maxScale = minScale * MAX_ZOOM_FACTOR
        val newScale = (_uiState.value.scale * zoomFactor).coerceIn(minScale, maxScale)
        val maxPanX = maxPan(photo.width, newScale, frameWidthPx)
        val maxPanY = maxPan(photo.height, newScale, frameHeightPx)
        val newPanX = (_uiState.value.panX + panDeltaX).coerceIn(-maxPanX, maxPanX)
        val newPanY = (_uiState.value.panY + panDeltaY).coerceIn(-maxPanY, maxPanY)
        _uiState.value = _uiState.value.copy(scale = newScale, panX = newPanX, panY = newPanY)
    }

    /**
     * Converts the current pan/zoom into a crop rect in the raw photo's own pixel space
     * (inverting the same cover-fit math [PhotoCropScreen] draws with), finishes it via
     * [ImageDownscaler.cropAndFinish], and publishes the result as
     * [PhotoRepository.workingPhoto] for terrain segmentation to consume.
     */
    fun confirmCrop() {
        val state = _uiState.value
        val photo = state.photo ?: return
        if (frameWidthPx <= 0f || frameHeightPx <= 0f) return

        val cropWidth = frameWidthPx / state.scale
        val cropHeight = frameHeightPx / state.scale
        val centerX = photo.width / 2f - state.panX / state.scale
        val centerY = photo.height / 2f - state.panY / state.scale
        val left = (centerX - cropWidth / 2f).roundToInt()
        val top = (centerY - cropHeight / 2f).roundToInt()
        val rect = Rect(left, top, left + cropWidth.roundToInt(), top + cropHeight.roundToInt())

        photoRepository.workingPhoto = ImageDownscaler.cropAndFinish(photo, rect)
        photoRepository.rawPhoto = null
    }

    /** The minimum scale at which [photo] fully covers the crop frame - the floor of the
     * user's allowed zoom range. */
    private fun coverScale(photo: Bitmap): Float {
        if (frameWidthPx <= 0f || frameHeightPx <= 0f) return 1f
        return max(frameWidthPx / photo.width, frameHeightPx / photo.height)
    }

    /** Half the slack (in screen px) between the scaled photo and the frame along one axis -
     * how far the photo can be panned before that axis's edge would show past the frame. */
    private fun maxPan(bitmapDimensionPx: Int, scale: Float, frameDimensionPx: Float): Float {
        return max(0f, (bitmapDimensionPx * scale - frameDimensionPx) / 2f)
    }

    companion object {
        private const val MAX_ZOOM_FACTOR = 4f
    }
}
