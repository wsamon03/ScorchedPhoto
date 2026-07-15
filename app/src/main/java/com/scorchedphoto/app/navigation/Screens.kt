package com.scorchedphoto.app.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object PhotoSource : Screen("photo_source")
    data object TerrainPreview : Screen("terrain_preview")
    data object GameSetup : Screen("game_setup")
    data object Game : Screen("game")
    data object PassDevice : Screen("pass_device")
    data object Victory : Screen("victory")
}
