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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.scorchedphoto.app.tournament.TournamentConfig
import com.scorchedphoto.app.tournament.TournamentTankState
import com.scorchedphoto.app.ui.LockScreenOrientation

@Composable
fun VictoryScreen(
    onRematch: () -> Unit,
    onNewPhoto: () -> Unit,
    onHome: () -> Unit,
    viewModel: VictoryViewModel = hiltViewModel(),
) {
    LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
    val winners = viewModel.winners
    val tournamentConfig = viewModel.tournamentConfig
    val overallWinner = viewModel.overallWinner
    val goHome = { viewModel.onHome(); onHome() }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                tournamentConfig == null -> {
                    MatchResultHeader(winners)
                    Button(onClick = onRematch, modifier = Modifier.padding(top = 24.dp)) {
                        Text("Rematch")
                    }
                    Button(onClick = onNewPhoto, modifier = Modifier.padding(top = 12.dp)) {
                        Text("New Photo")
                    }
                    Button(onClick = goHome, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Home")
                    }
                }
                overallWinner != null -> {
                    Text("Tournament Complete!", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = overallWinner.name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color(overallWinner.color),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TournamentStandings(
                        tanks = viewModel.tournamentTanks,
                        config = tournamentConfig,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Button(onClick = goHome, modifier = Modifier.padding(top = 24.dp)) {
                        Text("Home")
                    }
                }
                else -> {
                    MatchResultHeader(winners)
                    TournamentStandings(
                        tanks = viewModel.tournamentTanks,
                        config = tournamentConfig,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Button(onClick = onRematch, modifier = Modifier.padding(top = 24.dp)) {
                        Text("Next Game")
                    }
                    Button(onClick = goHome, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Quit Tournament")
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchResultHeader(winners: List<MatchWinner>) {
    Text(
        if (winners.size > 1) "Tie!" else "Victory!",
        style = MaterialTheme.typography.headlineMedium,
    )
    winners.forEach { winner ->
        Text(
            text = winner.name,
            style = MaterialTheme.typography.headlineSmall,
            color = Color(winner.color),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun TournamentStandings(tanks: List<TournamentTankState>, config: TournamentConfig, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Standings", style = MaterialTheme.typography.titleMedium)
        tanks.sortedByDescending { it.points }.forEach { tank ->
            val status = buildList {
                if (config.type.usesPointsToWin) add("${tank.points} pts")
                if (tank.knockedOut) add("knocked out")
                if (tank.hasImmunity) add("immune next game")
            }.joinToString(" · ")
            Text(
                text = if (status.isEmpty()) tank.name else "${tank.name} - $status",
                color = Color(tank.color),
                style = MaterialTheme.typography.bodyMedium.copy(
                    textDecoration = if (tank.knockedOut) TextDecoration.LineThrough else TextDecoration.None,
                ),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
