package com.scorchedphoto.app.capture

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PhotoSourceViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
) : ViewModel() {

    /** Decodes off the main thread and only invokes [onComplete] once the photo is ready. */
    fun loadPhoto(context: Context, uri: Uri, onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            val bitmap = ImageDownscaler.loadDownscaledAndCorrected(context, uri)
            photoRepository.workingPhoto = bitmap
            withContext(Dispatchers.Main) { onComplete() }
        }
    }
}
