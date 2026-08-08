package com.scorchedphoto.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scorchedphoto.app.game.colorFor
import com.scorchedphoto.app.setup.MAX_TANKS
import com.scorchedphoto.app.setup.MIN_TANKS
import com.scorchedphoto.app.ui.TypeDropdown
import com.scorchedphoto.engine.EdgeType
import com.scorchedphoto.engine.FloorType
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onDeathPhrases: () -> Unit,
    onAttackPhrases: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val audioSettings by viewModel.audioSettings.collectAsStateWithLifecycle()
    val matchDefaults by viewModel.matchDefaults.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
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

            Text(
                "New Match Defaults",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 32.dp),
            )

            Text("Players", modifier = Modifier.padding(top = 16.dp))
            PlayerCountStepper(
                count = matchDefaults.playerCount,
                onCountChange = viewModel::setDefaultPlayerCount,
                modifier = Modifier.padding(top = 4.dp),
            )

            Text("Wall Type", modifier = Modifier.padding(top = 16.dp))
            TypeDropdown(
                selected = matchDefaults.wallType,
                options = EdgeType.entries,
                displayName = { it.displayName },
                colorFor = { colorFor(it) },
                onSelect = viewModel::setDefaultWallType,
            )

            Text("Ceiling Type", modifier = Modifier.padding(top = 16.dp))
            TypeDropdown(
                selected = matchDefaults.ceilingType,
                options = EdgeType.entries,
                displayName = { it.displayName },
                colorFor = { colorFor(it) },
                onSelect = viewModel::setDefaultCeilingType,
            )

            Text("Floor Type", modifier = Modifier.padding(top = 16.dp))
            TypeDropdown(
                selected = matchDefaults.floorType,
                options = FloorType.entries,
                displayName = { it.displayName },
                colorFor = { colorFor(it) },
                onSelect = viewModel::setDefaultFloorType,
            )

            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            ) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun PlayerCountStepper(count: Int, onCountChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Button(onClick = { onCountChange(count - 1) }, enabled = count > MIN_TANKS) {
            Text("-")
        }
        Text(count.toString(), modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = { onCountChange(count + 1) }, enabled = count < MAX_TANKS) {
            Text("+")
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
