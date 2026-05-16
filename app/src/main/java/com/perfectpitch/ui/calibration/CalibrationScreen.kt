package com.perfectpitch.ui.calibration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import com.perfectpitch.ui.calibration.CalibrationViewModel.CalibrationStep

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
            } else if (!state.synthReady) {
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

            when (state.step) {
                CalibrationStep.INIT -> {
                    // Waiting for init
                }

                CalibrationStep.BASELINE_NEEDED -> BaselineNeededContent(
                    externalOutputName = state.externalOutputName,
                    externalInputName = state.externalInputName,
                    onReady = { viewModel.confirmBaselineReady() }
                )

                CalibrationStep.BASELINE_READY -> BaselineReadyContent(
                    synthReady = state.synthReady,
                    isRecalibration = state.hasBaseline,
                    onStart = { viewModel.startCalibration() }
                )

                CalibrationStep.BASELINE_CALIBRATING -> CalibratingContent(
                    title = "Measuring baseline delay…",
                    description = "Playing tones through built-in speaker and listening via built-in microphone.",
                    tonesPlayed = state.tonesPlayed,
                    tonesDetected = state.tonesDetected,
                    onStop = { viewModel.stopCalibration() }
                )

                CalibrationStep.BASELINE_RESULT -> BaselineResultContent(
                    roundTripMs = state.baselineRoundTripMs ?: 0,
                    inputDelayMs = state.baselineInputDelayMs ?: 0,
                    estimatedOutputLatencyMs = state.estimatedOutputLatencyMs,
                    editedDelay = state.editedBaselineInputDelay,
                    onEditDelay = { viewModel.updateEditedBaselineDelay(it) },
                    onSaveAndContinue = { viewModel.saveBaselineAndContinue() },
                    onRetry = { viewModel.startCalibration() },
                    hasMoreSteps = state.hasExternalOutput || state.hasExternalInput
                )

                CalibrationStep.DEVICE_OUTPUT_INSTRUCTIONS -> DeviceInstructionsContent(
                    deviceName = state.externalOutputName,
                    deviceType = "speaker/headphones",
                    onReady = { viewModel.confirmDeviceOutputReady() }
                )

                CalibrationStep.DEVICE_OUTPUT_READY -> DeviceReadyContent(
                    deviceName = state.externalOutputName,
                    synthReady = state.synthReady,
                    onStart = { viewModel.startCalibration() }
                )

                CalibrationStep.DEVICE_OUTPUT_CALIBRATING -> CalibratingContent(
                    title = "Measuring output delay for ${state.externalOutputName}…",
                    description = "Playing tones through external speaker and listening via built-in microphone.",
                    tonesPlayed = state.tonesPlayed,
                    tonesDetected = state.tonesDetected,
                    onStop = { viewModel.stopCalibration() }
                )

                CalibrationStep.DEVICE_OUTPUT_RESULT -> DeviceOutputResultContent(
                    deviceName = state.externalOutputName,
                    roundTripMs = state.deviceOutputRoundTripMs ?: 0,
                    outputDelayMs = state.deviceOutputDelayMs ?: 0,
                    editedDelay = state.editedOutputDelay,
                    onEditDelay = { viewModel.updateEditedOutputDelay(it) },
                    onSaveAndContinue = { viewModel.saveOutputDelayAndContinue() },
                    onRetry = { viewModel.startCalibration() },
                    hasMoreSteps = state.hasExternalInput
                )

                CalibrationStep.DEVICE_INPUT_INSTRUCTIONS -> DeviceInstructionsContent(
                    deviceName = state.externalInputName,
                    deviceType = "microphone",
                    onReady = { viewModel.confirmDeviceInputReady() }
                )

                CalibrationStep.DEVICE_INPUT_READY -> DeviceReadyContent(
                    deviceName = state.externalInputName,
                    synthReady = state.synthReady,
                    onStart = { viewModel.startCalibration() }
                )

                CalibrationStep.DEVICE_INPUT_CALIBRATING -> CalibratingContent(
                    title = "Measuring input delay for ${state.externalInputName}…",
                    description = "Playing tones through built-in speaker and listening via external microphone.",
                    tonesPlayed = state.tonesPlayed,
                    tonesDetected = state.tonesDetected,
                    onStop = { viewModel.stopCalibration() }
                )

                CalibrationStep.DEVICE_INPUT_RESULT -> DeviceInputResultContent(
                    deviceName = state.externalInputName,
                    roundTripMs = state.deviceInputRoundTripMs ?: 0,
                    inputDelayMs = state.deviceInputDelayMs ?: 0,
                    editedDelay = state.editedInputDelay,
                    onEditDelay = { viewModel.updateEditedInputDelay(it) },
                    onSaveAndFinish = { viewModel.saveInputDelayAndFinish() },
                    onRetry = { viewModel.startCalibration() }
                )

                CalibrationStep.DONE -> DoneContent(onBack = onBack)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun BaselineNeededContent(
    externalOutputName: String,
    externalInputName: String,
    onReady: () -> Unit
) {
    Text(
        text = "Baseline Calibration Required",
        style = MaterialTheme.typography.titleLarge
    )

    val devices = listOfNotNull(
        externalOutputName.takeIf { it.isNotBlank() },
        externalInputName.takeIf { it.isNotBlank() }
    ).joinToString(", ")

    Text(
        text = "External device(s) detected: $devices\n\n" +
            "First, we need to calibrate the built-in speaker and microphone as a baseline. " +
            "Please disconnect all external audio devices, then press Continue.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 8.dp)
    )

    Spacer(modifier = Modifier.height(16.dp))

    Button(
        onClick = onReady,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Text("Continue", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun BaselineReadyContent(
    synthReady: Boolean,
    isRecalibration: Boolean,
    onStart: () -> Unit
) {
    Text(
        text = if (isRecalibration) "Recalibrate Baseline" else "Baseline Calibration",
        style = MaterialTheme.typography.titleLarge
    )

    Text(
        text = "This measures the round-trip delay using the built-in speaker and microphone. " +
            "Make sure the environment is quiet.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 8.dp)
    )

    Spacer(modifier = Modifier.height(16.dp))

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
private fun CalibratingContent(
    title: String,
    description: String,
    tonesPlayed: Int,
    tonesDetected: Int,
    onStop: () -> Unit
) {
    Text(text = title, style = MaterialTheme.typography.titleMedium)

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
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(8.dp))
    TextButton(onClick = onStop) { Text("Cancel") }
}

@Composable
private fun BaselineResultContent(
    roundTripMs: Int,
    inputDelayMs: Int,
    estimatedOutputLatencyMs: Int,
    editedDelay: String,
    onEditDelay: (String) -> Unit,
    onSaveAndContinue: () -> Unit,
    onRetry: () -> Unit,
    hasMoreSteps: Boolean
) {
    Text(text = "Baseline Result", style = MaterialTheme.typography.titleLarge)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Round-trip delay: ${roundTripMs}ms",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Estimated speaker output delay: ~${estimatedOutputLatencyMs}ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Computed built-in mic input delay: ${inputDelayMs}ms",
                style = MaterialTheme.typography.titleMedium
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
            Text(if (hasMoreSteps) "Save & Continue" else "Save")
        }
    }
}

@Composable
private fun DeviceInstructionsContent(
    deviceName: String,
    deviceType: String,
    onReady: () -> Unit
) {
    Text(
        text = "Calibrate $deviceName",
        style = MaterialTheme.typography.titleLarge
    )

    Text(
        text = "Please reconnect your $deviceType ($deviceName), then press Continue.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 8.dp)
    )

    Spacer(modifier = Modifier.height(16.dp))

    Button(
        onClick = onReady,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Text("Continue", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DeviceReadyContent(
    deviceName: String,
    synthReady: Boolean,
    onStart: () -> Unit
) {
    Text(
        text = "Calibrate $deviceName",
        style = MaterialTheme.typography.titleLarge
    )

    Text(
        text = "Ready to measure delay. Make sure the environment is quiet.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

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
private fun DeviceOutputResultContent(
    deviceName: String,
    roundTripMs: Int,
    outputDelayMs: Int,
    editedDelay: String,
    onEditDelay: (String) -> Unit,
    onSaveAndContinue: () -> Unit,
    onRetry: () -> Unit,
    hasMoreSteps: Boolean
) {
    Text(text = "Output Delay: $deviceName", style = MaterialTheme.typography.titleLarge)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Round-trip delay: ${roundTripMs}ms",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Computed output delay: ${outputDelayMs}ms",
                style = MaterialTheme.typography.titleMedium
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
            onClick = onSaveAndContinue,
            enabled = editedDelay.toIntOrNull() != null
        ) {
            Text(if (hasMoreSteps) "Save & Continue" else "Save")
        }
    }
}

@Composable
private fun DeviceInputResultContent(
    deviceName: String,
    roundTripMs: Int,
    inputDelayMs: Int,
    editedDelay: String,
    onEditDelay: (String) -> Unit,
    onSaveAndFinish: () -> Unit,
    onRetry: () -> Unit
) {
    Text(text = "Input Delay: $deviceName", style = MaterialTheme.typography.titleLarge)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Round-trip delay: ${roundTripMs}ms",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Computed input delay: ${inputDelayMs}ms",
                style = MaterialTheme.typography.titleMedium
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
            onClick = onSaveAndFinish,
            enabled = editedDelay.toIntOrNull() != null
        ) {
            Text("Save")
        }
    }
}

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
