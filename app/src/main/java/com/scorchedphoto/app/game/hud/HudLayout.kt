package com.scorchedphoto.app.game.hud

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize

/** The four corners a HUD group can be anchored to, in clockwise order - see
 * [resolveHudCorner]. */
enum class HudCorner { TOP_START, TOP_END, BOTTOM_END, BOTTOM_START }

fun HudCorner.toAlignment(): Alignment = when (this) {
    HudCorner.TOP_START -> Alignment.TopStart
    HudCorner.TOP_END -> Alignment.TopEnd
    HudCorner.BOTTOM_END -> Alignment.BottomEnd
    HudCorner.BOTTOM_START -> Alignment.BottomStart
}

/** A circle on screen no HUD element may overlap - centered at the active tank's own screen
 * position with [radius] equal to the [AngleRing]'s own touch radius, since that's the
 * largest active touch/visual footprint the tank has while it's the one being aimed (bigger
 * than the tank sprite itself). `null` wherever there's nothing to protect against (see
 * [HudOverlay]'s own `canFire`/`hasValidCanvas` gating on [AngleRing]'s visibility). */
data class ProtectedZone(val center: Offset, val radius: Float)

/** The rect a HUD group of [groupSize] would occupy on a [canvasSize] screen if anchored at
 * [corner], with [groupSize] already including that group's own padding. */
private fun rectFor(corner: HudCorner, groupSize: Size, canvasSize: IntSize): Rect {
    val left = when (corner) {
        HudCorner.TOP_START, HudCorner.BOTTOM_START -> 0f
        HudCorner.TOP_END, HudCorner.BOTTOM_END -> canvasSize.width - groupSize.width
    }
    val top = when (corner) {
        HudCorner.TOP_START, HudCorner.TOP_END -> 0f
        HudCorner.BOTTOM_START, HudCorner.BOTTOM_END -> canvasSize.height - groupSize.height
    }
    return Rect(left, top, left + groupSize.width, top + groupSize.height)
}

/** True if [rect] intersects [zone] - clamps the circle's own center into the rect's bounds
 * on each axis to find the closest point the rect actually contains, then compares that
 * point's distance to the center against the radius (the standard rect/circle intersection
 * test). */
private fun intersects(rect: Rect, zone: ProtectedZone): Boolean {
    val closestX = zone.center.x.coerceIn(rect.left, rect.right)
    val closestY = zone.center.y.coerceIn(rect.top, rect.bottom)
    val dx = zone.center.x - closestX
    val dy = zone.center.y - closestY
    return dx * dx + dy * dy < zone.radius * zone.radius
}

/** Each corner's own fallback rotation - stays on the same edge (top/bottom) before crossing
 * to the other side, and prefers the opposite corner on its own edge before crossing. */
private val FALLBACK_ORDER: Map<HudCorner, List<HudCorner>> = mapOf(
    HudCorner.TOP_START to listOf(HudCorner.TOP_START, HudCorner.TOP_END, HudCorner.BOTTOM_START, HudCorner.BOTTOM_END),
    HudCorner.TOP_END to listOf(HudCorner.TOP_END, HudCorner.TOP_START, HudCorner.BOTTOM_END, HudCorner.BOTTOM_START),
    HudCorner.BOTTOM_END to listOf(HudCorner.BOTTOM_END, HudCorner.BOTTOM_START, HudCorner.TOP_END, HudCorner.TOP_START),
    HudCorner.BOTTOM_START to listOf(HudCorner.BOTTOM_START, HudCorner.BOTTOM_END, HudCorner.TOP_START, HudCorner.TOP_END),
)

/**
 * Picks the corner a HUD group should actually render in this frame: its own [homeCorner] if
 * that's safe, else the first corner (in [homeCorner]'s own fallback rotation) that's both
 * unclaimed by a higher-priority group already evaluated this frame ([claimed]) and doesn't
 * overlap [zone]. Callers evaluate their groups in a fixed priority order, threading each
 * result into the next call's [claimed] set, so higher-priority groups always get first pick
 * of a safe corner and lower-priority ones route around them. Falls back to [homeCorner]
 * itself if every corner is unsafe (a degenerate/very-small-screen case) - rendering with an
 * occasional overlap there is preferable to a HUD element silently vanishing.
 */
fun resolveHudCorner(
    homeCorner: HudCorner,
    groupSize: Size,
    canvasSize: IntSize,
    zone: ProtectedZone?,
    claimed: Set<HudCorner>,
): HudCorner {
    for (corner in FALLBACK_ORDER.getValue(homeCorner)) {
        if (corner in claimed) continue
        if (zone != null && intersects(rectFor(corner, groupSize, canvasSize), zone)) continue
        return corner
    }
    return homeCorner
}
