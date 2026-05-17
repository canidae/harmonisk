package com.perfectpitch.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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

            // Audio delay calibration (unified)
            SettingSection(title = "Audio Delay") {
                val device by viewModel.currentDevice.collectAsState()
                val inputDevice by viewModel.currentInputDevice.collectAsState()
                val activeOutputDelay by viewModel.activeOutputDelay.collectAsState()
                val activeInputDelay by viewModel.activeInputDelay.collectAsState()
                val totalDelay = activeOutputDelay + activeInputDelay

                device?.let {
                    Text(
                        text = "Output: ${it.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                inputDevice?.let {
                    Text(
                        text = "Input: ${it.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))

                // Display current values
                var editingOutput by remember { mutableStateOf(false) }
                var editingInput by remember { mutableStateOf(false) }
                var outputText by remember(activeOutputDelay) { mutableStateOf(activeOutputDelay.toString()) }
                var inputText by remember(activeInputDelay) { mutableStateOf(activeInputDelay.toString()) }

                Text(
                    text = "Total delay: ${totalDelay}ms",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(4.dp))

                // Output delay row
                if (editingOutput) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = outputText,
                            onValueChange = { outputText = it },
                            label = { Text("Output delay (ms)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.width(140.dp)
                        )
                        Button(
                            onClick = {
                                outputText.toIntOrNull()?.let { ms ->
                                    viewModel.saveOutputDelay(ms)
                                }
                                editingOutput = false
                            },
                            enabled = outputText.toIntOrNull() != null
                        ) { Text("Save") }
                        TextButton(onClick = {
                            outputText = activeOutputDelay.toString()
                            editingOutput = false
                        }) { Text("Cancel") }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Output delay: ${activeOutputDelay}ms",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { editingOutput = true }) { Text("Edit") }
                    }
                }

                // Input delay row
                if (editingInput) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            label = { Text("Input delay (ms)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.width(140.dp)
                        )
                        Button(
                            onClick = {
                                inputText.toIntOrNull()?.let { ms ->
                                    viewModel.saveInputDelay(ms)
                                }
                                editingInput = false
                            },
                            enabled = inputText.toIntOrNull() != null
                        ) { Text("Save") }
                        TextButton(onClick = {
                            inputText = activeInputDelay.toString()
                            editingInput = false
                        }) { Text("Cancel") }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Input delay: ${activeInputDelay}ms",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { editingInput = true }) { Text("Edit") }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onNavigateToCalibration,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Calibrate Audio Delays")
                }
                Text(
                    text = "Automatic calibration for connected audio devices",
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

            // Pitch line mode
            SettingSection(title = "Pitch Line") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Continuous Line", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (settings.continuousPitchLine)
                                "Pitch draws as one smooth line across notes"
                            else
                                "Pitch shown within each key's full width",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = settings.continuousPitchLine,
                        onCheckedChange = { viewModel.updateContinuousPitchLine(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

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
