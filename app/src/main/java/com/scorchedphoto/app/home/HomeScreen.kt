package com.scorchedphoto.app.home

import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.systemBarsPadding
import com.scorchedphoto.app.R
import com.scorchedphoto.app.ui.LockScreenOrientation

@Composable
fun HomeScreen(onNewGame: () -> Unit) {
    LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_USER)
    Box(modifier = Modifier.fillMaxSize()) {
        // Background icon
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "Scorched Photo",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(alpha = 0.6f),
            contentScale = androidx.compose.foundation.layout.ContentScale.Crop,
        )

        // Semi-transparent overlay for better text visibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
        )

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.Top),
        ) {
            Text(
                text = "Scorched Photo",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                modifier = Modifier.padding(top = 48.dp)
            )

            Box(modifier = Modifier.weight(1f))

            Button(onClick = onNewGame) {
                Text("New Game")
            }

            Box(modifier = Modifier.padding(bottom = 32.dp))
        }
    }
}
