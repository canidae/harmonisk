package com.perfectpitch.ui.melody

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.perfectpitch.ui.components.KeepScreenOn
import com.perfectpitch.ui.components.PIANO_HEIGHT_DP
import com.perfectpitch.ui.components.RequireMicPermission

private val BUTTON_AREA_WIDTH = 52.dp
private val OVERLAY_BG = Color(0xAA1A1A2E)
private val OVERLAY_ICON_TINT = Color.White.copy(alpha = 0.9f)
private val OVERLAY_BUTTON_SIZE = 40.dp

@Composable
fun MelodyPlayScreen(
    onBack: () -> Unit,
    viewModel: MelodyViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val density = LocalDensity.current
    val rightPaddingPx = with(density) { BUTTON_AREA_WIDTH.toPx() }
    var showResultsModal by remember { mutableStateOf(false) }

    KeepScreenOn()

    // Auto-start playing when entering this screen
    LaunchedEffect(Unit) {
        if (state.phase == PlayPhase.NOT_STARTED) {
            viewModel.startPlaying()
        }
    }

    // Show results modal when finished
    LaunchedEffect(state.phase) {
        if (state.phase == PlayPhase.FINISHED) {
            showResultsModal = true
        }
    }

    RequireMicPermission {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        // Vertical zoom only (pinch)
                        if (zoom != 1f) {
                            viewModel.onVerticalZoom(zoom)
                        }
                        // Vertical scroll: drag up = see future notes, drag down = see past notes
                        if (pan.y != 0f) {
                            val secondsPerPx = 1.0 / (state.fallSpeedPxPerSec * density.density)
                            viewModel.onVerticalScroll(pan.y * secondsPerPx)
                        }
                    }
                }
        ) {
            val track = state.melodyTrack

            if (track != null) {
                // Canvas
                MelodyPlayCanvas(
                    state = state,
                    melodyTrack = track,
                    detectedMidiNote = if (state.pitchResult.isValid) state.pitchResult.midiNoteNumber else null,
                    detectedCentsOff = if (state.pitchResult.isValid) state.pitchResult.centsOff else null,
                    rightPaddingPx = rightPaddingPx,
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

                // Waiting indicator
                if (state.phase == PlayPhase.WAITING_FOR_NOTE) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Surface(
                            color = Color(0xCC1A1A2E),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "Play the note!",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                // Top-left: progress info
                if (state.phase == PlayPhase.PLAYING || state.phase == PlayPhase.PAUSED || state.phase == PlayPhase.WAITING_FOR_NOTE) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val progress = if (track.durationSeconds > 0) {
                            (state.currentTimeSeconds / track.durationSeconds * 100).toInt().coerceAtMost(100)
                        } else 0
                        Surface(color = Color(0xCC1A1A2E), shape = MaterialTheme.shapes.small) {
                            Text(
                                text = "$progress%",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        val notesHit = state.noteResults.count { it.value != NoteResultState.MISS && it.value != NoteResultState.ACTIVE }
                        Surface(color = Color(0xCC1A1A2E), shape = MaterialTheme.shapes.small) {
                            Text(
                                text = "$notesHit / ${track.notes.size}",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Right-side button column (above piano)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = PIANO_HEIGHT_DP.dp + 8.dp, end = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Config dropdown
                    var showConfigMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { showConfigMenu = true },
                            modifier = Modifier
                                .size(OVERLAY_BUTTON_SIZE)
                                .background(OVERLAY_BG, CircleShape)
                        ) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Settings",
                                tint = OVERLAY_ICON_TINT,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showConfigMenu,
                            onDismissRequest = { showConfigMenu = false },
                            offset = DpOffset((-180).dp, 0.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Wait for note") },
                                trailingIcon = {
                                    Switch(
                                        checked = state.waitMode,
                                        onCheckedChange = { viewModel.setWaitMode(it) },
                                        modifier = Modifier.height(20.dp)
                                    )
                                },
                                onClick = { viewModel.setWaitMode(!state.waitMode) }
                            )
                            if (state.waitMode) {
                                DropdownMenuItem(
                                    text = { Text("Allow wrong notes") },
                                    trailingIcon = {
                                        Switch(
                                            checked = state.allowWrongNotes,
                                            onCheckedChange = { viewModel.setAllowWrongNotes(it) },
                                            modifier = Modifier.height(20.dp)
                                        )
                                    },
                                    onClick = { viewModel.setAllowWrongNotes(!state.allowWrongNotes) }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Tempo: ${"%.0f".format(state.tempoMultiplier * 100)}%") },
                                onClick = { }
                            )
                            Slider(
                                value = state.tempoMultiplier,
                                onValueChange = { viewModel.setTempoMultiplier(it) },
                                valueRange = 0.5f..1.5f,
                                steps = 9,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (state.transposeSemitones == 0) "Transpose: 0"
                                        else if (state.transposeSemitones > 0) "Transpose: +${state.transposeSemitones}"
                                        else "Transpose: ${state.transposeSemitones}"
                                    )
                                },
                                onClick = { }
                            )
                            Slider(
                                value = state.transposeSemitones.toFloat(),
                                onValueChange = { viewModel.setTranspose(it.toInt()) },
                                valueRange = -36f..36f,
                                steps = 71,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }

                    // Play/Pause button
                    if (state.phase == PlayPhase.PLAYING || state.phase == PlayPhase.PAUSED || state.phase == PlayPhase.WAITING_FOR_NOTE) {
                        IconButton(
                            onClick = {
                                if (state.phase == PlayPhase.PAUSED) viewModel.resume() else viewModel.pause()
                            },
                            modifier = Modifier
                                .size(OVERLAY_BUTTON_SIZE)
                                .background(OVERLAY_BG, CircleShape)
                        ) {
                            Icon(
                                if (state.phase == PlayPhase.PAUSED) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                contentDescription = if (state.phase == PlayPhase.PAUSED) "Resume" else "Pause",
                                tint = OVERLAY_ICON_TINT,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Scroll button
                    if (state.songTimeOffsetSeconds != 0.0) {
                        IconButton(
                            onClick = {
                                if (state.phase == PlayPhase.FINISHED) {
                                    viewModel.scrollToBeginning()
                                } else {
                                    viewModel.scrollToLive()
                                }
                            },
                            modifier = Modifier
                                .size(OVERLAY_BUTTON_SIZE)
                                .background(OVERLAY_BG, CircleShape)
                        ) {
                            Icon(
                                if (state.phase == PlayPhase.FINISHED) Icons.Filled.KeyboardArrowDown
                                else Icons.Filled.KeyboardArrowUp,
                                contentDescription = if (state.phase == PlayPhase.FINISHED) "Scroll to beginning" else "Back to live",
                                tint = OVERLAY_ICON_TINT,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Show Results button (reopen modal)
                    if (state.phase == PlayPhase.FINISHED) {
                        IconButton(
                            onClick = { showResultsModal = true },
                            modifier = Modifier
                                .size(OVERLAY_BUTTON_SIZE)
                                .background(OVERLAY_BG, CircleShape)
                        ) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = "Results",
                                tint = OVERLAY_ICON_TINT,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Play Again button
                    if (state.phase == PlayPhase.FINISHED) {
                        IconButton(
                            onClick = { viewModel.startPlaying() },
                            modifier = Modifier
                                .size(OVERLAY_BUTTON_SIZE)
                                .background(OVERLAY_BG, CircleShape)
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "Play Again",
                                tint = OVERLAY_ICON_TINT,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Close button
                    IconButton(
                        onClick = { viewModel.stop(); onBack() },
                        modifier = Modifier
                            .size(OVERLAY_BUTTON_SIZE)
                            .background(OVERLAY_BG, CircleShape)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = OVERLAY_ICON_TINT,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

            } else {
                // Error fallback — melody failed to load
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Failed to load melody",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onBack) {
                            Text("Go Back")
                        }
                    }
                }
            }
        }
    }

    // Results modal
    if (showResultsModal && state.melodyScore != null) {
        ScoreResultModal(
            melodyScore = state.melodyScore!!,
            onDismiss = { showResultsModal = false }
        )
    }
}
