package com.scorchedphoto.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView

/**
 * Requests the host Activity's orientation for as long as this composable is in
 * composition. Every screen in the nav graph calls this once with its own desired
 * value on entry - no exit/cleanup needed, since whichever screen is entered next
 * always sets its own value explicitly.
 */
@Composable
fun LockScreenOrientation(orientation: Int) {
    val view = LocalView.current
    LaunchedEffect(orientation) {
        (view.context as? ComponentActivity)?.requestedOrientation = orientation
    }
}
