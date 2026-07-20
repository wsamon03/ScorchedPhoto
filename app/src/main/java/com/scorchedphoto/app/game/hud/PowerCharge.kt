package com.scorchedphoto.app.game.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.scorchedphoto.app.game.GameCommand
import com.scorchedphoto.engine.physics.oscillatingPower

/**
 * Shared hold-to-charge state for the power meter. Both the full-screen catcher and the
 * dedicated corner button drive the *same* instance, so they're guaranteed to agree on
 * what's currently charging rather than being two independent implementations.
 */
class PowerChargeState {
    var isCharging by mutableStateOf(false)
        private set
    var displayPower by mutableFloatStateOf(0f)
        internal set

    fun start() {
        displayPower = 0f
        isCharging = true
    }

    /** Stops charging and returns the power value to fire at. */
    fun release(): Float {
        isCharging = false
        return displayPower
    }
}

@Composable
fun rememberPowerChargeState(): PowerChargeState = remember { PowerChargeState() }

/**
 * Drives [state]'s oscillating value every frame while charging. Call exactly once
 * (e.g. from [com.scorchedphoto.app.game.hud.HudOverlay]) regardless of how many trigger
 * surfaces share the state.
 */
@Composable
fun PowerChargeAnimator(state: PowerChargeState) {
    LaunchedEffect(state.isCharging) {
        if (!state.isCharging) return@LaunchedEffect
        var startFrameNanos = -1L
        while (state.isCharging) {
            withFrameNanos { frameNanos ->
                if (startFrameNanos < 0L) startFrameNanos = frameNanos
                val elapsedSeconds = (frameNanos - startFrameNanos) / 1_000_000_000f
                state.displayPower = oscillatingPower(elapsedSeconds)
            }
        }
    }
}

/** Press-and-hold-anywhere charges [state]; releasing fires at whatever it's on. */
fun Modifier.chargeFireGestures(state: PowerChargeState, onCommand: (GameCommand) -> Unit): Modifier =
    pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown()
            state.start()
            waitForUpOrCancellation()
            onCommand(GameCommand.FireWithPower(state.release()))
        }
    }

private val BUTTON_SIZE = 72.dp
private val BUTTON_RING_STROKE = 4.dp

/** A translucent, discoverable alternative to holding anywhere on the battlefield. */
@Composable
fun PowerChargeButton(state: PowerChargeState, onCommand: (GameCommand) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(BUTTON_SIZE)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.15f))
            .chargeFireGestures(state, onCommand),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = BUTTON_RING_STROKE.toPx()
            drawCircle(
                color = Color.White.copy(alpha = 0.4f),
                radius = (size.minDimension - strokePx) / 2f,
                style = Stroke(width = strokePx),
            )
            if (state.isCharging) {
                drawArc(
                    color = Color(0xFFFFB020),
                    startAngle = -90f,
                    sweepAngle = 360f * (state.displayPower / 100f),
                    useCenter = false,
                    style = Stroke(width = strokePx),
                )
            }
        }
        Text("Fire", color = Color.White)
    }
}

val POWER_METER_WIDTH = 18.dp
val POWER_METER_HEIGHT = 120.dp
val POWER_METER_HORIZONTAL_GAP = 16.dp

/**
 * A prominent vertical power gauge, visible only while [state] is actively charging -
 * positioned by the caller (see [HudOverlay]) near the tank rather than tucked in a
 * corner, so charging power is easy to read without looking away from the aim.
 */
@Composable
fun PowerMeterBar(state: PowerChargeState, modifier: Modifier = Modifier) {
    if (!state.isCharging) return

    Box(
        modifier = modifier
            .size(width = POWER_METER_WIDTH, height = POWER_METER_HEIGHT)
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(POWER_METER_WIDTH / 2)),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(state.displayPower / 100f)
                .background(Color(0xFFFFB020), RoundedCornerShape(POWER_METER_WIDTH / 2)),
        )
    }
}
