package com.scorchedphoto.app.game

/**
 * One of 7 procedural sky backdrops drawn by GameRenderer.drawSkyLook, used only when
 * PhotoUsageMode.TERRAIN is active (filling whatever the terrain-clipped photo doesn't cover).
 * Always auto-randomized - resolved once via SkyLook.entries.random() in
 * GameSetupViewModel.commitAndStart, never user-picked (no UI dropdown lists this enum).
 */
enum class SkyLook(val displayName: String) {
    CLEAR("Clear Sky"),
    CLOUDY("Cloudy"),
    SUNRISE("Sunrise"),
    SUNSET("Sunset"),
    NIGHT_CLEAR("Night Sky"),
    NIGHT_STARS("Starry Night"),
    NIGHT_STARS_MOON("Moonlit Night"),
}
