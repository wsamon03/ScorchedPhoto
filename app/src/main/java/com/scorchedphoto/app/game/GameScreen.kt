package com.scorchedphoto.app.game

import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scorchedphoto.app.game.hud.HudOverlay
import com.scorchedphoto.app.game.turntransition.PassDeviceScreen
import com.scorchedphoto.app.ui.ImmersiveMode
import com.scorchedphoto.app.ui.LockScreenOrientation
import com.scorchedphoto.engine.MatchPhase

@Composable
fun GameScreen(onMatchOver: () -> Unit, viewModel: GameViewModel = hiltViewModel()) {
    LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
    ImmersiveMode()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.winnerOwnerIds) {
        if (uiState.winnerOwnerIds.isNotEmpty()) {
            onMatchOver()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                GameSurfaceView(
                    context = context,
                    engine = viewModel.engine,
                    photo = viewModel.backgroundPhoto,
                    commandQueue = viewModel.commandQueue,
                    onStateChanged = viewModel::publishState,
                    onBurnMessageAssigned = viewModel::onBurnMessageAssigned,
                    soundController = viewModel.soundController,
                )
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Shown once per new human tank's turn (never for CPU turns) so the outgoing
        // player doesn't see the incoming player's aim setup mid-transition.
        var acknowledgedTankId by remember { mutableStateOf<Int?>(null) }
        val needsPassDevice = uiState.phase == MatchPhase.AIMING &&
            !uiState.currentTankIsCpu &&
            uiState.currentTankId != null &&
            uiState.currentTankId != acknowledgedTankId

        if (needsPassDevice) {
            val nextTankName = uiState.tanks.firstOrNull { it.id == uiState.currentTankId }?.name ?: "Player"
            PassDeviceScreen(
                nextPlayerName = nextTankName,
                onReady = { acknowledgedTankId = uiState.currentTankId },
            )
        } else {
            HudOverlay(uiState = uiState, onCommand = viewModel::submitCommand, photo = viewModel.backgroundPhoto)
        }
    }
}
