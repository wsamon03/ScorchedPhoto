package com.scorchedphoto.app.setup

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

// TODO(Phase 7): wire tank count / human-vs-CPU / difficulty / color config and construct GameEngine.
@Composable
fun GameSetupScreen(onStartMatch: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Game Setup", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onStartMatch, modifier = Modifier.padding(top = 24.dp)) {
                Text("Start Match")
            }
        }
    }
}
