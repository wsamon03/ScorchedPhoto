package com.scorchedphoto.app.game.hud

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun WindIndicator(windVelocity: Float, modifier: Modifier = Modifier) {
    val arrow = when {
        windVelocity > 0.5f -> "→"
        windVelocity < -0.5f -> "←"
        else -> "·"
    }
    Text("Wind $arrow ${abs(windVelocity).roundToInt()}", modifier = modifier)
}
