package com.scorchedphoto.app.game.hud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.scorchedphoto.app.game.GameCommand
import com.scorchedphoto.app.game.GameUiState
import com.scorchedphoto.engine.MatchPhase
import kotlin.math.roundToInt

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
            val tankScreenX = uiState.currentTankX * scaleX
            val tankScreenY = uiState.currentTankY * scaleY

            AngleRing(
                centerXPx = tankScreenX,
                centerYPx = tankScreenY,
                angleDeg = uiState.currentAngleDeg,
                onCommand = onCommand,
            )

            val density = LocalDensity.current
            val touchRadiusPx = with(density) { TOUCH_RADIUS.toPx() }
            val gapPx = with(density) { POWER_METER_HORIZONTAL_GAP.toPx() }
            val meterWidthPx = with(density) { POWER_METER_WIDTH.toPx() }
            val meterHeightPx = with(density) { POWER_METER_HEIGHT.toPx() }

            // Toward the screen's horizontal middle from the tank, so the meter never
            // needs edge-clamping - and vertically centered on the tank's own height,
            // not the screen's, per the user's explicit positioning choice.
            val towardCenter = if (tankScreenX < canvasSizePx.width / 2f) 1f else -1f
            val meterX = tankScreenX + towardCenter * (touchRadiusPx + gapPx) -
                if (towardCenter < 0f) meterWidthPx else 0f
            val meterY = tankScreenY - meterHeightPx / 2f

            PowerMeterBar(
                state = powerChargeState,
                modifier = Modifier.offset { IntOffset(meterX.roundToInt(), meterY.roundToInt()) },
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
