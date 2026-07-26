package com.scorchedphoto.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Puts the host Activity edge-to-edge and hides the status bar for as long as this composable
 * is in composition, so the screen's own measured size is the entire physical display rather
 * than whatever's left after system-bar insets are subtracted. Every screen that draws a
 * [com.scorchedphoto.app.game.WorldTransform]-mapped surface needs this - a screen measured
 * with insets consumed (e.g. inside a default `Scaffold`) would see a different aspect ratio
 * than the real play canvas does, throwing off exactly what fraction of the world ends up
 * visible on screen.
 */
@Composable
fun ImmersiveMode() {
    val view = LocalView.current
    LaunchedEffect(Unit) {
        val window = (view.context as? ComponentActivity)?.window ?: return@LaunchedEffect
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, view) ?: return@LaunchedEffect
        insetsController.hide(WindowInsetsCompat.Type.statusBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
