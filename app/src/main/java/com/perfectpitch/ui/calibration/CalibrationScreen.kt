package com.perfectpitch.ui.calibration

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
                title = { Text("Audio Input Delay") },
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
            // Input device info
            state.inputDevice?.let { device ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when (device.type) {
                                com.perfectpitch.audio.AudioInputDeviceType.BUILTIN -> "🎤"
                                com.perfectpitch.audio.AudioInputDeviceType.WIRED -> "🎧"
                                com.perfectpitch.audio.AudioInputDeviceType.BLUETOOTH -> "🎧"
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
                                text = "Calibrating for this input device",
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
                    synthReady = state.synthReady,
                    synthError = state.synthError,
                    error = state.error,
                    onStart = { viewModel.startCalibration() }
                )
                CalibrationPhase.CALIBRATING -> CalibratingContent(
                    tonesPlayed = state.tonesPlayed,
                    tonesDetected = state.tonesDetected,
                    totalTones = CalibrationViewModel.NUM_TONES,
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
private fun ReadyContent(
    synthReady: Boolean,
    synthError: String?,
    error: String?,
    onStart: () -> Unit
) {
    Text(
        text = "Automatically measures the delay between audio playback and microphone detection. " +
            "Make sure the environment is quiet.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 16.dp)
    )

    if (error != null) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }

    if (synthError != null) {
        Text(
            text = "Audio error: $synthError",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    } else if (!synthReady) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
        Text(
            text = "Initializing audio…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onStart,
        enabled = synthReady,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Text("Start Calibration", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun CalibratingContent(
    tonesPlayed: Int,
    tonesDetected: Int,
    totalTones: Int,
    onStop: () -> Unit
) {
    Text(
        text = "Measuring audio delay…",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    LinearProgressIndicator(
        progress = { tonesPlayed.toFloat() / totalTones },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp),
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )

    Text(
        text = "$tonesPlayed / $totalTones tones played, $tonesDetected detected",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Text(
        text = "Playing tones through the speaker and listening via the microphone…",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

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
        text = "Measured input delay: ${measuredDelay}ms",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = editedDelay,
        onValueChange = onEditDelay,
        label = { Text("Audio input delay (ms)") },
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
