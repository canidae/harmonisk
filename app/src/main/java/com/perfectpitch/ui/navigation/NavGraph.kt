package com.perfectpitch.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
import com.perfectpitch.music.NoteScore

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
                onNavigateToMelody = { navController.navigate("melody") },
                onNavigateToTuner = { navController.navigate("tuner") },
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }
        composable("freeplay") {
            FreePlayScreen(onBack = { navController.popBackStack() })
        }
        composable("melody") {
            val viewModel: MelodyViewModel = hiltViewModel()
            MelodyScreen(
                onBack = { navController.popBackStack() },
                onStartPlay = { navController.navigate("melody/play") },
                viewModel = viewModel
            )
        }
        composable("melody/play") {
            val viewModel: MelodyViewModel = hiltViewModel()
            MelodyPlayScreen(
                onBack = { navController.popBackStack() },
                onShowResults = { navController.navigate("melody/results") },
                viewModel = viewModel
            )
        }
        composable("melody/results") {
            val viewModel: MelodyViewModel = hiltViewModel()
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
        composable("tuner") {
            TunerScreen(onBack = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
