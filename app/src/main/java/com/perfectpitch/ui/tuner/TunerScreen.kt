package com.perfectpitch.ui.tuner

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.perfectpitch.ui.components.NoteDisplay
import com.perfectpitch.ui.components.PitchGauge
import com.perfectpitch.ui.components.RequireMicPermission
import com.perfectpitch.ui.components.KeepScreenOn

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
                title = { Text("Tune Instrument") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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

            val contentAlpha = if (state.isHeld) 0.5f else 1f

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Note display
                Box(modifier = Modifier.alpha(contentAlpha)) {
                    NoteDisplay(
                        noteName = state.displayedResult.noteName,
                        octave = state.displayedResult.octave,
                        isSilent = !state.displayedResult.isValid
                    )
                }

                // Pitch gauge
                Box(modifier = Modifier.alpha(contentAlpha)) {
                    PitchGauge(
                        centsOff = state.smoothedCentsOff,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Frequency display
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.alpha(contentAlpha)
                ) {
                    if (state.displayedResult.isValid) {
                        Text(
                            text = "${"%.1f".format(state.displayedResult.frequency)} Hz",
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center
                        )

                        if (state.isHeld) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "held",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Reference: ${"%.0f".format(state.referenceFrequency)} Hz",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                }
            }
        }
    }
}
