package com.scorchedphoto.app.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// TODO(Phase 7/8): host AndroidView(GameSurfaceView) as the base layer plus the Compose HUD overlay.
@Composable
fun GameScreen(onMatchOver: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Match in Progress", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onMatchOver, modifier = Modifier.padding(top = 24.dp)) {
                Text("End Match")
            }
        }
    }
}
