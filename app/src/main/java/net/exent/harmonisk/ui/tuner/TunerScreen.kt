package net.exent.harmonisk.ui.tuner

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import net.exent.harmonisk.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.exent.harmonisk.music.NoteUtils
import net.exent.harmonisk.ui.components.PitchGauge
import net.exent.harmonisk.ui.components.RequireMicPermission
import net.exent.harmonisk.ui.components.KeepScreenOn
import net.exent.harmonisk.ui.theme.Green80
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunerScreen(
    onBack: () -> Unit,
    viewModel: TunerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    KeepScreenOn()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tuner_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        RequireMicPermission {
            LaunchedEffect(Unit) {
                viewModel.startListening()
            }

            DisposableEffect(Unit) {
                onDispose { viewModel.stopListening() }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    PitchGauge(
                        centsOff = state.smoothedCentsOff,
                        noteName = state.displayedResult.noteName,
                        octave = state.displayedResult.octave,
                        isSilent = !state.displayedResult.isValid,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.height(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.isInTune) {
                            Text(
                                text = stringResource(R.string.tuner_in_tune),
                                style = MaterialTheme.typography.titleMedium,
                                color = Green80,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.tuner_frequency, state.displayedResult.frequency),
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.alpha(if (state.displayedResult.isValid) 1f else 0f)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Box(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.tuner_reference_pitch),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterStart)
                        )
                        Text(
                            text = stringResource(
                                R.string.tuner_reference_pitch_value,
                                state.concertPitchA4.roundToInt()
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.align(Alignment.Center)
                        )
                        Row(
                            modifier = Modifier.align(Alignment.CenterEnd),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            PresetButton(
                                label = stringResource(R.string.tuner_preset_baroque),
                                selected = state.concertPitchA4.roundToInt() == BAROQUE_PITCH,
                                onClick = {
                                    viewModel.setConcertPitch(BAROQUE_PITCH.toFloat())
                                    viewModel.commitConcertPitch()
                                }
                            )
                            PresetButton(
                                label = stringResource(R.string.tuner_preset_default),
                                selected = state.concertPitchA4.roundToInt() == DEFAULT_PITCH,
                                onClick = {
                                    viewModel.setConcertPitch(NoteUtils.DEFAULT_CONCERT_PITCH)
                                    viewModel.commitConcertPitch()
                                }
                            )
                        }
                    }

                    Slider(
                        value = state.concertPitchA4,
                        onValueChange = viewModel::setConcertPitch,
                        onValueChangeFinished = viewModel::commitConcertPitch,
                        valueRange = NoteUtils.MIN_CONCERT_PITCH..NoteUtils.MAX_CONCERT_PITCH,
                        steps = (NoteUtils.MAX_CONCERT_PITCH - NoteUtils.MIN_CONCERT_PITCH).toInt() - 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private const val BAROQUE_PITCH = 415
private val DEFAULT_PITCH = NoteUtils.DEFAULT_CONCERT_PITCH.roundToInt()

@Composable
private fun PresetButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val content: @Composable RowScope.() -> Unit = {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
    val contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)

    if (selected) {
        FilledTonalButton(onClick = onClick, contentPadding = contentPadding, content = content)
    } else {
        OutlinedButton(onClick = onClick, contentPadding = contentPadding, content = content)
    }
}
