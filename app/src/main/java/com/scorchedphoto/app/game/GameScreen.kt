package com.scorchedphoto.app.game

import android.content.pm.ActivityInfo
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scorchedphoto.app.game.hud.HudOverlay
import com.scorchedphoto.app.game.turntransition.PassDeviceScreen
import com.scorchedphoto.app.ui.LockScreenOrientation
import com.scorchedphoto.engine.MatchPhase

@Composable
fun GameScreen(onMatchOver: () -> Unit, viewModel: GameViewModel = hiltViewModel()) {
    LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val view = LocalView.current

    LaunchedEffect(Unit) {
        val window = (view.context as? androidx.activity.ComponentActivity)?.window ?: return@LaunchedEffect
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, view) ?: return@LaunchedEffect
        insetsController.hide(WindowInsetsCompat.Type.statusBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    LaunchedEffect(uiState.winnerOwnerId) {
        if (uiState.winnerOwnerId != null) {
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
