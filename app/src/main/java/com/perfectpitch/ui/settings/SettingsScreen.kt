package com.perfectpitch.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
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
                    steps = 79
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("420 Hz", style = MaterialTheme.typography.labelLarge)
                    Text("460 Hz", style = MaterialTheme.typography.labelLarge)
                }
            }

            // In-tune sensitivity
            SettingSection(title = "In-Tune Sensitivity") {
                val options = listOf(5 to "Strict (±5¢)", 10 to "Normal (±10¢)", 20 to "Relaxed (±20¢)")
                options.forEach { (cents, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.inTuneCents == cents,
                            onClick = { viewModel.updateInTuneCents(cents) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
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

            // Audio delay calibration (per-device)
            SettingSection(title = "Audio Delay") {
                val device by viewModel.currentDevice.collectAsState()
                val activeDelay by viewModel.activeDelay.collectAsState()
                var editingDelay by remember { mutableStateOf(false) }
                var delayText by remember(activeDelay) { mutableStateOf(activeDelay.toString()) }

                device?.let {
                    Text(
                        text = "Device: ${it.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))

                if (editingDelay) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = delayText,
                            onValueChange = { delayText = it },
                            label = { Text("Delay (ms)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.width(140.dp)
                        )
                        Button(
                            onClick = {
                                delayText.toIntOrNull()?.let { ms ->
                                    viewModel.saveDelay(ms)
                                }
                                editingDelay = false
                            },
                            enabled = delayText.toIntOrNull() != null
                        ) {
                            Text("Save")
                        }
                        TextButton(onClick = {
                            delayText = activeDelay.toString()
                            editingDelay = false
                        }) {
                            Text("Cancel")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Current delay: ${activeDelay}ms",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        TextButton(onClick = { editingDelay = true }) {
                            Text("Edit")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onNavigateToCalibration,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Calibrate Audio Delay")
                }
                Text(
                    text = "Tap-to-sync calibration for the connected audio device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Dark theme
            SettingSection(title = "Appearance") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Dark Theme", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = settings.darkTheme,
                        onCheckedChange = { viewModel.updateDarkTheme(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Reset button
            OutlinedButton(
                onClick = { viewModel.resetToDefaults() },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Reset to Defaults")
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
