package com.scorchedphoto.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onDeathPhrases: () -> Unit,
    onAttackPhrases: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val audioSettings by viewModel.audioSettings.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)

            VolumeSlider(
                label = "Music",
                volume = audioSettings.musicVolume,
                onVolumeChange = viewModel::setMusicVolume,
                modifier = Modifier.padding(top = 24.dp),
            )
            VolumeSlider(
                label = "Sound Effects",
                volume = audioSettings.sfxVolume,
                onVolumeChange = viewModel::setSfxVolume,
                modifier = Modifier.padding(top = 12.dp),
            )
            VolumeSlider(
                label = "Voices",
                volume = audioSettings.voiceVolume,
                onVolumeChange = viewModel::setVoiceVolume,
                modifier = Modifier.padding(top = 12.dp),
            )

            Button(onClick = onDeathPhrases, modifier = Modifier.padding(top = 24.dp)) {
                Text("Death Phrases")
            }
            Button(onClick = onAttackPhrases, modifier = Modifier.padding(top = 8.dp)) {
                Text("Attack Phrases")
            }

            Button(onClick = onBack, modifier = Modifier.padding(top = 24.dp)) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun VolumeSlider(
    label: String,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label)
            Text("${(volume * 100).roundToInt()}%")
        }
        Slider(
            value = volume,
            onValueChange = onVolumeChange,
            valueRange = 0f..1f,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        )
    }
}
