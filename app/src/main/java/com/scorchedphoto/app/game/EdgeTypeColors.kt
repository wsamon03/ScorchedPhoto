package com.scorchedphoto.app.game

import android.graphics.Color
import com.scorchedphoto.engine.EdgeType

/**
 * Each real [EdgeType]'s own distinct, noticeably-different-from-the-others color - the single
 * source of truth shared by [GameRenderer] (screen-edge border, bounce-mark animations) and
 * [com.scorchedphoto.app.setup.GameSettingsScreen] (dropdown option text), so a wall/ceiling
 * type reads as the same color everywhere it appears. [EdgeType.NONE] has no color ([null]) -
 * no border is drawn, and its dropdown text falls back to the theme's default. "Random" is a
 * UI-only sentinel (a `null` `EdgeType?`, never a real [EdgeType]) that never reaches this
 * function - see [com.scorchedphoto.app.setup.GameSetupViewModel]'s own doc on why.
 */
fun colorFor(edgeType: EdgeType): Int? = when (edgeType) {
    EdgeType.NONE -> null
    EdgeType.PADDED -> Color.rgb(0xAE, 0xE2, 0xF7) // light baby blue
    EdgeType.RUBBER -> Color.rgb(0xFF, 0x6F, 0xB0) // pink
    EdgeType.SPRING -> Color.rgb(0x29, 0xB6, 0xF6) // cyan
    EdgeType.REFLECTIVE -> Color.rgb(0xFF, 0xFF, 0xFF) // white
    EdgeType.WRAP -> Color.rgb(0xFF, 0xEB, 0x3B) // yellow
    EdgeType.BLAST_STEEL -> Color.rgb(0x90, 0xA4, 0xAE) // steel blue-grey
}
