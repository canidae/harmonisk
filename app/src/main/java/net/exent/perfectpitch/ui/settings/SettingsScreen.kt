package net.exent.perfectpitch.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToCalibration: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        var showResetConfirm by remember { mutableStateOf(false) }

        if (showResetConfirm) {
            AlertDialog(
                onDismissRequest = { showResetConfirm = false },
                title = { Text("Reset to defaults?") },
                text = {
                    Text(
                        "All settings will be returned to their default values, including:\n\n" +
                        "\u2022 Concert pitch (A = 440 Hz)\n" +
                        "\u2022 Silence threshold\n" +
                        "\u2022 Free Play speed, tempo, time signature, note snapping"
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.resetToDefaults(); showResetConfirm = false }) {
                        Text("Reset")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Concert pitch slider
            SettingSection(title = "Concert Pitch") {
                Text(
                    text = "A4 = ${"%.1f".format(settings.concertPitch)} Hz",
                    style = MaterialTheme.typography.bodyLarge
                )
                Slider(
                    value = settings.concertPitch,
                    onValueChange = { viewModel.updateConcertPitch(it) },
                    valueRange = 420f..460f,
                    steps = 39
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("420 Hz", style = MaterialTheme.typography.labelLarge)
                    Text("460 Hz", style = MaterialTheme.typography.labelLarge)
                }
            }

            // Microphone sensitivity
            SettingSection(title = "Microphone Sensitivity") {
                Text(
                    text = "Silence threshold: ${"%.0f".format(settings.silenceThresholdDb)} dB",
                    style = MaterialTheme.typography.bodyLarge
                )
                Slider(
                    value = settings.silenceThresholdDb,
                    onValueChange = { viewModel.updateSilenceThreshold(it) },
                    valueRange = -60f..-20f,
                    steps = 39
                )
            }

            // Audio delay calibration
            SettingSection(title = "Audio Delay") {
                val device by viewModel.currentDevice.collectAsState()
                val inputDevice by viewModel.currentInputDevice.collectAsState()
                val audioDelay by viewModel.activeAudioDelay.collectAsState()
                val visualDelay by viewModel.activeVisualDelay.collectAsState()

                // Device line
                val inName = inputDevice?.name ?: "microphone"
                val outName = device?.name ?: "speaker"
                Text(
                    text = "In/Out: $inName / $outName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Audio delay: ${audioDelay}ms",
                    style = MaterialTheme.typography.bodyLarge
                )
                Slider(
                    value = audioDelay.toFloat(),
                    onValueChange = { viewModel.setAudioDelay(it.toInt()) },
                    valueRange = 0f..500f,
                    steps = 49,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0ms", style = MaterialTheme.typography.labelSmall)
                    Text("500ms", style = MaterialTheme.typography.labelSmall)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Visual delay: ${visualDelay}ms",
                    style = MaterialTheme.typography.bodyLarge
                )
                Slider(
                    value = visualDelay.toFloat(),
                    onValueChange = { viewModel.setVisualDelay(it.toInt()) },
                    valueRange = 0f..50f,
                    steps = 50,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0ms", style = MaterialTheme.typography.labelSmall)
                    Text("50ms", style = MaterialTheme.typography.labelSmall)
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onNavigateToCalibration,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Calibrate Audio Delays")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Reset button
            OutlinedButton(
                onClick = { showResetConfirm = true },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Reset to defaults")
            }
        }
    }
}

@Composable
private fun SettingSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        content()
    }
}
