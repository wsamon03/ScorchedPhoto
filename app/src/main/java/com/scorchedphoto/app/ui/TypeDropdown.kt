package com.scorchedphoto.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** [colorFor] returns an [android.graphics.Color] Int (ARGB) - [com.scorchedphoto.app.game.GameRenderer]
 * draws directly with that, but Compose's [Text] wants its own [androidx.compose.ui.graphics.Color]
 * type, so every option's text color here goes through this converter. An option with no color
 * ([colorFor] returns null - [com.scorchedphoto.engine.EdgeType.NONE]/[com.scorchedphoto.engine.FloorType.HOLE])
 * falls back to [Color.Unspecified] (the theme's own default) rather than a hardcoded one. */
private fun <T> optionTextColor(option: T, colorFor: (T) -> Int?): Color = colorFor(option)?.let(::Color) ?: Color.Unspecified

/** Shared "pick one value, or Random" dropdown for a wall/ceiling ([com.scorchedphoto.engine.EdgeType])
 * or floor ([com.scorchedphoto.engine.FloorType]) setting - used both by [com.scorchedphoto.app.setup.GameSettingsScreen]
 * (a single match's own wall/ceiling/floor) and [com.scorchedphoto.app.settings.SettingsScreen]
 * (the title screen's persisted starting defaults for new matches), whose option text is
 * colored to match how that value actually renders in-game (see [colorFor]/[com.scorchedphoto.app.game.colorFor]),
 * so a single composable parameterized over the enum type avoids near-identical copies
 * drifting apart. */
@Composable
fun <T> TypeDropdown(
    selected: T?,
    options: List<T>,
    displayName: (T) -> String,
    colorFor: (T) -> Int?,
    onSelect: (T?) -> Unit,
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
            DropdownMenuItem(
                text = { Text("Random") },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
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
