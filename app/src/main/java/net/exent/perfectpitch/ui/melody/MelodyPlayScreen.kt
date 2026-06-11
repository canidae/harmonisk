package net.exent.perfectpitch.ui.melody

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.exent.perfectpitch.ui.components.CanvasMode
import net.exent.perfectpitch.ui.components.KeepScreenOn
import net.exent.perfectpitch.ui.components.PIANO_HEIGHT_DP
import net.exent.perfectpitch.ui.components.RequireMicPermission
import net.exent.perfectpitch.ui.components.SharedNoteCanvas
import net.exent.perfectpitch.ui.OVERLAY_BG
import net.exent.perfectpitch.ui.OVERLAY_BUTTON_SIZE
import net.exent.perfectpitch.ui.OVERLAY_ICON_TINT
import net.exent.perfectpitch.ui.PANEL_BG
import net.exent.perfectpitch.ui.PANEL_BTN_SIZE
import kotlin.math.roundToInt

@Composable
fun MelodyPlayScreen(
    onBack: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    viewModel: MelodyViewModel = hiltViewModel()
) {
    val gameState by viewModel.gameState.collectAsStateWithLifecycle()
    val viewState by viewModel.viewState.collectAsStateWithLifecycle()
    val deviceState by viewModel.deviceState.collectAsStateWithLifecycle()
    val isPreviewPlaying by viewModel.isPreviewPlaying.collectAsState()
    val uncalibratedDevice by viewModel.uncalibratedDevicePrompt.collectAsState()
    val audioDelay by viewModel.activeAudioDelay.collectAsState()
    val visualDelay by viewModel.activeVisualDelay.collectAsState()
    val scoreHistory by viewModel.currentScoreHistory.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    var showResultsModal by remember { mutableStateOf(false) }
    var showConfigMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler { viewModel.stop(); onBack() }

    KeepScreenOn()

    LaunchedEffect(Unit) {
        viewModel.initSynthesizer()
        if (gameState.phase == PlayPhase.NOT_STARTED) {
            viewModel.enterReadyPhase()
        }
    }

    LaunchedEffect(gameState.phase) {
        if (gameState.phase == PlayPhase.FINISHED) {
            showResultsModal = true
        }
    }

    LaunchedEffect(uncalibratedDevice) {
        val deviceName = uncalibratedDevice
        if (deviceName != null && viewModel.shouldShowCalibrationPrompt()) {
            val result = snackbarHostState.showSnackbar(
                message = "$deviceName is not calibrated",
                actionLabel = "Settings",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.stop()
                onNavigateToSettings()
            }
            viewModel.dismissCalibrationPrompt()
        }
    }

    RequireMicPermission(skip = deviceState.midiInputEnabled) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {

                // ── Top panel ────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PANEL_BG)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // MIDI input toggle
                    if (deviceState.midiAvailable) {
                        val isMidi = deviceState.midiInputEnabled
                        IconButton(
                            onClick = { viewModel.toggleMidiInput() },
                            modifier = Modifier.size(PANEL_BTN_SIZE)
                                .then(if (isMidi) Modifier.border(1.dp, Color(0xFF7E57C2), CircleShape) else Modifier)
                        ) {
                            Icon(
                                if (isMidi) Icons.Filled.MusicNote else Icons.Filled.Mic,
                                contentDescription = if (isMidi) "MIDI input" else "Mic input",
                                tint = if (isMidi) Color(0xFF7E57C2) else OVERLAY_ICON_TINT,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    // MIDI output toggle
                    if (deviceState.midiAvailable) {
                        val isMidi = deviceState.midiOutputEnabled
                        IconButton(
                            onClick = { viewModel.toggleMidiOutput() },
                            modifier = Modifier.size(PANEL_BTN_SIZE)
                                .then(if (isMidi) Modifier.border(1.dp, Color(0xFF7E57C2), CircleShape) else Modifier)
                        ) {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = if (isMidi) "MIDI output active" else "MIDI output off",
                                tint = if (isMidi) Color(0xFF7E57C2) else OVERLAY_ICON_TINT,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // BPM / time sig + live progress
                    val track = gameState.melodyTrack
                    if (track != null) {
                        val currentTick = track.secondsToTick(gameState.currentTimeSeconds.coerceAtLeast(0.0))
                        val currentBpm = track.bpmAt(currentTick).roundToInt()
                        val currentTimeSig = track.timeSignatureAt(currentTick)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "$currentBpm  ${currentTimeSig.numerator}/${currentTimeSig.denominator}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                        val showProgress = gameState.phase == PlayPhase.PLAYING ||
                                gameState.phase == PlayPhase.PAUSED ||
                                gameState.phase == PlayPhase.WAITING_FOR_NOTE
                        if (showProgress) {
                            val progress = if (track.durationSeconds > 0)
                                (gameState.currentTimeSeconds / track.durationSeconds * 100).toInt().coerceAtMost(100)
                            else 0
                            val notesHit = gameState.noteResults.count {
                                it.value != NoteResultState.MISS && it.value != NoteResultState.ACTIVE
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "$progress%  $notesHit/${track.notes.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // Preview melody button
                    if (gameState.phase == PlayPhase.READY || gameState.phase == PlayPhase.PAUSED ||
                        gameState.phase == PlayPhase.PLAYING || gameState.phase == PlayPhase.WAITING_FOR_NOTE) {
                        IconButton(
                            onClick = { if (isPreviewPlaying) viewModel.stopPreview() else viewModel.previewMelody() },
                            modifier = Modifier.size(PANEL_BTN_SIZE)
                        ) {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = if (isPreviewPlaying) "Stop preview" else "Preview melody",
                                tint = if (isPreviewPlaying) Color(0xFF90CAF9) else OVERLAY_ICON_TINT,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    // Metronome toggle
                    IconButton(
                        onClick = { viewModel.toggleMetronome() },
                        modifier = Modifier.size(PANEL_BTN_SIZE)
                    ) {
                        Icon(
                            Icons.Filled.Timer,
                            contentDescription = if (gameState.metronomeEnabled) "Metronome on" else "Metronome off",
                            tint = if (gameState.metronomeEnabled) Color(0xFF90CAF9) else OVERLAY_ICON_TINT,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    // Settings
                    MelodySettingsMenu(
                        expanded = showConfigMenu,
                        onDismiss = { showConfigMenu = false },
                        onOpen = { showConfigMenu = true },
                        waitMode = gameState.waitMode,
                        onWaitModeChange = { viewModel.setWaitMode(it) },
                        allowWrongNotes = gameState.allowWrongNotes,
                        onAllowWrongNotesChange = { viewModel.setAllowWrongNotes(it) },
                        tempoMultiplier = gameState.tempoMultiplier,
                        onTempoChange = { viewModel.setTempoMultiplier(it) },
                        transposeSemitones = gameState.transposeSemitones,
                        onTransposeChange = { viewModel.setTranspose(it) },
                        playMelodyAudio = gameState.playMelodyAudio,
                        onPlayMelodyAudioChange = { viewModel.setPlayMelodyAudio(it) },
                        audioDelay = audioDelay,
                        visualDelay = visualDelay
                    )
                }

                // ── Canvas area ──────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                if (zoom != 1f) viewModel.onVerticalZoom(zoom)
                                if (pan.y != 0f) {
                                    val secondsPerPx = 1.0 / (viewState.fallSpeedPxPerSec * density.density)
                                    viewModel.onVerticalScroll(pan.y * secondsPerPx)
                                }
                            }
                        }
                        .pointerInput(gameState.phase) {
                            detectTapGestures {
                                val phase = gameState.phase
                                if (phase == PlayPhase.PLAYING || phase == PlayPhase.COUNTDOWN || phase == PlayPhase.WAITING_FOR_NOTE) {
                                    viewModel.pause()
                                }
                            }
                        }
                ) {
                    val track = gameState.melodyTrack

                    if (track != null) {
                        SharedNoteCanvas(
                            mode = CanvasMode.Melody(
                                melodyTrack = track,
                                noteResults = gameState.noteResults,
                                currentNoteIndex = gameState.currentNoteIndex,
                                noteCentsHistory = gameState.noteCentsHistory,
                                noteCentsPositions = gameState.noteCentsPositions,
                                notePlayProgress = gameState.notePlayProgress,
                                noteFirstDetectionStart = gameState.noteFirstDetectionStart,
                                currentTimeSeconds = gameState.currentTimeSeconds - visualDelay / 1000.0,
                                songTimeOffsetSeconds = viewState.songTimeOffsetSeconds,
                                fallSpeedPxPerSec = viewState.fallSpeedPxPerSec,
                                detectedMidiNote = if (gameState.pitchResult.isValid) gameState.pitchResult.midiNoteNumber else null,
                                detectedCentsOff = if (gameState.pitchResult.isValid) gameState.pitchResult.centsOff else null
                            ),
                            pianoAtBottom = true,
                            viewMinMidi = viewState.viewMinMidi,
                            viewMaxMidi = viewState.viewMaxMidi,
                            modifier = Modifier.fillMaxSize()
                        )

                        if (gameState.phase == PlayPhase.READY || gameState.phase == PlayPhase.PAUSED) {
                            IconButton(
                                onClick = {
                                    if (isPreviewPlaying) viewModel.stopPreview()
                                    if (gameState.phase == PlayPhase.READY) viewModel.startPlaying()
                                    else viewModel.resume()
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = PIANO_HEIGHT_DP.dp + 24.dp)
                                    .size(72.dp)
                                    .background(Color(0xFF66BB6A).copy(alpha = 0.25f), CircleShape)
                                    .border(2.dp, Color(0xFF66BB6A).copy(alpha = 0.7f), CircleShape)
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = if (gameState.phase == PlayPhase.READY) "Start" else "Resume",
                                    tint = Color(0xFF66BB6A).copy(alpha = 0.9f),
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }

                        // Contextual action buttons (bottom-right overlay)
                        val showScrollButton = gameState.phase == PlayPhase.FINISHED && !gameState.isAtScrollStart(viewState.songTimeOffsetSeconds)
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(bottom = PIANO_HEIGHT_DP.dp + 8.dp, end = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (showScrollButton) {
                                IconButton(
                                    onClick = { viewModel.scrollToBeginning() },
                                    modifier = Modifier.size(OVERLAY_BUTTON_SIZE).background(OVERLAY_BG, CircleShape)
                                ) {
                                    Icon(
                                        Icons.Filled.KeyboardArrowDown,
                                        contentDescription = "Scroll to beginning",
                                        tint = OVERLAY_ICON_TINT,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            if (gameState.phase == PlayPhase.FINISHED) {
                                IconButton(
                                    onClick = { showResultsModal = true },
                                    modifier = Modifier.size(OVERLAY_BUTTON_SIZE).background(OVERLAY_BG, CircleShape)
                                ) {
                                    Icon(Icons.Filled.Star, contentDescription = "Results", tint = OVERLAY_ICON_TINT, modifier = Modifier.size(20.dp))
                                }
                                IconButton(
                                    onClick = { viewModel.startPlaying() },
                                    modifier = Modifier.size(OVERLAY_BUTTON_SIZE).background(OVERLAY_BG, CircleShape)
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Play Again", tint = OVERLAY_ICON_TINT, modifier = Modifier.size(20.dp))
                                }
                            }
                        }

                    } else {
                        // Error fallback — melody failed to load
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Failed to load melody", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = onBack) { Text("Go Back") }
                            }
                        }
                    }
                }
            }

            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    if (showResultsModal && gameState.melodyScore != null) {
        ScoreResultModal(
            melodyScore = gameState.melodyScore!!,
            onDismiss = { showResultsModal = false },
            scoreHistory = scoreHistory
        )
    }
}

@Composable
private fun MelodySettingsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    waitMode: Boolean,
    onWaitModeChange: (Boolean) -> Unit,
    allowWrongNotes: Boolean,
    onAllowWrongNotesChange: (Boolean) -> Unit,
    tempoMultiplier: Float,
    onTempoChange: (Float) -> Unit,
    transposeSemitones: Int,
    onTransposeChange: (Int) -> Unit,
    playMelodyAudio: Boolean,
    onPlayMelodyAudioChange: (Boolean) -> Unit,
    audioDelay: Int,
    visualDelay: Int
) {
    Box {
        IconButton(
            onClick = onOpen,
            modifier = Modifier.size(PANEL_BTN_SIZE)
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = OVERLAY_ICON_TINT, modifier = Modifier.size(17.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            offset = DpOffset((-180).dp, 0.dp)
        ) {
            DropdownMenuItem(
                text = { Text("Wait for note") },
                trailingIcon = {
                    Switch(checked = waitMode, onCheckedChange = onWaitModeChange, modifier = Modifier.height(20.dp))
                },
                onClick = { onWaitModeChange(!waitMode) }
            )
            if (waitMode) {
                DropdownMenuItem(
                    text = { Text("Allow wrong notes") },
                    trailingIcon = {
                        Switch(checked = allowWrongNotes, onCheckedChange = onAllowWrongNotesChange, modifier = Modifier.height(20.dp))
                    },
                    onClick = { onAllowWrongNotesChange(!allowWrongNotes) }
                )
            }
            DropdownMenuItem(text = { Text("Tempo: ${"%.0f".format(tempoMultiplier * 100)}%") }, onClick = {})
            Slider(
                value = tempoMultiplier,
                onValueChange = onTempoChange,
                valueRange = 0.5f..1.5f,
                steps = 9,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(
                        when {
                            transposeSemitones == 0 -> "Transpose: 0"
                            transposeSemitones > 0  -> "Transpose: +$transposeSemitones"
                            else                    -> "Transpose: $transposeSemitones"
                        }
                    )
                },
                onClick = {}
            )
            Slider(
                value = transposeSemitones.toFloat(),
                onValueChange = { onTransposeChange(it.toInt()) },
                valueRange = -24f..24f,
                steps = 47,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Play melody audio") },
                trailingIcon = {
                    Switch(checked = playMelodyAudio, onCheckedChange = onPlayMelodyAudioChange, modifier = Modifier.height(20.dp))
                },
                onClick = { onPlayMelodyAudioChange(!playMelodyAudio) }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Audio delay: ${audioDelay}ms") },
                onClick = {}
            )
            DropdownMenuItem(
                text = { Text("Visual delay: ${visualDelay}ms") },
                onClick = {}
            )
        }
    }
}
