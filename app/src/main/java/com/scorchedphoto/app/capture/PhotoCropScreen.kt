package com.scorchedphoto.app.capture

import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scorchedphoto.app.ui.LockScreenOrientation
import kotlin.math.roundToInt

/**
 * Lets the user pick which region of their photo becomes the battlefield: the raw,
 * orientation-corrected photo (not yet cropped) fills the screen at whatever zoom/pan the
 * user has chosen, always fully covering the frame - see [PhotoCropViewModel] - so what's
 * on screen here is exactly what [PhotoCropViewModel.confirmCrop] will crop to. Landscape-
 * locked because the frame's aspect ratio is the gameplay screen's landscape aspect (the
 * same target [ImageDownscaler]'s crop always matched), so the user needs to be looking at
 * it in the same shape it'll actually play in.
 */
@Composable
fun PhotoCropScreen(
    onCropConfirmed: () -> Unit,
    onRetake: () -> Unit,
    viewModel: PhotoCropViewModel = hiltViewModel(),
) {
    LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val photo = uiState.photo
            if (photo != null) {
                val imageBitmap = remember(photo) { photo.asImageBitmap() }
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .onSizeChanged { size ->
                            viewModel.onFrameSizeChanged(size.width.toFloat(), size.height.toFloat())
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                viewModel.onTransform(pan.x, pan.y, zoom)
                            }
                        },
                ) {
                    val bitmapWidthPx = photo.width * uiState.scale
                    val bitmapHeightPx = photo.height * uiState.scale
                    val left = (size.width - bitmapWidthPx) / 2f + uiState.panX
                    val top = (size.height - bitmapHeightPx) / 2f + uiState.panY
                    drawImage(
                        image = imageBitmap,
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(bitmapWidthPx.roundToInt(), bitmapHeightPx.roundToInt()),
                    )
                }
                Text(
                    "Pinch to zoom, drag to reposition",
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
                )
            } else {
                Text(
                    "No photo to crop.",
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row {
                    Button(
                        onClick = {
                            viewModel.confirmCrop()
                            onCropConfirmed()
                        },
                        enabled = photo != null,
                    ) {
                        Text("Use This Photo")
                    }
                    Button(onClick = onRetake, modifier = Modifier.padding(start = 12.dp)) {
                        Text("Retake")
                    }
                }
            }
        }
    }
}
