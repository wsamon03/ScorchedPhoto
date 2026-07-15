package com.scorchedphoto.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.scorchedphoto.app.capture.PhotoSourceScreen
import com.scorchedphoto.app.game.GameScreen
import com.scorchedphoto.app.home.HomeScreen
import com.scorchedphoto.app.result.VictoryScreen
import com.scorchedphoto.app.setup.GameSetupScreen
import com.scorchedphoto.app.terrainpreview.TerrainPreviewScreen

@Composable
fun ScorchedNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Screen.Home.route) {
        composable(Screen.Home.route) {
            HomeScreen(onNewGame = { navController.navigate(Screen.PhotoSource.route) })
        }
        composable(Screen.PhotoSource.route) {
            PhotoSourceScreen(onPhotoReady = { navController.navigate(Screen.TerrainPreview.route) })
        }
        composable(Screen.TerrainPreview.route) {
            TerrainPreviewScreen(
                onAccept = { navController.navigate(Screen.GameSetup.route) },
                onRetake = { navController.popBackStack() },
            )
        }
        composable(Screen.GameSetup.route) {
            GameSetupScreen(onStartMatch = { navController.navigate(Screen.Game.route) })
        }
        composable(Screen.Game.route) {
            GameScreen(onMatchOver = { navController.navigate(Screen.Victory.route) })
        }
        composable(Screen.Victory.route) {
            VictoryScreen(
                onRematch = {
                    navController.navigate(Screen.Game.route) {
                        popUpTo(Screen.GameSetup.route)
                    }
                },
                onNewPhoto = {
                    navController.navigate(Screen.PhotoSource.route) {
                        popUpTo(Screen.Home.route)
                    }
                },
                onHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
            )
        }
    }
}
