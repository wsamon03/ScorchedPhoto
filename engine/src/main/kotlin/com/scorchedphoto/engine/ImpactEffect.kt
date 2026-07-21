package com.scorchedphoto.engine

/**
 * A short-lived visual impact marker for the renderer to draw as a fading flash, sized to
 * the weapon's actual [blastRadius] so the visual matches the area that took damage.
 */
data class ImpactEffect(val x: Float, val y: Float, val blastRadius: Float, var age: Float = 0f)
