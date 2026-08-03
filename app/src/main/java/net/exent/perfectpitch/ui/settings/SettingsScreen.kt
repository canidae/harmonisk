package net.exent.perfectpitch.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.Language
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import android.content.pm.PackageManager
import net.exent.perfectpitch.R
import net.exent.perfectpitch.data.LocaleManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val isCalibrating by viewModel.isCalibrating.collectAsState()
    val calibrationTonesDetected by viewModel.calibrationTonesDetected.collectAsState()
    val calibrationNoDetection by viewModel.calibrationNoDetection.collectAsState()
    var showEditAudioDialog by remember { mutableStateOf(false) }
    var editAudioText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.autoCalibrateAudioDelay()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        var showResetConfirm by remember { mutableStateOf(false) }

        if (showResetConfirm) {
            AlertDialog(
                onDismissRequest = { showResetConfirm = false },
                title = { Text(stringResource(R.string.settings_reset_dialog_title)) },
                text = {
                    Text(stringResource(R.string.settings_reset_dialog_body))
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.resetToDefaults(); showResetConfirm = false }) {
                        Text(stringResource(R.string.settings_reset))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirm = false }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }

        if (showEditAudioDialog) {
            AlertDialog(
                onDismissRequest = { showEditAudioDialog = false },
                title = { Text(stringResource(R.string.settings_edit_audio_delay)) },
                text = {
                    OutlinedTextField(
                        value = editAudioText,
                        onValueChange = { editAudioText = it },
                        label = { Text(stringResource(R.string.settings_delay_ms)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.setAudioDelayFromText(editAudioText)
                        showEditAudioDialog = false
                    }) { Text(stringResource(R.string.save)) }
                },
                dismissButton = {
                    TextButton(onClick = { showEditAudioDialog = false }) { Text(stringResource(R.string.cancel)) }
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
            SettingSection(title = stringResource(R.string.settings_concert_pitch)) {
                Text(
                    text = stringResource(R.string.settings_concert_pitch_value, settings.concertPitch),
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
                    Text(stringResource(R.string.settings_concert_pitch_min), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.settings_concert_pitch_max), style = MaterialTheme.typography.labelLarge)
                }
            }

            // Microphone sensitivity (inverted: leftmost is least sensitive, rightmost most sensitive)
            SettingSection(title = stringResource(R.string.settings_mic_sensitivity)) {
                // Slider position maps inversely to the stored threshold: -10 dB at the left,
                // -50 dB at the right, so moving right detects fainter sounds.
                val sliderValue = (-60f - settings.silenceThresholdDb).coerceIn(-50f, -10f)
                Slider(
                    value = sliderValue,
                    onValueChange = { viewModel.updateSilenceThreshold(-60f - it) },
                    valueRange = -50f..-10f,
                    steps = 39,
                    modifier = Modifier.fillMaxWidth()
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_silence_threshold_min),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                    Text(
                        text = stringResource(R.string.settings_silence_threshold_max),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                    Text(
                        text = stringResource(R.string.settings_silence_threshold, settings.silenceThresholdDb),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            // Language
            SettingSection(title = stringResource(R.string.settings_language)) {
                var expanded by remember { mutableStateOf(false) }
                val languages = remember { LocaleManager.supportedLanguages }
                val selected = languages.find { it.code == settings.languageCode } ?: languages.first()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Filled.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = selected.displayName)
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.fillMaxWidth(0.8f)
                        ) {
                            languages.forEach { lang ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = lang.displayName,
                                            fontWeight = if (lang.code == selected.code) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        if (lang.code != selected.code) {
                                            viewModel.updateLanguageCode(lang.code, context as android.app.Activity)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Audio/Visual delay
            SettingSection(title = stringResource(R.string.settings_audio_visual_delay)) {
                val device by viewModel.currentDevice.collectAsState()
                val inputDevice by viewModel.currentInputDevice.collectAsState()
                val audioDelay by viewModel.activeAudioDelay.collectAsState()
                val visualDelay by viewModel.activeVisualDelay.collectAsState()

                val inName = inputDevice?.name ?: context.getString(R.string.microphone)
                val outName = device?.name ?: context.getString(R.string.speaker)
                Text(
                    text = stringResource(R.string.settings_in_out, inName, outName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_audio_delay, audioDelay),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = {
                            editAudioText = audioDelay.toString()
                            showEditAudioDialog = true
                        },
                        enabled = !isCalibrating
                    ) { Text(stringResource(R.string.edit)) }
                    Button(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                viewModel.autoCalibrateAudioDelay()
                            } else {
                                permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    ) { Text(if (isCalibrating) stringResource(R.string.settings_calibrating_stop) else stringResource(R.string.settings_auto_calibrate)) }
                }

                if (isCalibrating) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (calibrationNoDetection) {
                        Text(
                            text = stringResource(R.string.settings_no_detection),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.settings_calibrating, calibrationTonesDetected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.settings_visual_delay, visualDelay),
                    style = MaterialTheme.typography.bodyLarge
                )
                Slider(
                    value = visualDelay.toFloat(),
                    onValueChange = { viewModel.setVisualDelay(it.toInt()) },
                    valueRange = 0f..200f,
                    steps = 19,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.settings_visual_delay_min), style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(R.string.settings_visual_delay_max), style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Reset button
            OutlinedButton(
                onClick = { showResetConfirm = true },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(stringResource(R.string.settings_reset_to_defaults))
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
