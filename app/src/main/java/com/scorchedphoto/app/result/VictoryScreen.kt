package com.scorchedphoto.app.result

import android.content.pm.ActivityInfo
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.scorchedphoto.app.ui.LockScreenOrientation

@Composable
fun VictoryScreen(
    onRematch: () -> Unit,
    onNewPhoto: () -> Unit,
    onHome: () -> Unit,
    viewModel: VictoryViewModel = hiltViewModel(),
) {
    LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Victory!", style = MaterialTheme.typography.headlineMedium)
            val winnerName = viewModel.winnerName
            if (winnerName != null) {
                val color = viewModel.winnerColor?.let { Color(it) } ?: Color.Unspecified
                Text(
                    text = winnerName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = color,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Button(onClick = onRematch, modifier = Modifier.padding(top = 24.dp)) {
                Text("Rematch")
            }
            Button(onClick = onNewPhoto, modifier = Modifier.padding(top = 12.dp)) {
                Text("New Photo")
            }
            Button(onClick = onHome, modifier = Modifier.padding(top = 12.dp)) {
                Text("Home")
            }
        }
    }
}
