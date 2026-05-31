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
import kotlin.math.roundToInt

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
                        "• Concert pitch (A = 440 Hz)\n" +
                        "• Silence threshold\n" +
                        "• Free Play speed, tempo, time signature, note snapping"
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
                val totalDelay by viewModel.activeTotalDelay.collectAsState()
                val splitPercent by viewModel.activeSplitPercent.collectAsState()
                val outputDelay by viewModel.activeOutputDelay.collectAsState()
                val inputDelay by viewModel.activeInputDelay.collectAsState()

                // Device line
                val inName = inputDevice?.name ?: "microphone"
                val outName = device?.name ?: "speaker"
                Text(
                    text = "In/Out: $inName / $outName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Total delay — editable inline
                var editingTotal by remember { mutableStateOf(false) }
                var totalText by remember(totalDelay) { mutableStateOf(totalDelay.toString()) }

                InlineEditableNumber(
                    label = "Total delay",
                    unit = "ms",
                    value = totalDelay,
                    displayText = "Total delay: ${totalDelay}ms",
                    editing = editingTotal,
                    editText = totalText,
                    onEditTextChange = { totalText = it },
                    onEdit = { editingTotal = true },
                    onSave = {
                        totalText.toIntOrNull()?.let { viewModel.saveTotalDelay(it) }
                        editingTotal = false
                    },
                    onCancel = {
                        totalText = totalDelay.toString()
                        editingTotal = false
                    }
                )

                // Split slider (cold — no audio)
                val splitPct = (splitPercent * 100).roundToInt()
                Slider(
                    value = splitPct.toFloat(),
                    onValueChange = { viewModel.saveSplitPercent(it / 100f) },
                    valueRange = 0f..100f,
                    steps = 99,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Output delay: ${outputDelay}ms", style = MaterialTheme.typography.labelSmall)
                    Text("$splitPct%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Input delay: ${inputDelay}ms", style = MaterialTheme.typography.labelSmall)
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

@Composable
private fun InlineEditableNumber(
    label: String,
    unit: String,
    value: Int,
    displayText: String,
    editing: Boolean,
    editText: String,
    onEditTextChange: (String) -> Unit,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    if (editing) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = editText,
                onValueChange = onEditTextChange,
                label = { Text("$label ($unit)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.width(160.dp)
            )
            Button(
                onClick = onSave,
                enabled = editText.toIntOrNull() != null
            ) { Text("Save") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayText,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onEdit) { Text("Edit") }
        }
    }
}
