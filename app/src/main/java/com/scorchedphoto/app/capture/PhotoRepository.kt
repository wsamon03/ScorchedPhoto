package com.scorchedphoto.app.capture

import android.graphics.Bitmap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the working (downscaled, orientation-corrected) photo bitmap between screens.
 * Bitmaps aren't Parcelable-safe for nav arguments/SavedStateHandle, so it's kept here
 * instead and screens navigate with just a "photo is ready" signal.
 */
@Singleton
class PhotoRepository @Inject constructor() {
    var workingPhoto: Bitmap? = null
}
