package com.scorchedphoto.app.terrainpreview

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

// TODO(Phase 6): run TerrainSegmenter on the captured photo and overlay the detected ground line.
@Composable
fun TerrainPreviewScreen(onAccept: () -> Unit, onRetake: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Terrain Preview", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onAccept, modifier = Modifier.padding(top = 24.dp)) {
                Text("Play")
            }
            Button(onClick = onRetake, modifier = Modifier.padding(top = 12.dp)) {
                Text("Retake")
            }
        }
    }
}
