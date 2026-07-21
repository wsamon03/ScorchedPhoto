package com.scorchedphoto.engine.tanks

import kotlin.math.cos
import kotlin.math.sin

/**
 * Purely cosmetic body silhouette a tank renders with - collision/damage always uses the
 * circular [Tank.RADIUS] regardless of shape, so this never affects gameplay, only how the
 * player tells their tank apart on the battlefield and in setup.
 *
 * Each shape is a normalized outline tracing a hull with a turret rising off its top, so
 * every option reads as an actual tank silhouette rather than an abstract polygon: points
 * with `x` in `[-1, 1]` and `y` in `[-1, 0]`, centered on the tank's ground contact point
 * with `y` increasing downward (screen convention) - `y = 0` is the flat base sitting on
 * the ground/treads, `y = -1` is the shape's peak. Renderers scale these by the tank's
 * actual half-width and translate to its screen position to build the drawable outline, so
 * there's a single source of truth for the geometry shared by the in-match renderer and the
 * setup-screen picker.
 */
enum class TankShape(val outline: List<Pair<Float, Float>>) {
    /** Wide flat hull, boxy turret centered on top - the classic silhouette. */
    CLASSIC(
        listOf(
            -1f to 0f, -1f to -0.35f, -0.45f to -0.35f, -0.45f to -1f,
            0.45f to -1f, 0.45f to -0.35f, 1f to -0.35f, 1f to 0f,
        ),
    ),

    /** Chamfered hull corners under a wide, low turret block - a bulkier profile. */
    HEAVY(
        listOf(
            -1f to 0f, -1f to -0.2f, -0.85f to -0.45f, -0.55f to -0.45f, -0.55f to -0.85f,
            0.55f to -0.85f, 0.55f to -0.45f, 0.85f to -0.45f, 1f to -0.2f, 1f to 0f,
        ),
    ),

    /** Sloped front glacis armor with the turret set back - a modern MBT profile. */
    SLOPED(
        listOf(
            -1f to 0f, -0.55f to -0.5f, -0.15f to -0.5f, -0.15f to -0.85f,
            0.5f to -0.85f, 0.5f to -0.5f, 1f to -0.5f, 1f to 0f,
        ),
    ),

    /** Low flat hull with a small centered turret - a light scout profile. */
    LIGHT(
        listOf(
            -1f to 0f, -1f to -0.22f, -0.3f to -0.22f, -0.3f to -0.55f,
            0.3f to -0.55f, 0.3f to -0.22f, 1f to -0.22f, 1f to 0f,
        ),
    ),

    /** Flat hull under a rounded dome turret. */
    DOME(domeTurretOutline()),

    /** Low hull with a pair of raised turret cupolas - a self-propelled-gun profile. */
    TWIN(
        listOf(
            -1f to 0f, -1f to -0.22f, -0.72f to -0.22f, -0.72f to -0.6f, -0.32f to -0.6f, -0.32f to -0.22f,
            0.32f to -0.22f, 0.32f to -0.6f, 0.72f to -0.6f, 0.72f to -0.22f, 1f to -0.22f, 1f to 0f,
        ),
    ),
}

/**
 * A flat hull (`y = -0.3`) topped with a rounded dome turret, approximated with line
 * segments. A top-level function, not a companion member of [TankShape] - enum constants
 * construct before their own companion object is initialized, so [TankShape.DOME] can't
 * call a companion function to build its outline.
 */
private fun domeTurretOutline(segments: Int = 6): List<Pair<Float, Float>> {
    val hullY = -0.3f
    val radius = 0.42f
    val arc = (1 until segments).map { i ->
        val theta = Math.PI - i * Math.PI / segments
        (radius * cos(theta)).toFloat() to (hullY - radius * sin(theta)).toFloat()
    }
    return listOf(-1f to 0f, -1f to hullY, -radius to hullY) +
        arc +
        listOf(radius to hullY, 1f to hullY, 1f to 0f)
}
