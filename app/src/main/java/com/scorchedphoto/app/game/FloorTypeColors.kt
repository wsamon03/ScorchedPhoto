package com.scorchedphoto.app.game

import android.graphics.Color
import com.scorchedphoto.engine.FloorType

/**
 * Each real [FloorType]'s own distinct color - the floor equivalent of [colorFor] (for
 * [EdgeType][com.scorchedphoto.engine.EdgeType]), shared by [GameRenderer] (floor border/fill,
 * Void/Lava/Water rendering) and [com.scorchedphoto.app.setup.GameSettingsScreen] (dropdown
 * option text). The 6 types [FloorType] shares with the wall/ceiling vocabulary keep the exact
 * same hex values [colorFor] uses for those, so a wall/ceiling and a floor of the same type read
 * as the same color. [FloorType.HOLE] has no color ([null]) - no border is drawn, matching
 * [EdgeType.NONE][com.scorchedphoto.engine.EdgeType.NONE]'s own "no border" treatment, per the
 * user's spec ("default color/no border"). "Random" is a UI-only sentinel (a `null` `FloorType?`,
 * never a real [FloorType]) that never reaches this function - see
 * [com.scorchedphoto.app.setup.GameSetupViewModel]'s own doc on why.
 */
fun colorFor(floorType: FloorType): Int? = when (floorType) {
    FloorType.HOLE -> null
    FloorType.PADDED -> Color.rgb(0xAE, 0xE2, 0xF7) // light baby blue
    FloorType.RUBBER -> Color.rgb(0xFF, 0x6F, 0xB0) // pink
    FloorType.SPRING -> Color.rgb(0x29, 0xB6, 0xF6) // cyan
    FloorType.REFLECTIVE -> Color.rgb(0xFF, 0xFF, 0xFF) // white
    FloorType.WRAP -> Color.rgb(0xFF, 0xEB, 0x3B) // yellow
    FloorType.BLAST_STEEL -> Color.rgb(0x90, 0xA4, 0xAE) // steel blue-grey
    FloorType.GROUND -> Color.rgb(0x6D, 0x4C, 0x41) // brown
    FloorType.VOID -> Color.rgb(0x00, 0x00, 0x00) // black
    FloorType.WATER -> Color.rgb(0x21, 0x96, 0xF3) // blue
    FloorType.LAVA -> Color.rgb(0xF4, 0x43, 0x36) // red
}
