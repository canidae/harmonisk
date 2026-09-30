package net.exent.perfectpitch.ui.home

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import net.exent.perfectpitch.R
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import net.exent.perfectpitch.data.DevModeState
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** How long the home heading must be held down to toggle developer mode. */
private const val DEV_MODE_HOLD_MS = 7_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToFreePlay: () -> Unit,
    onNavigateToMelody: () -> Unit,
    onNavigateToTuner: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            )
        }
    ) { padding ->
        if (isLandscape) {
            LandscapeContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                onNavigateToFreePlay = onNavigateToFreePlay,
                onNavigateToMelody = onNavigateToMelody,
                onNavigateToTuner = onNavigateToTuner
            )
        } else {
            PortraitContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                onNavigateToFreePlay = onNavigateToFreePlay,
                onNavigateToMelody = onNavigateToMelody,
                onNavigateToTuner = onNavigateToTuner,
                onToggleDevMode = {
                    val enabled = viewModel.toggleDevMode()
                    Toast.makeText(
                        context,
                        context.getString(if (enabled) R.string.dev_mode_enabled else R.string.dev_mode_disabled),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }
}

@Composable
private fun PortraitContent(
    modifier: Modifier,
    onNavigateToFreePlay: () -> Unit,
    onNavigateToMelody: () -> Unit,
    onNavigateToTuner: () -> Unit,
    onToggleDevMode: () -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.home_heading),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        // Only a press held for the full duration toggles developer mode.
                        if (withTimeoutOrNull(DEV_MODE_HOLD_MS) { tryAwaitRelease() } == null) {
                            onToggleDevMode()
                        }
                    }
                )
            }
        )

        Spacer(modifier = Modifier.height(32.dp))

        ModeCard(
            emoji = "🎵",
            title = stringResource(R.string.home_free_play_title),
            description = stringResource(R.string.home_free_play_desc),
            onClick = onNavigateToFreePlay
        )

        Spacer(modifier = Modifier.height(12.dp))

        ModeCard(
            emoji = "🎼",
            title = stringResource(R.string.home_melody_title),
            description = stringResource(R.string.home_melody_desc),
            onClick = onNavigateToMelody
        )

        Spacer(modifier = Modifier.height(12.dp))

        ModeCard(
            emoji = "🎸",
            title = stringResource(R.string.home_tuner_title),
            description = stringResource(R.string.home_tuner_desc),
            onClick = onNavigateToTuner
        )
    }
}

@Composable
private fun LandscapeContent(
    modifier: Modifier,
    onNavigateToFreePlay: () -> Unit,
    onNavigateToMelody: () -> Unit,
    onNavigateToTuner: () -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModeCard(
            modifier = Modifier.weight(1f),
            isLandscape = true,
            emoji = "🎵",
            title = stringResource(R.string.home_free_play_title),
            description = stringResource(R.string.home_free_play_desc_short),
            onClick = onNavigateToFreePlay
        )
        ModeCard(
            modifier = Modifier.weight(1f),
            isLandscape = true,
            emoji = "🎼",
            title = stringResource(R.string.home_melody_title),
            description = stringResource(R.string.home_melody_desc_short),
            onClick = onNavigateToMelody
        )
        ModeCard(
            modifier = Modifier.weight(1f),
            isLandscape = true,
            emoji = "🎸",
            title = stringResource(R.string.home_tuner_title),
            description = stringResource(R.string.home_tuner_desc_short),
            onClick = onNavigateToTuner
        )
    }
}

@Composable
private fun ModeCard(
    emoji: String,
    title: String,
    description: String,
    onClick: () -> Unit,
    isLandscape: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (isLandscape) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxHeight()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(emoji, style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = emoji,
                    style = MaterialTheme.typography.headlineLarge
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val devModeState: DevModeState
) : ViewModel() {

    /** Toggle developer mode, returning whether it is now enabled. */
    fun toggleDevMode(): Boolean = devModeState.toggle()
}
