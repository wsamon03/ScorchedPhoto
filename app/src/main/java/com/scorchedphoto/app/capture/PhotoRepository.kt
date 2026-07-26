package com.scorchedphoto.app.capture

import android.graphics.Bitmap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds photo bitmaps between screens. Bitmaps aren't Parcelable-safe for nav
 * arguments/SavedStateHandle, so they're kept here instead and screens navigate with just a
 * "photo is ready" signal.
 */
@Singleton
class PhotoRepository @Inject constructor() {
    /** Orientation-corrected but not yet cropped - staged between PhotoSourceScreen and
     * PhotoCropScreen for the user's manual crop selection. */
    var rawPhoto: Bitmap? = null

    /** The final downscaled, cropped photo terrain segmentation actually consumes. */
    var workingPhoto: Bitmap? = null
}
