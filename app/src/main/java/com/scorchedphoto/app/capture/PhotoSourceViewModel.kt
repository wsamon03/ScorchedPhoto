package com.scorchedphoto.app.capture

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PhotoSourceViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
) : ViewModel() {

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Decodes off the main thread and only invokes [onComplete] once the photo is ready. */
    fun loadPhoto(context: Context, uri: Uri, onComplete: () -> Unit) {
        _errorMessage.value = null
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val bitmap = ImageDownscaler.loadCorrected(context, uri)
                photoRepository.rawPhoto = bitmap
                withContext(Dispatchers.Main) { onComplete() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Couldn't load that photo. Please try a different one."
                }
            }
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }
}
