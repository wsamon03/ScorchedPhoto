package com.scorchedphoto.app.game

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel

// TODO(Phase 8): add the Compose HUD overlay (angle/power sliders, wind indicator,
// health bars, weapon selector, fire button) on top of the AndroidView below, driven by
// viewModel.uiState. TODO(Phase 9): react to uiState.winnerOwnerId via onMatchOver.
@Composable
fun GameScreen(onMatchOver: () -> Unit, viewModel: GameViewModel = hiltViewModel()) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                GameSurfaceView(
                    context = context,
                    engine = viewModel.engine,
                    photo = viewModel.backgroundPhoto,
                    onStateChanged = viewModel::publishState,
                )
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
