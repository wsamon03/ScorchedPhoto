package com.scorchedphoto.app.setup

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scorchedphoto.engine.ai.Difficulty
import com.scorchedphoto.engine.tanks.TankShape

@Composable
fun GameSetupScreen(onStartMatch: () -> Unit, viewModel: GameSetupViewModel = hiltViewModel()) {
    val tankConfigs by viewModel.tankConfigs.collectAsStateWithLifecycle()

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

            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(tankConfigs) { index, config ->
                    TankConfigRow(
                        config = config,
                        onToggleCpu = { viewModel.toggleCpu(index) },
                        onDifficultyChange = { viewModel.setDifficulty(index, it) },
                        onShapeChange = { viewModel.setShape(index, it) },
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
                Text("Start Match")
            }
        }
    }
}

@Composable
private fun TankConfigRow(
    config: TankConfig,
    onToggleCpu: () -> Unit,
    onDifficultyChange: (Difficulty) -> Unit,
    onShapeChange: (TankShape) -> Unit,
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
