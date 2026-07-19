package com.scorchedphoto.app.terrainpreview

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scorchedphoto.app.capture.ImageDownscaler
import com.scorchedphoto.app.capture.PhotoRepository
import com.scorchedphoto.app.ml.FallbackSkySignalProvider
import com.scorchedphoto.terrain.HeightMap
import com.scorchedphoto.terrain.TerrainSegmenter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TerrainPreviewUiState(
    val photo: Bitmap? = null,
    val heightMap: HeightMap? = null,
    val isLoading: Boolean = true,
)

@HiltViewModel
class TerrainPreviewViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val terrainRepository: TerrainRepository,
    private val skySignalProvider: FallbackSkySignalProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TerrainPreviewUiState())
    val uiState: StateFlow<TerrainPreviewUiState> = _uiState.asStateFlow()

    private var segmentJob: Job? = null

    init {
        segment(seed = System.nanoTime())
    }

    fun regenerate() {
        segment(seed = System.nanoTime())
    }

    private fun segment(seed: Long) {
        val photo = photoRepository.workingPhoto ?: return
        segmentJob?.cancel()
        _uiState.value = TerrainPreviewUiState(photo = photo, heightMap = null, isLoading = true)
        segmentJob = viewModelScope.launch(Dispatchers.Default) {
            val pixelBuffer = ImageDownscaler.toPixelBuffer(photo)
            val heightMap = TerrainSegmenter.segment(pixelBuffer, seed, skySignalProvider)
            terrainRepository.heightMap = heightMap
            _uiState.value = TerrainPreviewUiState(photo = photo, heightMap = heightMap, isLoading = false)
        }
    }
}
