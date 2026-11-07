package net.exent.harmonisk.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import android.content.pm.PackageManager
import net.exent.harmonisk.R
import net.exent.harmonisk.data.LocaleManager
import net.exent.harmonisk.ui.components.BrandTopBar
import net.exent.harmonisk.ui.components.brandBackground
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isCalibrating by viewModel.isCalibrating.collectAsStateWithLifecycle()
    val calibrationTonesDetected by viewModel.calibrationTonesDetected.collectAsStateWithLifecycle()
    val calibrationNoDetection by viewModel.calibrationNoDetection.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.autoCalibrateAudioDelay()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .brandBackground(),
        containerColor = Color.Transparent,
        topBar = {
            BrandTopBar(
                title = stringResource(R.string.settings),
                onBack = onBack
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Language
            SettingSection(
                title = stringResource(R.string.settings_language),
                icon = Icons.Filled.Language
            ) {
                var expanded by remember { mutableStateOf(false) }
                val languages = remember { LocaleManager.supportedLanguages }
                val selected = languages.find { it.code == settings.languageCode } ?: languages.first()

                Box(modifier = Modifier.fillMaxWidth()) {
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

            // Microphone sensitivity, the negated silence threshold: -25 dB at the left,
            // -65 dB at the right, so moving right detects fainter sounds.
            SettingSection(
                title = stringResource(R.string.settings_mic_sensitivity),
                icon = Icons.Filled.Mic
            ) {
                Slider(
                    value = -settings.silenceThresholdDb,
                    onValueChange = { viewModel.updateSilenceThreshold(-it) },
                    valueRange = 25f..65f,
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

            // Audio delay calibration
            SettingSection(
                title = stringResource(R.string.settings_delay_title),
                icon = Icons.Filled.Timer,
                helpText = stringResource(R.string.settings_delay_help)
            ) {
                val device by viewModel.currentDevice.collectAsStateWithLifecycle()
                val inputDevice by viewModel.currentInputDevice.collectAsStateWithLifecycle()
                val outputDelay by viewModel.activeOutputDelay.collectAsStateWithLifecycle()
                val inputDelay by viewModel.activeInputDelay.collectAsStateWithLifecycle()

                val inName = inputDevice?.name
                    ?: inputDevice?.nameRes?.takeIf { it != 0 }?.let { stringResource(it) }
                    ?: stringResource(R.string.microphone)
                val outName = device?.name
                    ?: device?.nameRes?.takeIf { it != 0 }?.let { stringResource(it) }
                    ?: stringResource(R.string.speaker)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_devices, inName, outName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                viewModel.autoCalibrateAudioDelay()
                            } else {
                                permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    ) { Text(if (isCalibrating) stringResource(R.string.stop) else stringResource(R.string.settings_auto_calibrate)) }
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
                DelaySlider(
                    name = stringResource(R.string.settings_output_name),
                    value = outputDelay,
                    onValueChange = viewModel::setOutputDelay
                )

                Spacer(modifier = Modifier.height(12.dp))
                DelaySlider(
                    name = stringResource(R.string.settings_input_name),
                    value = inputDelay,
                    onValueChange = viewModel::setInputDelay
                )
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
private fun DelaySlider(
    name: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Slider(
        value = value.toFloat(),
        onValueChange = { onValueChange(((it / 25f).roundToInt() * 25).coerceIn(0, 500)) },
        valueRange = 0f..500f,
        steps = 19,
        modifier = Modifier.fillMaxWidth()
    )
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.settings_input_delay_min),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        Text(
            stringResource(R.string.settings_input_delay_max),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
        Text(
            text = stringResource(R.string.settings_delay_value, name, value),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun SettingSection(
    title: String,
    icon: ImageVector,
    helpText: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (helpText != null) {
                    HelpButton(helpText)
                }
            }
            content()
        }
    }
}

@Composable
private fun HelpButton(helpText: String) {
    var showHelp by remember { mutableStateOf(false) }

    IconButton(onClick = { showHelp = true }) {
        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = stringResource(R.string.help))
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            text = { Text(helpText) },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) { Text(stringResource(R.string.got_it)) }
            }
        )
    }
}
