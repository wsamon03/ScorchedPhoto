package com.scorchedphoto.app.game

import kotlin.math.min

/**
 * Maps world coordinates (the terrain's own pixel space, which [GameEngine] and every
 * physics/collision calculation operate in) onto screen pixels using a single *uniform*
 * scale, letterboxed to fit the canvas - never two independently-stretched scaleX/scaleY
 * factors. The canvas (a full-screen [android.view.SurfaceView]) and the world (a photo's
 * segmented terrain) almost never share an aspect ratio, and stretching them to fill the
 * screen non-uniformly distorts every angle and distance: a 45-degree launch would render
 * shallower than 45 degrees whenever the canvas is wider (relative to its height) than the
 * terrain is, since horizontal motion would cover disproportionately more screen pixels
 * than the equal vertical motion does. [GameRenderer] and [com.scorchedphoto.app.game.hud.HudOverlay]
 * both derive their transform from this same fit() so the drawn world and the touch/HUD
 * layer on top of it always agree.
 */
data class WorldTransform(val scale: Float, val offsetX: Float, val offsetY: Float) {
    fun screenX(worldX: Float): Float = worldX * scale + offsetX
    fun screenY(worldY: Float): Float = worldY * scale + offsetY

    companion object {
        fun fit(canvasWidth: Float, canvasHeight: Float, worldWidth: Float, worldHeight: Float): WorldTransform {
            if (canvasWidth <= 0f || canvasHeight <= 0f || worldWidth <= 0f || worldHeight <= 0f) {
                return WorldTransform(1f, 0f, 0f)
            }
            val scale = min(canvasWidth / worldWidth, canvasHeight / worldHeight)
            val offsetX = (canvasWidth - worldWidth * scale) / 2f
            val offsetY = (canvasHeight - worldHeight * scale) / 2f
            return WorldTransform(scale, offsetX, offsetY)
        }
    }
}
