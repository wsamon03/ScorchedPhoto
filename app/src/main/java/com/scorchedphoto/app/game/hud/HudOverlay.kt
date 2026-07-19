package com.scorchedphoto.app.game.hud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.scorchedphoto.app.game.GameCommand
import com.scorchedphoto.app.game.GameUiState
import com.scorchedphoto.engine.MatchPhase

@Composable
fun HudOverlay(uiState: GameUiState, onCommand: (GameCommand) -> Unit, modifier: Modifier = Modifier) {
    var canvasSizePx by remember { mutableStateOf(IntSize.Zero) }
    val powerChargeState = rememberPowerChargeState()
    PowerChargeAnimator(powerChargeState)

    val canFire = uiState.phase == MatchPhase.AIMING && !uiState.currentTankIsCpu

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSizePx = it },
    ) {
        // Bottom-most layer: press-and-hold anywhere on the battlefield charges/fires.
        // Smaller, topmost composables below (weapon chips, the angle ring, the power
        // button) claim their own touches first via normal Compose hit-testing, so this
        // never steals a drag/tap meant for them.
        if (canFire) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .chargeFireGestures(powerChargeState, onCommand),
            )
        }

        HealthBarRow(
            tanks = uiState.tanks,
            currentTankId = uiState.currentTankId,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        )
        WindIndicator(
            windVelocity = uiState.windVelocity,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        )

        WeaponSelector(
            weapons = uiState.weapons,
            enabled = canFire,
            onCommand = onCommand,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        )

        if (canFire && canvasSizePx.width > 0 && canvasSizePx.height > 0) {
            val scaleX = canvasSizePx.width.toFloat() / uiState.terrainWidth
            val scaleY = canvasSizePx.height.toFloat() / uiState.terrainHeight
            AngleRing(
                centerXPx = uiState.currentTankX * scaleX,
                centerYPx = uiState.currentTankY * scaleY,
                angleDeg = uiState.currentAngleDeg,
                onCommand = onCommand,
            )
        }

        if (canFire) {
            PowerChargeButton(
                state = powerChargeState,
                onCommand = onCommand,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        }
    }
}
