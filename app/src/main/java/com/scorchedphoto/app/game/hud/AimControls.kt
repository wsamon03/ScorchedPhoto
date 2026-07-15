package com.scorchedphoto.app.game.hud

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.scorchedphoto.app.game.GameCommand
import com.scorchedphoto.engine.MatchPhase

/**
 * Angle/power sliders keep their own local state (reset only when [currentTankId]
 * changes) so dragging feels immediate instead of waiting on the loop thread's
 * once-every-few-frames state republish; every drag still submits a [GameCommand] so the
 * engine's authoritative value converges to what's shown here.
 */
@Composable
fun AimControls(
    currentTankId: Int?,
    angleDeg: Float,
    power: Float,
    phase: MatchPhase,
    canFire: Boolean,
    onCommand: (GameCommand) -> Unit,
) {
    var localAngle by remember(currentTankId) { mutableStateOf(angleDeg) }
    var localPower by remember(currentTankId) { mutableStateOf(power) }

    Column {
        Text("Angle: ${localAngle.toInt()}°")
        Slider(
            value = localAngle,
            onValueChange = {
                localAngle = it
                onCommand(GameCommand.SetAngle(it))
            },
            valueRange = 5f..85f,
            enabled = canFire,
        )
        Text("Power: ${localPower.toInt()}")
        Slider(
            value = localPower,
            onValueChange = {
                localPower = it
                onCommand(GameCommand.SetPower(it))
            },
            valueRange = 1f..100f,
            enabled = canFire,
        )
        Button(
            onClick = { onCommand(GameCommand.Fire) },
            enabled = canFire,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(if (phase == MatchPhase.AIMING) "Fire" else "…")
        }
    }
}
