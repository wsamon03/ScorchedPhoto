package com.scorchedphoto.app.game.hud

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.scorchedphoto.app.game.WorldTransform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private val CIRCLE_DIAMETER = 64.dp
private val BORDER_STROKE_WIDTH = 3.dp
private val POLE_STROKE_WIDTH = 2.dp
private const val OVERLAY_ALPHA = 0.4f
private const val BORDER_ALPHA = 0.85f

// Candidate colors the pole/flag are chosen from by contrast against the average photo
// color sampled within the circle - see pickMostContrasting.
private val POLE_CANDIDATES = listOf(
    Color(0xFFFFD54F), // yellow
    Color(0xFFC0C0C0), // silver
    Color.White,
    Color.Black,
)
private val FLAG_CANDIDATES = listOf(
    Color(0xFFE53935), // red
    Color(0xFFFB8C00), // orange
    Color(0xFFFDD835), // yellow
    Color(0xFF43A047), // green
    Color(0xFF00ACC1), // cyan
    Color(0xFF1E88E5), // blue
    Color(0xFF8E24AA), // purple
    Color(0xFFD81B60), // pink
    Color.White,
    Color.Black,
)

/**
 * A small circular gauge (top-right of the HUD) showing wind as a flag on a pole: the
 * flag hangs straight down when calm, swings up toward fully horizontal as wind strength
 * approaches [windMaxMagnitude], and points toward whichever side [windVelocity]'s sign
 * blows (matching [com.scorchedphoto.engine.physics.stepProjectile]'s
 * `vx += wind.velocity * WIND_SCALE * dt`: positive = rightward). The circle is a
 * semi-transparent overlay with an opaque-ish white border; the pole and flag colors are
 * each picked from a small candidate palette by contrast against the average color of the
 * match photo's pixels that actually fall within the circle - see [averageColorInCircle] -
 * so the gauge stays readable over any photo.
 */
@Composable
fun WindIndicator(
    windVelocity: Float,
    windMaxMagnitude: Float,
    photo: Bitmap?,
    transform: WorldTransform,
    terrainWidth: Int,
    terrainHeight: Int,
    modifier: Modifier = Modifier,
) {
    val radiusPx = with(LocalDensity.current) { CIRCLE_DIAMETER.toPx() } / 2f

    // Learned from actual layout (rather than replicating the caller's alignment/padding
    // math here) so this stays correct however HudOverlay chooses to position it.
    var circleCenter by remember { mutableStateOf<Offset?>(null) }

    val backgroundColor = remember(photo, transform, terrainWidth, terrainHeight, circleCenter) {
        val center = circleCenter
        if (photo != null && terrainWidth > 0 && terrainHeight > 0 && center != null) {
            averageColorInCircle(photo, transform, terrainWidth, terrainHeight, center, radiusPx)
        } else {
            Color(0xFF808080)
        }
    }
    val poleColor = remember(backgroundColor) { pickMostContrasting(backgroundColor, POLE_CANDIDATES) }
    val flagColor = remember(backgroundColor) { pickMostContrasting(backgroundColor, FLAG_CANDIDATES) }

    Canvas(
        modifier = modifier
            .size(CIRCLE_DIAMETER)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInParent()
                val center = Offset((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
                if (center != circleCenter) circleCenter = center
            },
    ) {
        val radius = size.width / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val circleBounds = Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)

        clipPath(Path().apply { addOval(circleBounds) }) {
            drawCircle(color = Color.Black.copy(alpha = OVERLAY_ALPHA), radius = radius, center = center)
            drawFlag(windVelocity, windMaxMagnitude, poleColor, flagColor, center, radius)
        }

        drawCircle(
            color = Color.White.copy(alpha = BORDER_ALPHA),
            radius = radius - BORDER_STROKE_WIDTH.toPx() / 2f,
            center = center,
            style = Stroke(width = BORDER_STROKE_WIDTH.toPx()),
        )
    }
}

private fun DrawScope.drawFlag(
    windVelocity: Float,
    windMaxMagnitude: Float,
    poleColor: Color,
    flagColor: Color,
    center: Offset,
    radius: Float,
) {
    val windFraction = if (windMaxMagnitude > 0f) (abs(windVelocity) / windMaxMagnitude).coerceIn(0f, 1f) else 0f
    val side = if (windVelocity >= 0f) 1f else -1f
    // 0 = hanging straight down (calm), PI/2 = fully horizontal (max wind) - see class doc.
    val thetaRad = windFraction * (Math.PI / 2.0).toFloat()

    val poleX = center.x
    val poleTopY = center.y - radius * POLE_TOP_INSET_FRACTION
    val poleBottomY = center.y + radius * POLE_BOTTOM_INSET_FRACTION
    drawLine(
        color = poleColor,
        start = Offset(poleX, poleTopY),
        end = Offset(poleX, poleBottomY),
        strokeWidth = POLE_STROKE_WIDTH.toPx(),
        cap = StrokeCap.Round,
    )

    val hoistTop = Offset(poleX, poleTopY + radius * FLAG_HOIST_TOP_INSET_FRACTION)
    val hoistBottom = Offset(hoistTop.x, hoistTop.y + radius * FLAG_HOIST_HEIGHT_FRACTION)
    val flagLength = radius * FLAG_LENGTH_FRACTION
    val tip = Offset(
        hoistTop.x + side * flagLength * sin(thetaRad),
        hoistTop.y + flagLength * cos(thetaRad),
    )
    val flagPath = Path().apply {
        moveTo(hoistTop.x, hoistTop.y)
        lineTo(hoistBottom.x, hoistBottom.y)
        lineTo(tip.x, tip.y)
        close()
    }
    drawPath(flagPath, color = flagColor)
}

private const val POLE_TOP_INSET_FRACTION = 0.7f
private const val POLE_BOTTOM_INSET_FRACTION = 0.55f
private const val FLAG_HOIST_TOP_INSET_FRACTION = 0.15f
private const val FLAG_HOIST_HEIGHT_FRACTION = 0.55f
private const val FLAG_LENGTH_FRACTION = 0.85f

/**
 * Averages the color of [photo]'s pixels that map (through [transform] and the terrain's
 * own aspect ratio, exactly the same way [com.scorchedphoto.app.game.GameRenderer] draws
 * the photo as the match backdrop) onto a sparse grid of points inside the on-screen circle
 * at [center]/[radiusPx] - a full per-pixel scan isn't needed for a decorative average.
 */
private fun averageColorInCircle(
    photo: Bitmap,
    transform: WorldTransform,
    terrainWidth: Int,
    terrainHeight: Int,
    center: Offset,
    radiusPx: Float,
): Color {
    val gridSize = 12
    var redSum = 0L
    var greenSum = 0L
    var blueSum = 0L
    var count = 0
    for (iy in 0 until gridSize) {
        val sy = center.y - radiusPx + (2f * radiusPx) * (iy + 0.5f) / gridSize
        for (ix in 0 until gridSize) {
            val sx = center.x - radiusPx + (2f * radiusPx) * (ix + 0.5f) / gridSize
            val dx = sx - center.x
            val dy = sy - center.y
            if (dx * dx + dy * dy > radiusPx * radiusPx) continue

            val worldX = (sx - transform.offsetX) / transform.scale
            val worldY = (sy - transform.offsetY) / transform.scale
            val photoX = ((worldX / terrainWidth) * photo.width).toInt().coerceIn(0, photo.width - 1)
            val photoY = ((worldY / terrainHeight) * photo.height).toInt().coerceIn(0, photo.height - 1)

            val pixel = photo.getPixel(photoX, photoY)
            redSum += android.graphics.Color.red(pixel)
            greenSum += android.graphics.Color.green(pixel)
            blueSum += android.graphics.Color.blue(pixel)
            count++
        }
    }
    if (count == 0) return Color(0xFF808080)
    return Color(
        red = redSum.toFloat() / count / 255f,
        green = greenSum.toFloat() / count / 255f,
        blue = blueSum.toFloat() / count / 255f,
    )
}

/** WCAG-style relative luminance, for a contrast ratio robust to hue as well as
 * brightness - a candidate close in luminance to [background] would blend into it
 * regardless of how different its hue is. */
private fun relativeLuminance(color: Color): Float {
    fun channel(c: Float) = if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)
}

private fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a) + 0.05f
    val lb = relativeLuminance(b) + 0.05f
    return if (la > lb) la / lb else lb / la
}

private fun pickMostContrasting(background: Color, candidates: List<Color>): Color =
    candidates.maxByOrNull { contrastRatio(background, it) } ?: candidates.first()
