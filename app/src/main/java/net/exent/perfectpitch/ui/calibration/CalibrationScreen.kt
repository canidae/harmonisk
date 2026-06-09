package net.exent.perfectpitch.ui.calibration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.exent.perfectpitch.ui.components.RequireMicPermission
import net.exent.perfectpitch.ui.calibration.CalibrationViewModel.CalibrationPhase

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
        RequireMicPermission {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
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

                    CalibrationPhase.INSTRUCTIONS -> InstructionsContent(
                        inputDeviceName = state.inputDevice?.name ?: "microphone",
                        outputDeviceName = state.outputDevice?.name ?: "speaker",
                        synthReady = state.synthReady,
                        onStart = { viewModel.startRoundTripMeasurement() }
                    )

                    CalibrationPhase.MEASURING_ROUNDTRIP -> MeasuringRoundTripContent(
                        tonesPlayed = state.tonesPlayed,
                        tonesDetected = state.tonesDetected,
                        onCancel = { viewModel.stopCalibration() }
                    )

                    CalibrationPhase.ADJUSTING_SPLIT -> AdjustingSplitContent(
                        state = state,
                        onSliderChange = { viewModel.updateSplitPercent(it) },
                        onSave = { viewModel.saveAndFinish() },
                        onRetry = { viewModel.stopCalibration(); viewModel.startRoundTripMeasurement() }
                    )

                    CalibrationPhase.DONE -> DoneContent(
                        state = state,
                        onBack = onBack
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun InstructionsContent(
    inputDeviceName: String,
    outputDeviceName: String,
    synthReady: Boolean,
    onStart: () -> Unit
) {
    Text(
        text = "Calibrate Audio Delays",
        style = MaterialTheme.typography.titleLarge
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Output: $outputDeviceName\nInput: $inputDeviceName",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This wizard will measure the audio delay of your devices in two steps:\n\n" +
                    "1. The app will play a series of tones and detect them to measure the total round-trip delay.\n\n" +
                    "2. You'll adjust a slider until the sound and a visual pulse are perfectly in sync.\n\n" +
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
        Text("Start Calibration", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MeasuringRoundTripContent(
    tonesPlayed: Int,
    tonesDetected: Int,
    onCancel: () -> Unit
) {
    Text(
        text = "Measuring Round-Trip Delay…",
        style = MaterialTheme.typography.titleMedium
    )

    Spacer(modifier = Modifier.height(8.dp))

    LinearProgressIndicator(
        progress = { tonesPlayed.toFloat() / CalibrationViewModel.NUM_TONES },
        modifier = Modifier.fillMaxWidth().height(8.dp),
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )

    Text(
        text = "$tonesPlayed / ${CalibrationViewModel.NUM_TONES} tones played, $tonesDetected detected",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Text(
        text = "Playing tones and listening… keep the environment quiet.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(8.dp))
    TextButton(onClick = onCancel) { Text("Cancel") }
}

@Composable
private fun AdjustingSplitContent(
    state: CalibrationViewModel.CalibrationState,
    onSliderChange: (Int) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit
) {
    val pulseOnMs = CalibrationViewModel.PULSE_ON_MS
    val pulseColor = MaterialTheme.colorScheme.primary
    val pulseOffColor = MaterialTheme.colorScheme.surfaceVariant

    Text(
        text = "Adjust Audio Sync",
        style = MaterialTheme.typography.titleLarge
    )

    Text(
        text = "Move the slider until the sound and the flashing circle are perfectly in sync.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Pulsing circle — synced to tone playback.
    // The visual starts outputDelayMs after the tone starts.
    // toneStartTimeMs is updated each cycle by the VM's split loop.
    var isVisualOn by remember { mutableStateOf(false) }
    val currentState by rememberUpdatedState(state)

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos {
                val toneStart = currentState.toneStartTimeMs
                if (toneStart <= 0L) {
                    isVisualOn = false
                    return@withFrameNanos
                }
                val outputDelay = currentState.outputDelayMs.toLong()
                val elapsed = System.currentTimeMillis() - toneStart
                val visualStart = outputDelay
                val visualEnd = outputDelay + pulseOnMs
                isVisualOn = elapsed in visualStart until visualEnd
            }
        }
    }

    val circleColor = if (isVisualOn) pulseColor else pulseOffColor

    Canvas(modifier = Modifier.size(120.dp)) {
        drawCircle(
            color = circleColor,
            radius = size.minDimension / 2f,
            center = Offset(size.width / 2f, size.height / 2f)
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Slider card
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Total delay: ${state.totalDelayMs}ms",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Slider(
                value = state.splitPercent.toFloat(),
                onValueChange = { onSliderChange(it.toInt()) },
                valueRange = 0f..100f,
                steps = 19,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Output delay: ${state.outputDelayMs}ms", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${state.splitPercent}%", style = MaterialTheme.typography.bodyMedium)
                Text("Input delay: ${state.inputDelayMs}ms", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onRetry) { Text("Retry") }
        Button(onClick = onSave) { Text("Save") }
    }
}

@Composable
private fun DoneContent(
    state: CalibrationViewModel.CalibrationState,
    onBack: () -> Unit
) {
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

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Total delay: ${state.totalDelayMs}ms (${state.splitPercent}% output)",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "Output: ${state.outputDelayMs}ms / Input: ${state.inputDelayMs}ms",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    Text(
        text = "Audio delays have been saved. You can fine-tune the split in Settings.",
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
