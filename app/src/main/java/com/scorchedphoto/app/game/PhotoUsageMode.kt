package com.scorchedphoto.app.game

/**
 * How this match's photo is used as the battlefield backdrop - a pure rendering choice with no
 * physics/engine implications (GameEngine never reads this), so unlike EdgeType/FloorType it
 * lives in :app, not :engine. Resolved once per match in GameSetupViewModel.commitAndStart and
 * never re-picked mid-match. User-selectable in GameSettingsScreen with no "Random" option
 * (defaults to BACKGROUND, matching the pre-existing behavior) - see TypeDropdown's
 * includeRandom param.
 */
enum class PhotoUsageMode(val displayName: String) {
    BACKGROUND("Background"),
    TERRAIN("Terrain Only"),
    SKY("Sky Only"),
}
