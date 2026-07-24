package com.scorchedphoto.app.home

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
import androidx.compose.ui.unit.dp
import com.scorchedphoto.app.ui.LockScreenOrientation

@Composable
fun HomeScreen(onNewGame: () -> Unit) {
    LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_USER)
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = "Scorched Photo", style = MaterialTheme.typography.headlineLarge)
            Button(onClick = onNewGame, modifier = Modifier.padding(top = 24.dp)) {
                Text("New Game")
            }
        }
    }
}
