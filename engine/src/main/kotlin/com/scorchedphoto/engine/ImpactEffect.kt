package com.scorchedphoto.engine

/** A short-lived visual impact marker for the renderer to draw as a fading flash. */
data class ImpactEffect(val x: Float, val y: Float, var age: Float = 0f)
