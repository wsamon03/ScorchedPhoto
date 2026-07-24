package com.scorchedphoto.app.setup

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scorchedphoto.app.tts.VoiceOption
import com.scorchedphoto.app.ui.LockScreenOrientation
import com.scorchedphoto.engine.ai.Difficulty
import com.scorchedphoto.engine.tanks.TankShape

private const val RHVOICE_URL = "https://f-droid.org/packages/com.github.olga_yakovleva.rhvoice.android/"
private val PITCH_RATE_RANGE = 0.5f..2.0f

@Composable
fun GameSetupScreen(onStartMatch: () -> Unit, viewModel: GameSetupViewModel = hiltViewModel()) {
    LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_USER)
    val tankConfigs by viewModel.tankConfigs.collectAsStateWithLifecycle()
    val availableVoices by viewModel.availableVoices.collectAsStateWithLifecycle()
    var showMoreVoicesDialog by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text("Game Setup", style = MaterialTheme.typography.headlineMedium)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Tanks: ${tankConfigs.size}")
                Row {
                    Button(onClick = viewModel::removeTank, enabled = viewModel.canRemoveTank) {
                        Text("-")
                    }
                    Button(
                        onClick = viewModel::addTank,
                        modifier = Modifier.padding(start = 8.dp),
                        enabled = viewModel.canAddTank,
                    ) {
                        Text("+")
                    }
                }
            }

            Text(
                "Want More Voices?",
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable { showMoreVoicesDialog = true }
                    .padding(bottom = 8.dp),
            )

            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(tankConfigs) { index, config ->
                    TankConfigRow(
                        config = config,
                        availableVoices = availableVoices,
                        onToggleCpu = { viewModel.toggleCpu(index) },
                        onDifficultyChange = { viewModel.setDifficulty(index, it) },
                        onShapeChange = { viewModel.setShape(index, it) },
                        onVoiceChange = { viewModel.setVoice(index, it) },
                        onPitchChange = { viewModel.setPitch(index, it) },
                        onSpeechRateChange = { viewModel.setSpeechRate(index, it) },
                        onTest = { viewModel.testVoice(index) },
                    )
                }
            }

            Button(
                onClick = {
                    viewModel.commitAndStart()
                    onStartMatch()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text("Next")
            }
        }
    }

    if (showMoreVoicesDialog) {
        WantMoreVoicesDialog(onDismiss = { showMoreVoicesDialog = false })
    }
}

@Composable
private fun WantMoreVoicesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Want More Voices?") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "More voices for your current text-to-speech engine are installed " +
                        "through your phone's system settings (Settings → Accessibility " +
                        "→ Text-to-speech output → your engine → Install voice " +
                        "data). Installed voices show up here automatically.",
                )
                Text(
                    "For entirely different-sounding voices, install an alternative " +
                        "text-to-speech engine app and set it as your device's default in " +
                        "system settings - for example, RHVoice, a free and open-source " +
                        "option:",
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    RHVOICE_URL,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RHVOICE_URL)))
                        }
                        .padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Not every engine supports this shortcut into its own voice-download UI.
                    try {
                        context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                    } catch (_: ActivityNotFoundException) {
                        // No handler for it on this device/engine - the dialog's own
                        // instructions above are the fallback path.
                    }
                },
            ) {
                Text("Open Voice Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun TankConfigRow(
    config: TankConfig,
    availableVoices: List<VoiceOption>,
    onToggleCpu: () -> Unit,
    onDifficultyChange: (Difficulty) -> Unit,
    onShapeChange: (TankShape) -> Unit,
    onVoiceChange: (String?) -> Unit,
    onPitchChange: (Float) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onTest: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(Color(config.color), CircleShape),
                )
                Text(config.name, modifier = Modifier.padding(start = 8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (config.isCpu) "CPU" else "Human")
                Switch(checked = config.isCpu, onCheckedChange = { onToggleCpu() })
                if (config.isCpu) {
                    DifficultyDropdown(selected = config.difficulty, onSelect = onDifficultyChange)
                }
            }
        }
        TankShapePicker(
            selected = config.shape,
            tint = Color(config.color),
            onSelect = onShapeChange,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoiceDropdown(selected = config.voiceId, options = availableVoices, onSelect = onVoiceChange)
            Button(onClick = onTest, modifier = Modifier.padding(start = 8.dp)) {
                Text("Test")
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Pitch", modifier = Modifier.padding(end = 4.dp))
            Slider(
                value = config.pitch,
                onValueChange = onPitchChange,
                valueRange = PITCH_RATE_RANGE,
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Rate", modifier = Modifier.padding(end = 4.dp))
            Slider(
                value = config.speechRate,
                onValueChange = onSpeechRateChange,
                valueRange = PITCH_RATE_RANGE,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun VoiceDropdown(selected: String?, options: List<VoiceOption>, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.id == selected }?.displayName ?: VoiceOption.SYSTEM_DEFAULT.displayName
    Box {
        Button(onClick = { expanded = true }) {
            Text(selectedLabel)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { voice ->
                DropdownMenuItem(
                    text = { Text(voice.displayName) },
                    onClick = {
                        onSelect(voice.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun TankShapePicker(
    selected: TankShape,
    tint: Color,
    onSelect: (TankShape) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TankShape.entries.forEach { shape ->
            val isSelected = shape == selected
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        if (isSelected) tint.copy(alpha = 0.25f) else Color.Transparent,
                        RoundedCornerShape(6.dp),
                    )
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) tint else Color.Gray.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .clickable { onSelect(shape) }
                    .padding(4.dp),
            ) {
                TankShapeIcon(shape = shape, tint = tint, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** Draws [shape]'s normalized outline (see [TankShape]) as a small filled preview icon. */
@Composable
private fun TankShapeIcon(shape: TankShape, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val path = Path()
        shape.outline.forEachIndexed { index, (nx, ny) ->
            val x = size.width / 2f + nx * size.width / 2f
            val y = size.height + ny * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color = tint)
    }
}

@Composable
private fun DifficultyDropdown(selected: Difficulty, onSelect: (Difficulty) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }) {
            Text(selected.name)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Difficulty.entries.forEach { difficulty ->
                DropdownMenuItem(
                    text = { Text(difficulty.name) },
                    onClick = {
                        onSelect(difficulty)
                        expanded = false
                    },
                )
            }
        }
    }
}
