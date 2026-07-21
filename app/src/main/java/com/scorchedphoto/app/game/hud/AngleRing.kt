package com.scorchedphoto.app.game.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.scorchedphoto.app.game.GameCommand
import com.scorchedphoto.engine.physics.screenOffsetToAngleDeg
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val RING_RADIUS = 36.dp
internal val TOUCH_RADIUS = 70.dp
private val RING_STROKE_WIDTH = 3.dp
private val HIGHLIGHT_DOT_RADIUS = 7.dp

/**
 * Translucent ring anchored to the current tank's live screen position (in
 * [GameUiState.currentTankX]/Y, mapped through the same terrain->screen scale
 * [com.scorchedphoto.app.game.GameRenderer] uses). Drag anywhere within the touch region
 * to aim - the bounded touch area (larger than the visible ring) claims the gesture
 * before it reaches the full-screen charge/fire catcher underneath.
 */
@Composable
fun AngleRing(
    centerXPx: Float,
    centerYPx: Float,
    angleDeg: Float,
    onCommand: (GameCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val touchRadiusPx = with(density) { TOUCH_RADIUS.toPx() }
    val ringRadiusPx = with(density) { RING_RADIUS.toPx() }
    val strokePx = with(density) { RING_STROKE_WIDTH.toPx() }
    val dotRadiusPx = with(density) { HIGHLIGHT_DOT_RADIUS.toPx() }

    Canvas(
        modifier = modifier
            .offset {
                IntOffset(
                    (centerXPx - touchRadiusPx).roundToInt(),
                    (centerYPx - touchRadiusPx).roundToInt(),
                )
            }
            .size(TOUCH_RADIUS * 2)
            .pointerInput(Unit) {
                // Applies the touch position the instant a finger goes down, then tracks
                // every subsequent move directly via drag() - not detectDragGestures(),
                // whose onDrag only fires once movement exceeds the platform's touch-slop
                // threshold. A deliberate tap straight at the intended angle (no preceding
                // drag) would otherwise never register at all, silently leaving the tank
                // aimed at whatever angle it already had.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    fun applyAngle(position: Offset) {
                        val dx = position.x - touchRadiusPx
                        val dy = position.y - touchRadiusPx
                        onCommand(GameCommand.SetAngle(screenOffsetToAngleDeg(dx, dy)))
                    }
                    applyAngle(down.position)
                    drag(down.id) { change ->
                        change.consume()
                        applyAngle(change.position)
                    }
                }
            },
    ) {
        val center = Offset(touchRadiusPx, touchRadiusPx)
        drawCircle(
            color = Color.White.copy(alpha = 0.35f),
            radius = ringRadiusPx,
            center = center,
            style = Stroke(width = strokePx),
        )

        val angleRad = Math.toRadians(angleDeg.toDouble())
        val highlight = Offset(
            x = center.x + (cos(angleRad) * ringRadiusPx).toFloat(),
            y = center.y - (sin(angleRad) * ringRadiusPx).toFloat(),
        )
        drawCircle(color = Color(0xFFFFB020), radius = dotRadiusPx, center = highlight)
    }
}
