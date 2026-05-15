package com.perfectpitch.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.perfectpitch.ui.melody.ScoreResultScreen
import com.perfectpitch.ui.settings.SettingsScreen
import com.perfectpitch.ui.tuner.TunerScreen
import com.perfectpitch.music.MelodyScore

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
                    onShowResults = { navController.navigate("melody/results") },
                    viewModel = viewModel
                )
            }
            composable("melody/results") { entry ->
                val parentEntry = remember(entry) {
                    navController.getBackStackEntry("melody_graph")
                }
                val viewModel: MelodyViewModel = hiltViewModel(parentEntry)
                val state by viewModel.state.collectAsState()
                ScoreResultScreen(
                    melodyScore = state.melodyScore ?: MelodyScore.EMPTY,
                    noteScores = state.noteScores,
                    onPlayAgain = {
                        navController.popBackStack("melody", inclusive = false)
                    },
                    onBack = {
                        navController.popBackStack("melody", inclusive = false)
                    }
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
