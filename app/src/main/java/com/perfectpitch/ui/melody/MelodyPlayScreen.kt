package com.perfectpitch.ui.melody

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.perfectpitch.ui.components.RequireMicPermission

@Composable
fun MelodyPlayScreen(
    onBack: () -> Unit,
    onShowResults: () -> Unit,
    viewModel: MelodyViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    // Navigate to results when finished
    LaunchedEffect(state.phase) {
        if (state.phase == PlayPhase.FINISHED) {
            onShowResults()
        }
    }

    RequireMicPermission {
        Box(modifier = Modifier.fillMaxSize()) {
            val track = state.melodyTrack
            if (track != null) {
                // Synthesia canvas (full screen background)
                SynthesiaCanvas(
                    melodyTrack = track,
                    currentTimeSeconds = state.currentTimeSeconds,
                    noteResults = state.noteResults,
                    detectedMidiNote = if (state.pitchResult.isValid) state.pitchResult.midiNoteNumber else null,
                    modifier = Modifier.fillMaxSize()
                )

                // Countdown overlay
                if (state.phase == PlayPhase.COUNTDOWN) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${state.countdownValue}",
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                // Top overlay - score and progress
                if (state.phase == PlayPhase.PLAYING || state.phase == PlayPhase.PAUSED) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Progress
                        val progress = if (track.durationSeconds > 0) {
                            (state.currentTimeSeconds / track.durationSeconds * 100).toInt()
                        } else 0
                        Text(
                            text = "$progress%",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        // Notes hit
                        val notesHit = state.noteResults.count { it.value != NoteResultState.MISS }
                        Text(
                            text = "$notesHit / ${track.notes.size}",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        // Controls
                        Row {
                            if (state.phase == PlayPhase.PLAYING) {
                                IconButton(onClick = { viewModel.pause() }) {
                                    Icon(Icons.Default.Refresh, "Pause", tint = MaterialTheme.colorScheme.onBackground)
                                }
                            } else {
                                IconButton(onClick = { viewModel.resume() }) {
                                    Icon(Icons.Default.PlayArrow, "Resume", tint = MaterialTheme.colorScheme.onBackground)
                                }
                            }
                            IconButton(onClick = { viewModel.stop(); onBack() }) {
                                Icon(Icons.Default.Close, "Stop", tint = MaterialTheme.colorScheme.onBackground)
                            }
                        }
                    }
                }

                // Bottom overlay - detected note
                if (state.pitchResult.isValid && state.phase == PlayPhase.PLAYING) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${state.pitchResult.noteName}${state.pitchResult.octave}",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}
