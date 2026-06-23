package net.exent.perfectpitch.ui.home

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import net.exent.perfectpitch.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import net.exent.perfectpitch.audio.AudioDeviceMonitor
import net.exent.perfectpitch.data.AudioDelayRepository
import kotlinx.coroutines.flow.*
import javax.inject.Inject

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
    val uncalibratedDeviceNames by viewModel.uncalibratedDeviceNames.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uncalibratedDeviceNames) {
        val names = uncalibratedDeviceNames
        if (names != null && viewModel.shouldShowPrompt()) {
            val msg = context.getString(R.string.home_uncalibrated, names.joinToString(", "))
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = context.getString(R.string.settings),
                duration = SnackbarDuration.Indefinite
            )
            if (result == SnackbarResult.ActionPerformed) {
                onNavigateToSettings()
            }
            viewModel.dismissPrompt()
        }
    }

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
                onNavigateToTuner = onNavigateToTuner
            )
        }
    }
}

@Composable
private fun PortraitContent(
    modifier: Modifier,
    onNavigateToFreePlay: () -> Unit,
    onNavigateToMelody: () -> Unit,
    onNavigateToTuner: () -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.home_heading),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
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
    private val audioDeviceMonitor: AudioDeviceMonitor,
    private val audioDelayRepository: AudioDelayRepository
) : ViewModel() {

    val uncalibratedDeviceNames: StateFlow<List<String>?> = combine(
        audioDeviceMonitor.currentInputDevice,
        audioDeviceMonitor.currentDevice,
        audioDelayRepository.allProfiles
    ) { inputDevice, outputDevice, profiles ->
        val uncalibrated = mutableListOf<String>()
        if (!profiles.containsKey(inputDevice.deviceKey)) {
            uncalibrated.add(inputDevice.name)
        }
        if (!profiles.containsKey(outputDevice.deviceKey)) {
            uncalibrated.add(outputDevice.name)
        }
        uncalibrated.ifEmpty { null }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private var promptDismissed = false

    fun dismissPrompt() {
        promptDismissed = true
    }

    fun shouldShowPrompt(): Boolean = !promptDismissed
}
