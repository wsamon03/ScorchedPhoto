package com.scorchedphoto.engine.tanks

import kotlin.math.cos
import kotlin.math.sin

/**
 * Purely cosmetic body silhouette a tank renders with - collision/damage always uses the
 * circular [Tank.RADIUS] regardless of shape, so this never affects gameplay, only how the
 * player tells their tank apart on the battlefield and in setup.
 *
 * Each shape is a normalized outline: points with `x` in `[-1, 1]` and `y` in `[-1, 0]`,
 * centered on the tank's ground contact point with `y` increasing downward (screen
 * convention) - `y = 0` is the flat base sitting on the ground, `y = -1` is the shape's
 * peak. Renderers scale these by the tank's actual half-width and translate to its screen
 * position to build the drawable outline, so there's a single source of truth for the
 * geometry shared by the in-match renderer and the setup-screen picker.
 */
enum class TankShape(val outline: List<Pair<Float, Float>>) {
    SQUARE(listOf(-1f to -1f, 1f to -1f, 1f to 0f, -1f to 0f)),
    ROUND(DOME_OUTLINE),
    WEDGE(listOf(-1f to 0f, 0f to -1f, 1f to 0f)),
    HEXAGON(listOf(-1f to -0.4f, -0.5f to -1f, 0.5f to -1f, 1f to -0.4f, 1f to 0f, -1f to 0f)),
    PENTAGON(listOf(0f to -1f, 1f to -0.35f, 0.6f to 0f, -0.6f to 0f, -1f to -0.35f)),
    DIAMOND(listOf(-1f to -0.5f, 0f to -1f, 1f to -0.5f, 1f to 0f, -1f to 0f)),
}

/**
 * A half-circle dome sitting on the ground line, approximated with line segments. A
 * top-level constant, not a companion member of [TankShape] - enum constants construct
 * before their own companion object is initialized, so [ROUND] can't call a companion
 * function to build its outline.
 */
private val DOME_OUTLINE: List<Pair<Float, Float>> = (0..16).map { i ->
    val t = Math.PI * i / 16
    -cos(t).toFloat() to -sin(t).toFloat()
}
