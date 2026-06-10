package net.exent.perfectpitch.ui.calibration

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
                    Text("Initializing audio\u2026", style = MaterialTheme.typography.bodySmall)
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
                    CalibrationPhase.INIT -> {}

                    CalibrationPhase.INSTRUCTIONS -> InstructionsContent(
                        inputDeviceName = state.inputDevice?.name ?: "microphone",
                        outputDeviceName = state.outputDevice?.name ?: "speaker",
                        synthReady = state.synthReady,
                        onStart = { viewModel.startCalibration() }
                    )

                    CalibrationPhase.CALIBRATING -> CalibratingContent(
                        tonesPlayed = state.tonesPlayed,
                        tonesDetected = state.tonesDetected,
                        onSave = { viewModel.saveAndFinish() },
                        onRetry = { viewModel.stopCalibration(); viewModel.startCalibration() }
                    )

                    CalibrationPhase.DONE -> DoneContent(
                        audioDelayMs = state.computedAudioDelayMs,
                        visualDelayMs = state.computedVisualDelayMs,
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
                text = "This wizard measures the audio delay of your devices:\n\n" +
                    "The app plays a tone and measures how long it takes to reach " +
                    "the microphone. This gives the audio delay for your system.\n\n" +
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
private fun CalibratingContent(
    tonesPlayed: Int,
    tonesDetected: Int,
    onSave: () -> Unit,
    onRetry: () -> Unit
) {
    Text(
        text = "Calibrating Audio Delay",
        style = MaterialTheme.typography.titleLarge
    )

    Text(
        text = "Listening for tones\u2026",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Tones played: $tonesPlayed | Detected: $tonesDetected",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onRetry) { Text("Retry") }
        Button(onClick = onSave) { Text("Save") }
    }
}

@Composable
private fun DoneContent(
    audioDelayMs: Int,
    visualDelayMs: Int,
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
                text = "Audio delay: ${audioDelayMs}ms",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "Visual delay: ${visualDelayMs}ms",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    Text(
        text = "Audio delays have been saved. You can fine-tune them in Settings.",
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
