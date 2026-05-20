package com.perfectpitch.ui.melody

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.perfectpitch.ui.components.CanvasMode
import com.perfectpitch.ui.components.KeepScreenOn
import com.perfectpitch.ui.components.PIANO_HEIGHT_DP
import com.perfectpitch.ui.components.RequireMicPermission
import com.perfectpitch.ui.components.SharedNoteCanvas
import kotlin.math.roundToInt

private val BUTTON_AREA_WIDTH = 52.dp
private val OVERLAY_BG = Color(0xAA1A1A2E)
private val OVERLAY_ICON_TINT = Color.White.copy(alpha = 0.9f)
private val OVERLAY_BUTTON_SIZE = 40.dp

@Composable
fun MelodyPlayScreen(
    onBack: () -> Unit,
    onNavigateToCalibration: () -> Unit = {},
    viewModel: MelodyViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val isPreviewPlaying by viewModel.isPreviewPlaying.collectAsState()
    val continuousPitchLine by viewModel.continuousPitchLine.collectAsState()
    val uncalibratedDevice by viewModel.uncalibratedDevicePrompt.collectAsState()
    val splitPercent by viewModel.activeSplitPercent.collectAsState()
    val outputDelay by viewModel.activeOutputDelay.collectAsState()
    val inputDelay by viewModel.activeInputDelay.collectAsState()
    val density = LocalDensity.current
    val context = LocalContext.current
    val rightPaddingPx = with(density) { BUTTON_AREA_WIDTH.toPx() }
    var showResultsModal by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler { viewModel.stop(); onBack() }

    KeepScreenOn()

    // Initialize synthesizer; don't auto-start
    LaunchedEffect(Unit) {
        viewModel.initSynthesizer(context)
        if (state.phase == PlayPhase.NOT_STARTED) {
            viewModel.enterReadyPhase()
        }
    }

    LaunchedEffect(state.phase) {
        if (state.phase == PlayPhase.FINISHED) {
            showResultsModal = true
        }
    }

    LaunchedEffect(uncalibratedDevice) {
        val deviceName = uncalibratedDevice
        if (deviceName != null && viewModel.shouldShowCalibrationPrompt()) {
            val result = snackbarHostState.showSnackbar(
                message = "$deviceName is not calibrated",
                actionLabel = "Calibrate",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.stop()
                onNavigateToCalibration()
            }
            viewModel.dismissCalibrationPrompt()
        }
    }

    RequireMicPermission {
        Box(modifier = Modifier.fillMaxSize()) {
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
                .pointerInput(state.phase) {
                    detectTapGestures {
                        val phase = state.phase
                        if (phase == PlayPhase.PLAYING || phase == PlayPhase.COUNTDOWN || phase == PlayPhase.WAITING_FOR_NOTE) {
                            viewModel.pause()
                        }
                    }
                }
        ) {
            val track = state.melodyTrack

            if (track != null) {
                SharedNoteCanvas(
                    mode = CanvasMode.Melody(
                        melodyTrack = track,
                        noteResults = state.noteResults,
                        currentNoteIndex = state.currentNoteIndex,
                        pitchTrace = state.pitchTrace,
                        currentTimeSeconds = state.currentTimeSeconds,
                        songTimeOffsetSeconds = state.songTimeOffsetSeconds,
                        fallSpeedPxPerSec = state.fallSpeedPxPerSec,
                        detectedMidiNote = if (state.pitchResult.isValid) state.pitchResult.midiNoteNumber else null,
                        detectedCentsOff = if (state.pitchResult.isValid) state.pitchResult.centsOff else null
                    ),
                    pianoAtBottom = true,
                    viewMinMidi = state.viewMinMidi,
                    viewMaxMidi = state.viewMaxMidi,
                    continuousPitchLine = continuousPitchLine,
                    rightPaddingPx = rightPaddingPx,
                    modifier = Modifier.fillMaxSize()
                )

                // Tempo / time signature chip (top-left)
                val currentTick = track.secondsToTick(state.currentTimeSeconds.coerceAtLeast(0.0))
                val currentBpm = track.bpmAt(currentTick).roundToInt()
                val currentTimeSig = track.timeSignatureAt(currentTick)
                Surface(
                    color = Color(0xCC1A1A2E),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = 8.dp)
                ) {
                    Text(
                        text = "$currentBpm BPM  ${currentTimeSig.numerator}/${currentTimeSig.denominator}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

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

                if (state.phase == PlayPhase.READY || state.phase == PlayPhase.PAUSED) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = {
                                if (isPreviewPlaying) viewModel.stopPreview()
                                if (state.phase == PlayPhase.READY) viewModel.startPlaying()
                                else viewModel.resume()
                            },
                            modifier = Modifier
                                .size(72.dp)
                                .background(Color.White.copy(alpha = 0.2f), CircleShape)
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = if (state.phase == PlayPhase.READY) "Start" else "Resume",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                }

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

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = PIANO_HEIGHT_DP.dp + 8.dp, end = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
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
                                valueRange = -24f..24f,
                                steps = 47,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Play melody audio") },
                                trailingIcon = {
                                    Switch(
                                        checked = state.playMelodyAudio,
                                        onCheckedChange = { viewModel.setPlayMelodyAudio(it) },
                                        modifier = Modifier.height(20.dp)
                                    )
                                },
                                onClick = { viewModel.setPlayMelodyAudio(!state.playMelodyAudio) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    val splitPct = (splitPercent * 100).roundToInt()
                                    Text("Audio delay split: $splitPct% output")
                                },
                                onClick = { }
                            )
                            Slider(
                                value = (splitPercent * 100).roundToInt().toFloat(),
                                onValueChange = { viewModel.saveSplitPercent(it / 100f) },
                                valueRange = 0f..100f,
                                steps = 99,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Out: ${outputDelay}ms", style = MaterialTheme.typography.labelSmall)
                                Text("In: ${inputDelay}ms", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    // Listen to melody button (visible in READY and PAUSED states too)
                    if (state.phase == PlayPhase.READY || state.phase == PlayPhase.PAUSED ||
                        state.phase == PlayPhase.PLAYING || state.phase == PlayPhase.WAITING_FOR_NOTE) {
                        IconButton(
                            onClick = {
                                if (isPreviewPlaying) viewModel.stopPreview() else viewModel.previewMelody()
                            },
                            modifier = Modifier
                                .size(OVERLAY_BUTTON_SIZE)
                                .background(OVERLAY_BG, CircleShape)
                        ) {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = if (isPreviewPlaying) "Stop listening" else "Listen to melody",
                                tint = if (isPreviewPlaying) Color(0xFF90CAF9) else OVERLAY_ICON_TINT,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Scroll button: visible when scrolled away from live (active phases) or
                    // not yet at the beginning (FINISHED phase).
                    val showScrollButton = if (state.phase == PlayPhase.FINISHED) !state.isAtScrollStart
                                          else state.songTimeOffsetSeconds != 0.0
                    if (showScrollButton) {
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
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showResultsModal && state.melodyScore != null) {
        ScoreResultModal(
            melodyScore = state.melodyScore!!,
            onDismiss = { showResultsModal = false }
        )
    }
}
