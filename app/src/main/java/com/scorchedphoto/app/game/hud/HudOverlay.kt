package com.scorchedphoto.app.game.hud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.scorchedphoto.app.game.GameCommand
import com.scorchedphoto.app.game.GameUiState
import com.scorchedphoto.engine.MatchPhase

@Composable
fun HudOverlay(uiState: GameUiState, onCommand: (GameCommand) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
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

        val canFire = uiState.phase == MatchPhase.AIMING && !uiState.currentTankIsCpu
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            WeaponSelector(weapons = uiState.weapons, enabled = canFire, onCommand = onCommand)
            AimControls(
                currentTankId = uiState.currentTankId,
                angleDeg = uiState.currentAngleDeg,
                power = uiState.currentPower,
                phase = uiState.phase,
                canFire = canFire,
                onCommand = onCommand,
            )
        }
    }
}
