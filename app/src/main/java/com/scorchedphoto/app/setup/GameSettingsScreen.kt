package com.scorchedphoto.app.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scorchedphoto.app.game.colorFor
import com.scorchedphoto.app.ui.TypeDropdown
import com.scorchedphoto.engine.EdgeType
import com.scorchedphoto.engine.FloorType

@Composable
fun GameSettingsScreen(viewModel: GameSetupViewModel, onBack: () -> Unit) {
    val wallType by viewModel.wallType.collectAsStateWithLifecycle()
    val ceilingType by viewModel.ceilingType.collectAsStateWithLifecycle()
    val floorType by viewModel.floorType.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text("Match Settings", style = MaterialTheme.typography.headlineMedium)

            Text("Wall Type", modifier = Modifier.padding(top = 24.dp))
            TypeDropdown(
                selected = wallType,
                options = EdgeType.entries,
                displayName = { it.displayName },
                colorFor = { colorFor(it) },
                onSelect = viewModel::setWallType,
            )

            Text("Ceiling Type", modifier = Modifier.padding(top = 16.dp))
            TypeDropdown(
                selected = ceilingType,
                options = EdgeType.entries,
                displayName = { it.displayName },
                colorFor = { colorFor(it) },
                onSelect = viewModel::setCeilingType,
            )

            Text("Floor Type", modifier = Modifier.padding(top = 16.dp))
            TypeDropdown(
                selected = floorType,
                options = FloorType.entries,
                displayName = { it.displayName },
                colorFor = { colorFor(it) },
                onSelect = viewModel::setFloorType,
            )

            Button(onClick = onBack, modifier = Modifier.padding(top = 24.dp)) {
                Text("Back")
            }
        }
    }
}
