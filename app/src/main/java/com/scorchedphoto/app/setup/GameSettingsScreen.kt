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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scorchedphoto.app.game.PhotoUsageMode
import com.scorchedphoto.app.game.colorFor
import com.scorchedphoto.engine.EdgeType
import com.scorchedphoto.engine.FloorType

@Composable
fun GameSettingsScreen(viewModel: GameSetupViewModel, onBack: () -> Unit) {
    val wallType by viewModel.wallType.collectAsStateWithLifecycle()
    val ceilingType by viewModel.ceilingType.collectAsStateWithLifecycle()
    val floorType by viewModel.floorType.collectAsStateWithLifecycle()
    val photoUsageMode by viewModel.photoUsageMode.collectAsStateWithLifecycle()

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

            Text("Photo Usage", modifier = Modifier.padding(top = 16.dp))
            TypeDropdown(
                selected = photoUsageMode,
                options = PhotoUsageMode.entries,
                displayName = { it.displayName },
                colorFor = { null },
                onSelect = { viewModel.setPhotoUsageMode(it ?: PhotoUsageMode.BACKGROUND) },
                includeRandom = false,
            )

            Button(onClick = onBack, modifier = Modifier.padding(top = 24.dp)) {
                Text("Back")
            }
        }
    }
}

/** [colorFor] returns an [android.graphics.Color] Int (ARGB) - [com.scorchedphoto.app.game.GameRenderer]
 * draws directly with that, but Compose's [Text] wants its own [androidx.compose.ui.graphics.Color]
 * type, so every option's text color here goes through this converter. An option with no color
 * ([colorFor] returns null - [EdgeType.NONE]/[FloorType.HOLE]) falls back to [Color.Unspecified]
 * (the theme's own default) rather than a hardcoded one. */
private fun <T> optionTextColor(option: T, colorFor: (T) -> Int?): Color = colorFor(option)?.let(::Color) ?: Color.Unspecified

/** Generic dropdown shared by the wall/ceiling ([EdgeType]) and floor ([FloorType]) settings
 * rows below - both are "pick one value, or Random" pickers whose option text is colored to
 * match how that value actually renders in-game (see [colorFor]/[com.scorchedphoto.app.game.colorFor]),
 * so a single composable parameterized over the enum type avoids two near-identical copies
 * drifting apart. */
@Composable
private fun <T> TypeDropdown(
    selected: T?,
    options: List<T>,
    displayName: (T) -> String,
    colorFor: (T) -> Int?,
    onSelect: (T?) -> Unit,
    // false for pickers with no "Random" concept (e.g. Photo Usage - a deliberate user choice
    // with a real default, not a per-side value that can be independently rolled at commit
    // time like wall/ceiling/floor).
    includeRandom: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }) {
            Text(
                selected?.let(displayName) ?: "Random",
                color = selected?.let { optionTextColor(it, colorFor) } ?: Color.Unspecified,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (includeRandom) {
                DropdownMenuItem(
                    text = { Text("Random") },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    },
                )
            }
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(displayName(option), color = optionTextColor(option, colorFor)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
