package com.scorchedphoto.app.game.hud

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.scorchedphoto.app.game.GameCommand
import com.scorchedphoto.app.game.GameUiState
import com.scorchedphoto.app.game.WorldTransform
import com.scorchedphoto.engine.MatchPhase
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// Conservative fixed footprints for the two corner-anchored HUD groups whose size doesn't
// meaningfully vary at runtime - kept numerically in sync by hand with WindIndicator's own
// CIRCLE_DIAMETER and PowerChargeButton's own BUTTON_SIZE (same "mirrored constant" pattern
// GameRenderer.GROWTH_SECONDS uses for GameEngine.DEATH_EXPLOSION_GROWTH_SECONDS), each padded
// out to the group's own real on-screen footprint (content + its own align().padding()). See
// resolveHudCorner's callers below - HealthBarRow's own footprint varies with tank count, so
// it's measured live instead (healthRowSizePx).
private val WIND_GROUP_FOOTPRINT_DP = DpSize(80.dp, 80.dp) // WindIndicator's 64dp circle + 8dp padding each side
private val WEAPON_FIRE_GROUP_SIZE_DP = DpSize(250.dp, 110.dp) // weapon pill + gap + 72dp fire button + 16dp padding each side
private val HEALTH_ROW_FALLBACK_SIZE_DP = DpSize(100.dp, 80.dp) // a conservative single-tank guess, used only before the first real onSizeChanged fires

private const val READOUT_VISIBLE_MILLIS = 2500L

/** A just-fired shot's exact values, snapshotted (including screen position) at release
 * time so the labels stay put even after the turn moves on to a different tank. */
private data class FiredShotReadout(
    val angleDeg: Float,
    val power: Float,
    val angleLabelX: Float,
    val angleLabelY: Float,
    val powerLabelX: Float,
    val powerLabelY: Float,
)

@Composable
fun HudOverlay(
    uiState: GameUiState,
    onCommand: (GameCommand) -> Unit,
    photo: Bitmap?,
    modifier: Modifier = Modifier,
) {
    var canvasSizePx by remember { mutableStateOf(IntSize.Zero) }
    val powerChargeState = rememberPowerChargeState()
    PowerChargeAnimator(powerChargeState, maxPower = uiState.currentMaxPower)

    var lastFired by remember { mutableStateOf<FiredShotReadout?>(null) }
    LaunchedEffect(lastFired) {
        val readout = lastFired ?: return@LaunchedEffect
        delay(READOUT_VISIBLE_MILLIS)
        if (lastFired === readout) lastFired = null
    }

    val canFire = uiState.phase == MatchPhase.AIMING && !uiState.currentTankIsCpu
    val density = LocalDensity.current
    val hasValidCanvas = canvasSizePx.width > 0 && canvasSizePx.height > 0

    // The same uniform, letterboxed fit GameRenderer draws the world with - see
    // WorldTransform's doc for why independent x/y scale factors aren't used: this HUD
    // layer has to agree exactly with the renderer below it, or touch targets (the angle
    // ring, the power meter) drift away from where the tank is actually drawn.
    val transform = WorldTransform.fit(
        canvasSizePx.width.toFloat(),
        canvasSizePx.height.toFloat(),
        uiState.terrainWidth.toFloat(),
        uiState.terrainHeight.toFloat(),
    )
    val tankScreenX = if (hasValidCanvas) transform.screenX(uiState.currentTankX) else 0f
    val tankScreenY = if (hasValidCanvas) transform.screenY(uiState.currentTankY) else 0f

    val touchRadiusPx = with(density) { TOUCH_RADIUS.toPx() }

    // No HUD element may overlay the active tank or its AngleRing while it's live - a circle
    // matching AngleRing's own touch footprint, `null` (nothing to protect against) whenever
    // it isn't shown. Each corner-anchored group below is resolved against this in a fixed
    // priority order (health, wind, weapon+fire), threading claimed corners forward so a
    // lower-priority group never lands on a corner a higher-priority one already took.
    var healthRowSizePx by remember { mutableStateOf(IntSize.Zero) }
    val protectedZone = if (canFire && hasValidCanvas) {
        ProtectedZone(Offset(tankScreenX, tankScreenY), touchRadiusPx)
    } else {
        null
    }
    val healthRowSize = if (healthRowSizePx != IntSize.Zero) {
        Size(healthRowSizePx.width.toFloat(), healthRowSizePx.height.toFloat())
    } else {
        with(density) { HEALTH_ROW_FALLBACK_SIZE_DP.toSize() }
    }
    val windGroupSize = with(density) { WIND_GROUP_FOOTPRINT_DP.toSize() }
    val weaponFireGroupSize = with(density) { WEAPON_FIRE_GROUP_SIZE_DP.toSize() }
    val healthRowCorner = resolveHudCorner(HudCorner.TOP_START, healthRowSize, canvasSizePx, protectedZone, emptySet())
    val windCorner = resolveHudCorner(HudCorner.TOP_END, windGroupSize, canvasSizePx, protectedZone, setOf(healthRowCorner))
    val weaponFireCorner = resolveHudCorner(
        HudCorner.BOTTOM_END, weaponFireGroupSize, canvasSizePx, protectedZone, setOf(healthRowCorner, windCorner),
    )

    val gapPx = with(density) { POWER_METER_HORIZONTAL_GAP.toPx() }
    val meterWidthPx = with(density) { POWER_METER_WIDTH.toPx() }
    val meterHeightPx = with(density) { POWER_METER_HEIGHT.toPx() }
    // Leans toward the screen's horizontal middle from the tank first (the common case
    // needs no further correction), and is vertically centered on the tank's own height by
    // default - but a tank can sit anywhere in the canvas, including right up against an
    // edge (e.g. a peak near the top, a valley near the bottom), so both axes are still
    // coerced into the canvas bounds afterward to guarantee the bar's full size never
    // renders off-screen.
    val towardCenter = if (tankScreenX < canvasSizePx.width / 2f) 1f else -1f
    val meterX = (
        tankScreenX + towardCenter * (touchRadiusPx + gapPx) -
            if (towardCenter < 0f) meterWidthPx else 0f
        ).coerceIn(0f, (canvasSizePx.width - meterWidthPx).coerceAtLeast(0f))
    val meterY = (tankScreenY - meterHeightPx / 2f)
        .coerceIn(0f, (canvasSizePx.height - meterHeightPx).coerceAtLeast(0f))

    // Wraps onCommand to snapshot a fired shot's angle/power/position for the brief
    // post-release readout - AngleRing's own SetAngle drags don't go through this.
    val onFireCommand: (GameCommand) -> Unit = { command ->
        if (command is GameCommand.FireWithPower) {
            lastFired = FiredShotReadout(
                angleDeg = uiState.currentAngleDeg,
                power = command.power,
                angleLabelX = tankScreenX,
                angleLabelY = tankScreenY + touchRadiusPx + with(density) { 4.dp.toPx() },
                powerLabelX = meterX,
                powerLabelY = meterY - with(density) { 20.dp.toPx() },
            )
        }
        onCommand(command)
    }

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
                    .chargeFireGestures(powerChargeState, onFireCommand),
            )
        }

        HealthBarRow(
            tanks = uiState.tanks,
            currentTankId = uiState.currentTankId,
            modifier = Modifier
                .align(healthRowCorner.toAlignment())
                .padding(8.dp)
                .onSizeChanged { healthRowSizePx = it },
        )
        WindIndicator(
            windVelocity = uiState.windVelocity,
            windMaxMagnitude = uiState.windMaxMagnitude,
            photo = photo,
            transform = transform,
            terrainWidth = uiState.terrainWidth,
            terrainHeight = uiState.terrainHeight,
            modifier = Modifier
                .align(windCorner.toAlignment())
                .padding(8.dp),
        )

        if (canFire && hasValidCanvas) {
            AngleRing(
                centerXPx = tankScreenX,
                centerYPx = tankScreenY,
                angleDeg = uiState.currentAngleDeg,
                onCommand = onCommand,
            )
            PowerMeterBar(
                state = powerChargeState,
                maxPower = uiState.currentMaxPower,
                modifier = Modifier.offset { IntOffset(meterX.roundToInt(), meterY.roundToInt()) },
            )
        }

        if (canFire) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(weaponFireCorner.toAlignment())
                    .padding(16.dp),
            ) {
                WeaponPicker(
                    weapons = uiState.weapons,
                    enabled = canFire,
                    onCommand = onCommand,
                    modifier = Modifier.padding(end = 8.dp),
                )
                PowerChargeButton(
                    state = powerChargeState,
                    maxPower = uiState.currentMaxPower,
                    onCommand = onFireCommand,
                )
            }
        }

        lastFired?.let { readout ->
            Text(
                text = "${readout.angleDeg.roundToInt()}°",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.offset {
                    IntOffset(readout.angleLabelX.roundToInt(), readout.angleLabelY.roundToInt())
                },
            )
            Text(
                text = "${readout.power.roundToInt()}",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.offset {
                    IntOffset(readout.powerLabelX.roundToInt(), readout.powerLabelY.roundToInt())
                },
            )
        }
    }
}
