package com.scorchedphoto.app.capture

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

// TODO(Phase 5): wire CameraX capture + Android Photo Picker in place of this placeholder.
@Composable
fun PhotoSourceScreen(onPhotoReady: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Choose a Photo", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onPhotoReady, modifier = Modifier.padding(top = 24.dp)) {
                Text("Take Photo")
            }
            Button(onClick = onPhotoReady, modifier = Modifier.padding(top = 12.dp)) {
                Text("Choose from Gallery")
            }
        }
    }
}
