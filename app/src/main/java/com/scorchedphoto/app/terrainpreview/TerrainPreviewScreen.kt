package com.scorchedphoto.app.terrainpreview

import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scorchedphoto.app.game.PhotoUsageMode
import com.scorchedphoto.app.ui.LockScreenOrientation
import com.scorchedphoto.terrain.HeightMap
import kotlin.math.roundToInt

@Composable
fun TerrainPreviewScreen(
    onAccept: () -> Unit,
    onRetake: () -> Unit,
    viewModel: TerrainPreviewViewModel = hiltViewModel(),
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
            val heightMap = uiState.heightMap

            if (photo != null) {
                val imageBitmap = remember(photo) { photo.asImageBitmap() }
                val drawModifier = if (uiState.isDrawingMode && heightMap != null) {
                    Modifier.terrainDrawGestures(heightMap, onPaint = viewModel::paintColumns)
                } else {
                    Modifier
                }
                Canvas(modifier = Modifier.fillMaxSize().then(drawModifier)) {
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
                        val strokeColor = if (uiState.isDrawingMode) Color(0xFF00E5FF) else Color(0xFFFFC107)
                        drawPath(path, color = strokeColor, style = Stroke(width = 4f))
                    }
                }
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            if (uiState.isDrawingMode) {
                Text(
                    "Drag to draw the ground",
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
                )
            }

            Button(
                onClick = onAccept,
                enabled = heightMap != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
            ) {
                Text("Play")
            }

            var menuExpanded by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            ) {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = "Terrain options",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    Text(
                        "Photo Usage",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    PhotoUsageMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.displayName) },
                            leadingIcon = if (mode == uiState.photoUsageMode) {
                                { Icon(imageVector = Icons.Filled.Check, contentDescription = null) }
                            } else {
                                null
                            },
                            onClick = {
                                viewModel.setPhotoUsageMode(mode)
                                menuExpanded = false
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Retake") },
                        onClick = {
                            menuExpanded = false
                            onRetake()
                        },
                    )
                    if (uiState.isDrawingMode) {
                        DropdownMenuItem(
                            text = { Text("Clear") },
                            onClick = {
                                viewModel.clearDrawing()
                                menuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Use Photo Terrain") },
                            onClick = {
                                viewModel.useAutoGenerated()
                                menuExpanded = false
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Regenerate") },
                            enabled = !uiState.isLoading,
                            onClick = {
                                viewModel.regenerate()
                                menuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Draw Your Own") },
                            enabled = heightMap != null,
                            onClick = {
                                viewModel.startDrawing()
                                menuExpanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Drag-to-paint gesture for hand-drawing terrain: on each touch, converts screen-space
 * pointer positions into (column, row) coordinates in [heightMap]'s pixel space (inverting
 * the same scale math the Canvas above uses to render the overlay line), then reports
 * [onPaint] calls the ViewModel forwards straight into [TerrainPreviewViewModel.paintColumns].
 * Uses awaitFirstDown()/drag() rather than detectDragGestures() so a deliberate single tap
 * (no drag) still paints that one column, matching the same reasoning as AngleRing's gesture.
 */
private fun Modifier.terrainDrawGestures(
    heightMap: HeightMap,
    onPaint: (fromColumn: Int?, fromRow: Int, toColumn: Int, toRow: Int) -> Unit,
): Modifier = pointerInput(heightMap.width, heightMap.height) {
    fun Offset.toColumnRow(): Pair<Int, Int> {
        val scaleX = size.width.toFloat() / heightMap.width
        val scaleY = size.height.toFloat() / heightMap.height
        val column = (x / scaleX).roundToInt().coerceIn(0, heightMap.width - 1)
        val row = (y / scaleY).roundToInt().coerceIn(0, heightMap.height - 1)
        return column to row
    }
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        val (startColumn, startRow) = down.position.toColumnRow()
        onPaint(null, startRow, startColumn, startRow)
        var lastColumn = startColumn
        var lastRow = startRow
        drag(down.id) { change ->
            change.consume()
            val (column, row) = change.position.toColumnRow()
            onPaint(lastColumn, lastRow, column, row)
            lastColumn = column
            lastRow = row
        }
    }
}
