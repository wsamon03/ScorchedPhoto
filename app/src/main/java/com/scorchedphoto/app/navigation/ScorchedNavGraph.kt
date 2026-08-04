package com.scorchedphoto.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.scorchedphoto.app.capture.PhotoCropScreen
import com.scorchedphoto.app.capture.PhotoSourceScreen
import com.scorchedphoto.app.game.GameScreen
import com.scorchedphoto.app.home.HomeScreen
import com.scorchedphoto.app.result.VictoryScreen
import com.scorchedphoto.app.settings.AttackPhrasesScreen
import com.scorchedphoto.app.settings.DeathPhrasesScreen
import com.scorchedphoto.app.settings.SettingsScreen
import com.scorchedphoto.app.setup.GameSettingsScreen
import com.scorchedphoto.app.setup.GameSetupScreen
import com.scorchedphoto.app.setup.GameSetupViewModel
import com.scorchedphoto.app.terrainpreview.TerrainPreviewScreen
import com.scorchedphoto.app.tournament.MultiGameSetupScreen

@Composable
fun ScorchedNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Screen.Home.route) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNewGame = { navController.navigate(Screen.GameSetup.route) },
                onMultiGame = { navController.navigate(Screen.MultiGameSetup.route) },
                onSettings = { navController.navigate(Screen.Settings.route) },
            )
        }
        composable(Screen.MultiGameSetup.route) {
            MultiGameSetupScreen(onNext = { navController.navigate(Screen.GameSetup.route) })
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onDeathPhrases = { navController.navigate(Screen.DeathPhrases.route) },
                onAttackPhrases = { navController.navigate(Screen.AttackPhrases.route) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Screen.DeathPhrases.route) {
            DeathPhrasesScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.AttackPhrases.route) {
            AttackPhrasesScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.GameSetup.route) {
            GameSetupScreen(
                onStartMatch = { navController.navigate(Screen.PhotoSource.route) },
                onOpenSettings = { navController.navigate(Screen.GameSettings.route) },
            )
        }
        composable(Screen.GameSettings.route) { backStackEntry ->
            val gameSetupEntry = remember(backStackEntry) { navController.getBackStackEntry(Screen.GameSetup.route) }
            val viewModel: GameSetupViewModel = hiltViewModel(gameSetupEntry)
            GameSettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(Screen.PhotoSource.route) {
            PhotoSourceScreen(onPhotoReady = { navController.navigate(Screen.PhotoCrop.route) })
        }
        composable(Screen.PhotoCrop.route) {
            PhotoCropScreen(
                onCropConfirmed = {
                    navController.navigate(Screen.TerrainPreview.route) {
                        popUpTo(Screen.PhotoCrop.route) { inclusive = true }
                    }
                },
                onRetake = { navController.popBackStack() },
            )
        }
        composable(Screen.TerrainPreview.route) {
            TerrainPreviewScreen(
                onAccept = { navController.navigate(Screen.Game.route) },
                onRetake = { navController.popBackStack() },
            )
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
