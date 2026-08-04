package com.scorchedphoto.app.tournament

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MultiGameSetupScreen(
    onNext: () -> Unit,
    viewModel: MultiGameSetupViewModel = hiltViewModel(),
) {
    val selectedType by viewModel.selectedType.collectAsStateWithLifecycle()
    val pointsToWin by viewModel.pointsToWin.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text("Multi-Game", style = MaterialTheme.typography.headlineMedium)

            LazyColumn(modifier = Modifier.weight(1f).padding(top = 16.dp)) {
                items(MultiGameType.entries) { type ->
                    MultiGameTypeRow(
                        type = type,
                        selected = type == selectedType,
                        onSelect = { viewModel.selectType(type) },
                    )
                }
            }

            if (selectedType.usesPointsToWin) {
                Text("Points to Win", modifier = Modifier.padding(top = 8.dp))
                PointsToWinStepper(
                    points = pointsToWin,
                    onPointsChange = viewModel::setPointsToWin,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Button(
                onClick = { viewModel.commit(); onNext() },
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text("Next")
            }
        }
    }
}

@Composable
private fun MultiGameTypeRow(type: MultiGameType, selected: Boolean, onSelect: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Button(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
            Text(if (selected) "✓ ${type.displayName}" else type.displayName)
        }
        if (selected) {
            Text(
                type.description,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PointsToWinStepper(points: Int, onPointsChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Button(onClick = { onPointsChange(points - 1) }, enabled = points > TournamentConfig.MIN_POINTS_TO_WIN) {
            Text("-")
        }
        Text(points.toString(), modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = { onPointsChange(points + 1) }) {
            Text("+")
        }
    }
}
