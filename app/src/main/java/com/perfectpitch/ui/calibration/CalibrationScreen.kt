package com.perfectpitch.ui.calibration

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MicExternalOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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
                title = { Text("Audio Calibration") },
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
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Synth initialization error
            if (state.synthError != null) {
                Text(
                    text = "Audio error: ${state.synthError}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            } else if (!state.synthReady && state.phase == CalibrationPhase.INIT) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Text("Initializing audio…", style = MaterialTheme.typography.bodySmall)
            }

            // Error from failed calibration
            if (state.error != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = state.error!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            when (state.phase) {
                CalibrationPhase.INIT -> { /* Waiting for init */ }

                CalibrationPhase.INPUT_INSTRUCTIONS -> InputInstructionsContent(
                    inputDeviceName = state.inputDevice?.name ?: "microphone",
                    onReady = { viewModel.confirmInputReady() }
                )

                CalibrationPhase.INPUT_READY -> InputReadyContent(
                    onStart = { viewModel.startInputCalibration() }
                )

                CalibrationPhase.INPUT_CALIBRATING -> InputCalibratingContent(
                    currentBeat = state.currentBeat,
                    totalBeats = CalibrationViewModel.TOTAL_BEATS,
                    warmupBeats = CalibrationViewModel.WARMUP_BEATS,
                    isWarmup = state.isWarmup,
                    beatsDetected = state.beatsDetected,
                    onStop = { viewModel.stopCalibration() }
                )

                CalibrationPhase.INPUT_RESULT -> InputResultContent(
                    inputDelayMs = state.inputDelayMs ?: 0,
                    editedDelay = state.editedInputDelay,
                    onEditDelay = { viewModel.updateEditedInputDelay(it) },
                    onSaveAndContinue = { viewModel.saveInputDelayAndContinue() },
                    onRetry = { viewModel.startInputCalibration() }
                )

                CalibrationPhase.OUTPUT_OFFER -> OutputOfferContent(
                    outputDeviceName = state.outputDevice?.name ?: "speaker",
                    synthReady = state.synthReady,
                    onStart = { viewModel.startOutputCalibration() },
                    onSkip = { viewModel.skipOutputCalibration() }
                )

                CalibrationPhase.OUTPUT_CALIBRATING -> OutputCalibratingContent(
                    tonesPlayed = state.tonesPlayed,
                    tonesDetected = state.tonesDetected,
                    onStop = { viewModel.stopCalibration() }
                )

                CalibrationPhase.OUTPUT_RESULT -> OutputResultContent(
                    outputDeviceName = state.outputDevice?.name ?: "speaker",
                    outputDelayMs = state.outputDelayMs ?: 0,
                    editedDelay = state.editedOutputDelay,
                    onEditDelay = { viewModel.updateEditedOutputDelay(it) },
                    onSaveAndFinish = { viewModel.saveOutputDelayAndFinish() },
                    onRetry = { viewModel.startOutputCalibration() }
                )

                CalibrationPhase.DONE -> DoneContent(onBack = onBack)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// --- Phase 1: Input Calibration ---

@Composable
private fun InputInstructionsContent(
    inputDeviceName: String,
    onReady: () -> Unit
) {
    Icon(
        Icons.Filled.MicExternalOn,
        contentDescription = null,
        modifier = Modifier.size(48.dp),
        tint = MaterialTheme.colorScheme.primary
    )

    Text(
        text = "Calibrate Audio Input",
        style = MaterialTheme.typography.titleLarge
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Input device: $inputDeviceName",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "A pulsing circle will beat at a steady rhythm. " +
                    "Make a sound (play a note, hum, or clap) in sync with each pulse.\n\n" +
                    "The first 4 beats are warm-up — get into the groove. " +
                    "The next 8 beats will be measured for calibration.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Button(
        onClick = onReady,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Text("I'm Ready", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun InputReadyContent(
    onStart: () -> Unit
) {
    Text(
        text = "Ready to Calibrate",
        style = MaterialTheme.typography.titleLarge
    )

    Text(
        text = "Press Start and make a sound on each pulse.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    Button(
        onClick = onStart,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Text("Start Calibration", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun InputCalibratingContent(
    currentBeat: Int,
    totalBeats: Int,
    warmupBeats: Int,
    isWarmup: Boolean,
    beatsDetected: Int,
    onStop: () -> Unit
) {
    val beatIntervalMs = CalibrationViewModel.BEAT_INTERVAL_MS.toInt()

    // Pulsing animation — beats at the metronome BPM
    val infiniteTransition = rememberInfiniteTransition(label = "metronome")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = beatIntervalMs
                1.0f at 0 using LinearEasing
                0.6f at (beatIntervalMs * 0.3).toInt() using FastOutSlowInEasing
                0.6f at beatIntervalMs using LinearEasing
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = beatIntervalMs
                1.0f at 0 using LinearEasing
                0.3f at (beatIntervalMs * 0.3).toInt() using FastOutSlowInEasing
                0.3f at beatIntervalMs using LinearEasing
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "alpha"
    )

    val pulseColor = if (isWarmup) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.primary
    }

    Text(
        text = if (isWarmup) "Warm-up — Get into the rhythm!" else "Measuring — Make a sound on each pulse!",
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        color = if (isWarmup) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Pulsing circle
    Box(
        modifier = Modifier.size(160.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer glow
        Box(
            modifier = Modifier
                .size(160.dp)
                .scale(pulseScale * 1.2f)
                .background(
                    pulseColor.copy(alpha = pulseAlpha * 0.2f),
                    CircleShape
                )
        )
        // Inner circle
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(pulseScale)
                .background(
                    pulseColor.copy(alpha = pulseAlpha * 0.8f),
                    CircleShape
                )
        )
        // Beat number in center
        Text(
            text = "$currentBeat",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Progress bar
    LinearProgressIndicator(
        progress = { currentBeat.toFloat() / totalBeats },
        modifier = Modifier.fillMaxWidth().height(8.dp),
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )

    val measuredBeats = if (currentBeat > warmupBeats) currentBeat - warmupBeats else 0
    val totalMeasured = totalBeats - warmupBeats
    Text(
        text = if (isWarmup) {
            "Warm-up: beat $currentBeat of $warmupBeats"
        } else {
            "Measuring: $measuredBeats / $totalMeasured beats ($beatsDetected detected)"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(8.dp))
    TextButton(onClick = onStop) { Text("Cancel") }
}

@Composable
private fun InputResultContent(
    inputDelayMs: Int,
    editedDelay: String,
    onEditDelay: (String) -> Unit,
    onSaveAndContinue: () -> Unit,
    onRetry: () -> Unit
) {
    Text(text = "Input Calibration Result", style = MaterialTheme.typography.titleLarge)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Calibrated input delay: ${inputDelayMs}ms",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "This is how far back detected sounds are shifted to match when you actually played them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    OutlinedTextField(
        value = editedDelay,
        onValueChange = onEditDelay,
        label = { Text("Input delay (ms)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.width(200.dp),
        supportingText = { Text("Fine-tune if needed") }
    )

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onRetry) { Text("Retry") }
        Button(
            onClick = onSaveAndContinue,
            enabled = editedDelay.toIntOrNull() != null
        ) {
            Text("Save & Continue")
        }
    }
}

// --- Phase 2: Output Calibration ---

@Composable
private fun OutputOfferContent(
    outputDeviceName: String,
    synthReady: Boolean,
    onStart: () -> Unit,
    onSkip: () -> Unit
) {
    Icon(
        Icons.AutoMirrored.Filled.VolumeUp,
        contentDescription = null,
        modifier = Modifier.size(48.dp),
        tint = MaterialTheme.colorScheme.primary
    )

    Text(
        text = "Calibrate Audio Output",
        style = MaterialTheme.typography.titleLarge
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Output device: $outputDeviceName",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "The app will play a series of tones and measure how long it takes to detect them. " +
                    "This determines the output delay for your current speaker/headphones.\n\n" +
                    "Make sure the environment is quiet and the microphone can hear the speaker.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Button(
        onClick = onStart,
        enabled = synthReady,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Text("Start Output Calibration", style = MaterialTheme.typography.titleMedium)
    }

    TextButton(onClick = onSkip) {
        Text("Skip — I don't need output calibration")
    }
}

@Composable
private fun OutputCalibratingContent(
    tonesPlayed: Int,
    tonesDetected: Int,
    onStop: () -> Unit
) {
    Text(
        text = "Measuring Output Delay…",
        style = MaterialTheme.typography.titleMedium
    )

    LinearProgressIndicator(
        progress = { tonesPlayed.toFloat() / CalibrationViewModel.OUTPUT_NUM_TONES },
        modifier = Modifier.fillMaxWidth().height(8.dp),
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )

    Text(
        text = "$tonesPlayed / ${CalibrationViewModel.OUTPUT_NUM_TONES} tones played, $tonesDetected detected",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Text(
        text = "Playing tones and listening for them…",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(8.dp))
    TextButton(onClick = onStop) { Text("Cancel") }
}

@Composable
private fun OutputResultContent(
    outputDeviceName: String,
    outputDelayMs: Int,
    editedDelay: String,
    onEditDelay: (String) -> Unit,
    onSaveAndFinish: () -> Unit,
    onRetry: () -> Unit
) {
    Text(text = "Output Calibration Result", style = MaterialTheme.typography.titleLarge)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Device: $outputDeviceName",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Calibrated output delay: ${outputDelayMs}ms",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Audio will be played this much earlier to compensate for speaker/headphone latency.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    OutlinedTextField(
        value = editedDelay,
        onValueChange = onEditDelay,
        label = { Text("Output delay (ms)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.width(200.dp),
        supportingText = { Text("Fine-tune if needed") }
    )

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onRetry) { Text("Retry") }
        Button(
            onClick = onSaveAndFinish,
            enabled = editedDelay.toIntOrNull() != null
        ) {
            Text("Save")
        }
    }
}

// --- Done ---

@Composable
private fun DoneContent(onBack: () -> Unit) {
    Icon(
        Icons.Filled.Check,
        contentDescription = null,
        modifier = Modifier.size(64.dp),
        tint = MaterialTheme.colorScheme.primary
    )

    Text(
        text = "Calibration Complete",
        style = MaterialTheme.typography.headlineMedium
    )

    Text(
        text = "Audio delays have been saved. You can fine-tune the values in Settings.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(16.dp))

    Button(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Text("Done", style = MaterialTheme.typography.titleMedium)
    }
}
