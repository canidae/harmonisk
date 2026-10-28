package net.exent.harmonisk.ui.tuner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import net.exent.harmonisk.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.exent.harmonisk.music.NoteUtils
import net.exent.harmonisk.ui.components.BrandTopBar
import net.exent.harmonisk.ui.components.PitchGauge
import net.exent.harmonisk.ui.components.RequireMicPermission
import net.exent.harmonisk.ui.components.KeepScreenOn
import net.exent.harmonisk.ui.components.brandBackground
import net.exent.harmonisk.ui.theme.TunerGreen
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
        modifier = Modifier
            .fillMaxSize()
            .brandBackground(),
        containerColor = Color.Transparent,
        topBar = {
            BrandTopBar(
                title = stringResource(R.string.tuner_title),
                onBack = onBack
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
                    .navigationBarsPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(
                            Brush.radialGradient(
                                listOf(TunerGreen.copy(alpha = 0.12f), Color.Transparent)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    PitchGauge(
                        centsOff = state.smoothedCentsOff,
                        noteName = state.displayedResult.noteName,
                        octave = state.displayedResult.octave,
                        isSilent = !state.displayedResult.isValid,
                        isInTune = state.isInTune,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.height(44.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        InTuneBadge(visible = state.isInTune)
                    }

                    Text(
                        text = stringResource(R.string.tuner_frequency, state.displayedResult.frequency),
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.alpha(if (state.displayedResult.isValid) 1f else 0f)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
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
    }
}

@Composable
private fun InTuneBadge(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f)
    ) {
        Surface(
            color = TunerGreen.copy(alpha = 0.16f),
            contentColor = TunerGreen,
            shape = RoundedCornerShape(50)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.tuner_in_tune),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
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
