package com.scorchedphoto.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ScorchedColorScheme = darkColorScheme(
    primary = ScorchedOrange,
    secondary = ScorchedBlue,
    background = ScorchedNight,
    surface = ScorchedNight,
)

@Composable
fun ScorchedPhotoTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = ScorchedColorScheme,
        typography = ScorchedTypography,
        content = content,
    )
}
