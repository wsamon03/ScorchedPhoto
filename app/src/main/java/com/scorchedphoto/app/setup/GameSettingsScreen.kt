package com.scorchedphoto.app.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scorchedphoto.engine.EdgeType

@Composable
fun GameSettingsScreen(viewModel: GameSetupViewModel, onBack: () -> Unit) {
    val wallType by viewModel.wallType.collectAsStateWithLifecycle()
    val ceilingType by viewModel.ceilingType.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text("Match Settings", style = MaterialTheme.typography.headlineMedium)

            Text("Wall Type", modifier = Modifier.padding(top = 24.dp))
            EdgeTypeDropdown(selected = wallType, onSelect = viewModel::setWallType)

            Text("Ceiling Type", modifier = Modifier.padding(top = 16.dp))
            EdgeTypeDropdown(selected = ceilingType, onSelect = viewModel::setCeilingType)

            Button(onClick = onBack, modifier = Modifier.padding(top = 24.dp)) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun EdgeTypeDropdown(selected: EdgeType?, onSelect: (EdgeType?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }) {
            Text(selected?.displayName ?: "Random")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Random") },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            EdgeType.entries.forEach { edgeType ->
                DropdownMenuItem(
                    text = { Text(edgeType.displayName) },
                    onClick = {
                        onSelect(edgeType)
                        expanded = false
                    },
                )
            }
        }
    }
}
