package com.perfectpitch.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.perfectpitch.ui.freeplay.FreePlayScreen
import com.perfectpitch.ui.home.HomeScreen
import com.perfectpitch.ui.melody.MelodyPlayScreen
import com.perfectpitch.ui.melody.MelodyScreen
import com.perfectpitch.ui.melody.MelodyViewModel
import com.perfectpitch.ui.settings.SettingsScreen
import com.perfectpitch.ui.tuner.TunerScreen

@Composable
fun NavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(
                onNavigateToFreePlay = { navController.navigate("freeplay") },
                onNavigateToMelody = { navController.navigate("melody_graph") },
                onNavigateToTuner = { navController.navigate("tuner") },
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }
        composable("freeplay") {
            FreePlayScreen(onBack = { navController.popBackStack() })
        }
        navigation(startDestination = "melody", route = "melody_graph") {
            composable("melody") { entry ->
                val parentEntry = remember(entry) {
                    navController.getBackStackEntry("melody_graph")
                }
                val viewModel: MelodyViewModel = hiltViewModel(parentEntry)
                MelodyScreen(
                    onBack = { navController.popBackStack() },
                    onStartPlay = { navController.navigate("melody/play") },
                    viewModel = viewModel
                )
            }
            composable("melody/play") { entry ->
                val parentEntry = remember(entry) {
                    navController.getBackStackEntry("melody_graph")
                }
                val viewModel: MelodyViewModel = hiltViewModel(parentEntry)
                MelodyPlayScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
        }
        composable("tuner") {
            TunerScreen(onBack = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
