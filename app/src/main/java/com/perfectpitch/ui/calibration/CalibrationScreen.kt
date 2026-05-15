package com.perfectpitch.ui.calibration

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.perfectpitch.ui.calibration.CalibrationViewModel.CalibrationPhase
import com.perfectpitch.ui.theme.Green80

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    onBack: () -> Unit,
    viewModel: CalibrationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.initSynthesizer(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio Delay Calibration") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.stopCalibration()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Device info
            state.device?.let { device ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when (device.type) {
                                com.perfectpitch.audio.AudioOutputDeviceType.SPEAKER -> "🔊"
                                com.perfectpitch.audio.AudioOutputDeviceType.WIRED -> "🎧"
                                com.perfectpitch.audio.AudioOutputDeviceType.BLUETOOTH -> "🎧"
                            },
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = device.name,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Calibrating for this device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(0.3f))

            when (state.phase) {
                CalibrationPhase.READY -> ReadyContent(
                    onStart = { viewModel.startCalibration() }
                )
                CalibrationPhase.PLAYING -> PlayingContent(
                    tapsCollected = state.tapsCollected,
                    totalTaps = CalibrationViewModel.TOTAL_TAPS,
                    currentBeat = state.currentBeat,
                    onTap = { viewModel.onTap() },
                    onStop = { viewModel.stopCalibration() }
                )
                CalibrationPhase.RESULT -> ResultContent(
                    measuredDelay = state.resultDelayMs ?: 0,
                    editedDelay = state.editedDelayMs,
                    saved = state.saved,
                    onEditDelay = { viewModel.updateEditedDelay(it) },
                    onSave = { viewModel.saveDelay() },
                    onRetry = { viewModel.startCalibration() }
                )
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReadyContent(onStart: () -> Unit) {
    Text(
        text = "Tap the button in time with the piano beats to measure your audio delay.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 16.dp)
    )

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Text("Start Calibration", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PlayingContent(
    tapsCollected: Int,
    totalTaps: Int,
    currentBeat: Int,
    onTap: () -> Unit,
    onStop: () -> Unit
) {
    // Progress
    Text(
        text = "Tap in time with the beat",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Beat pulse animation
    val beatPulse = remember { Animatable(1f) }
    LaunchedEffect(currentBeat) {
        if (currentBeat > 0) {
            beatPulse.snapTo(1.15f)
            beatPulse.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
        }
    }

    val progressColor by animateColorAsState(
        targetValue = if (tapsCollected >= CalibrationViewModel.WARMUP_TAPS)
            Green80
        else
            MaterialTheme.colorScheme.primary,
        animationSpec = tween(300),
        label = "progress_color"
    )

    LinearProgressIndicator(
        progress = { tapsCollected.toFloat() / totalTaps },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp),
        color = progressColor,
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )

    Text(
        text = "$tapsCollected / $totalTaps taps",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (tapsCollected < CalibrationViewModel.WARMUP_TAPS) {
        Text(
            text = "Warming up… get into the groove!",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Big TAP button
    FilledTonalButton(
        onClick = onTap,
        modifier = Modifier
            .size((180 * beatPulse.value).dp),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Text(
            text = "TAP",
            style = MaterialTheme.typography.displayMedium
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    TextButton(onClick = onStop) {
        Text("Cancel")
    }
}

@Composable
private fun ResultContent(
    measuredDelay: Int,
    editedDelay: String,
    saved: Boolean,
    onEditDelay: (String) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit
) {
    Text(
        text = "Calibration Complete",
        style = MaterialTheme.typography.titleLarge
    )

    Text(
        text = "Measured delay: ${measuredDelay}ms",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = editedDelay,
        onValueChange = onEditDelay,
        label = { Text("Audio delay (ms)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.width(200.dp),
        supportingText = {
            Text("Fine-tune the value if needed")
        }
    )

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = onRetry) {
            Text("Retry")
        }

        Button(
            onClick = onSave,
            enabled = !saved && editedDelay.toIntOrNull() != null
        ) {
            if (saved) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Saved")
            } else {
                Text("Save")
            }
        }
    }
}
