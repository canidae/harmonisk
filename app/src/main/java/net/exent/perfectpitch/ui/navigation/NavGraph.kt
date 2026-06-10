package net.exent.perfectpitch.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import net.exent.perfectpitch.ui.freeplay.FreePlayScreen
import net.exent.perfectpitch.ui.home.HomeScreen
import net.exent.perfectpitch.ui.melody.MelodyPlayScreen
import net.exent.perfectpitch.ui.melody.MelodyScreen
import net.exent.perfectpitch.ui.melody.MelodyViewModel
import net.exent.perfectpitch.ui.settings.SettingsScreen
import net.exent.perfectpitch.ui.tuner.TunerScreen

@Composable
fun NavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = NavRoutes.HOME
    ) {
        composable(NavRoutes.HOME) {
            HomeScreen(
                onNavigateToFreePlay = { navController.navigate(NavRoutes.FREEPLAY) },
                onNavigateToMelody = { navController.navigate(NavRoutes.MELODY_GRAPH) },
                onNavigateToTuner = { navController.navigate(NavRoutes.TUNER) },
                onNavigateToSettings = { navController.navigate(NavRoutes.SETTINGS) }
            )
        }
        composable(NavRoutes.FREEPLAY) {
            FreePlayScreen(onBack = { navController.popBackStack() })
        }
        navigation(startDestination = NavRoutes.MELODY, route = NavRoutes.MELODY_GRAPH) {
            composable(NavRoutes.MELODY) { entry ->
                val parentEntry = remember(entry) {
                    navController.getBackStackEntry(NavRoutes.MELODY_GRAPH)
                }
                val viewModel: MelodyViewModel = hiltViewModel(parentEntry)
                MelodyScreen(
                    onBack = { navController.popBackStack() },
                    onStartPlay = { navController.navigate(NavRoutes.MELODY_PLAY) },
                    viewModel = viewModel
                )
            }
            composable(NavRoutes.MELODY_PLAY) { entry ->
                val parentEntry = remember(entry) {
                    navController.getBackStackEntry(NavRoutes.MELODY_GRAPH)
                }
                val viewModel: MelodyViewModel = hiltViewModel(parentEntry)
                MelodyPlayScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToSettings = { navController.navigate(NavRoutes.SETTINGS) },
                    viewModel = viewModel
                )
            }
        }
        composable(NavRoutes.TUNER) {
            TunerScreen(onBack = { navController.popBackStack() })
        }
        composable(NavRoutes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
