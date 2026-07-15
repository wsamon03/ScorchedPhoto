package com.scorchedphoto.app.terrainpreview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@Composable
fun TerrainPreviewScreen(
    onAccept: () -> Unit,
    onRetake: () -> Unit,
    viewModel: TerrainPreviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val photo = uiState.photo
            val heightMap = uiState.heightMap

            if (photo != null) {
                val imageBitmap = remember(photo) { photo.asImageBitmap() }
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawImage(
                        image = imageBitmap,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    )
                    if (heightMap != null) {
                        val scaleX = size.width / heightMap.width
                        val scaleY = size.height / heightMap.height
                        val path = Path()
                        heightMap.groundY.forEachIndexed { x, y ->
                            val px = x * scaleX
                            val py = y * scaleY
                            if (x == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        drawPath(path, color = Color(0xFFFFC107), style = Stroke(width = 4f))
                    }
                }
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(onClick = onAccept, enabled = heightMap != null) {
                    Text("Play")
                }
                Row(modifier = Modifier.padding(top = 12.dp)) {
                    Button(onClick = onRetake) {
                        Text("Retake")
                    }
                    Button(
                        onClick = viewModel::regenerate,
                        modifier = Modifier.padding(start = 8.dp),
                        enabled = !uiState.isLoading,
                    ) {
                        Text("Regenerate")
                    }
                }
            }
        }
    }
}
