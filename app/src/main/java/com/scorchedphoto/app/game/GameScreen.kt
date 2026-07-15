package com.scorchedphoto.app.game

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scorchedphoto.app.game.hud.HudOverlay

// TODO(Phase 9): react to uiState.winnerOwnerId via onMatchOver (navigate to Victory),
// and auto-play CPU turns instead of waiting on HUD input a CPU tank never sends.
@Composable
fun GameScreen(onMatchOver: () -> Unit, viewModel: GameViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                GameSurfaceView(
                    context = context,
                    engine = viewModel.engine,
                    photo = viewModel.backgroundPhoto,
                    commandQueue = viewModel.commandQueue,
                    onStateChanged = viewModel::publishState,
                )
            },
            modifier = Modifier.fillMaxSize(),
        )
        HudOverlay(uiState = uiState, onCommand = viewModel::submitCommand)
    }
}
