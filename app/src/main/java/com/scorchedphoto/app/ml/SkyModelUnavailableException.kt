package com.scorchedphoto.app.ml

/** Thrown when the bundled sky-segmentation model can't be loaded or run. */
class SkyModelUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
