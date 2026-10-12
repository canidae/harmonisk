package net.exent.harmonisk.ui.melody

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.exent.harmonisk.R
import net.exent.harmonisk.midi.MidiToAlphaTex
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.ui.components.CanvasMode
import net.exent.harmonisk.ui.components.VOICE_COLORS
import net.exent.harmonisk.ui.components.KeepScreenOn
import net.exent.harmonisk.ui.components.PIANO_HEIGHT_DP
import net.exent.harmonisk.ui.components.RequireMicPermission
import net.exent.harmonisk.ui.components.SharedNoteCanvas
import net.exent.harmonisk.ui.components.VIOLIN_LABEL_AREA_DP
import net.exent.harmonisk.ui.OVERLAY_BG
import net.exent.harmonisk.ui.OVERLAY_BUTTON_SIZE
import net.exent.harmonisk.ui.OVERLAY_ICON_TINT
import net.exent.harmonisk.ui.PANEL_BG
import net.exent.harmonisk.ui.utils.formatScore
import net.exent.harmonisk.ui.PANEL_BTN_SIZE
import net.exent.harmonisk.ui.theme.*
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
    val inputDelay by viewModel.activeInputDelay.collectAsState()
    val scoreHistory by viewModel.currentScoreHistory.collectAsStateWithLifecycle()
    val canAdvance by viewModel.canAdvanceLevel.collectAsStateWithLifecycle()
    val celebrationLevel by viewModel.celebrationLevel.collectAsStateWithLifecycle()
    val currentInstrument by viewModel.currentInstrument.collectAsStateWithLifecycle()
    val devModeEnabled by viewModel.devModeEnabled.collectAsStateWithLifecycle()
    val violinMode = currentInstrument == Instrument.VIOLIN
    val density = LocalDensity.current
    val context = LocalContext.current
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
                                .then(if (isMidi) Modifier.border(1.dp, MidiPurple, CircleShape) else Modifier)
                        ) {
                            Icon(
                                if (isMidi) Icons.Filled.MusicNote else Icons.Filled.Mic,
                                contentDescription = if (isMidi) stringResource(R.string.melody_play_midi_input) else stringResource(R.string.melody_play_mic_input),
                                tint = if (isMidi) MidiPurple else OVERLAY_ICON_TINT,
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
                                .then(if (isMidi) Modifier.border(1.dp, MidiPurple, CircleShape) else Modifier)
                        ) {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = if (isMidi) stringResource(R.string.melody_play_midi_output_on) else stringResource(R.string.melody_play_midi_output_off),
                                tint = if (isMidi) MidiPurple else OVERLAY_ICON_TINT,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Song title, level and latest score
                    gameState.melodyName?.let { name ->
                        Spacer(Modifier.width(6.dp))
                        val latestScore = scoreHistory.lastOrNull()?.let { formatScore(it.overallScore) }
                        Column {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = levelAndScoreText(gameState.currentLevel, latestScore),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // Preview melody button. Listening to the song is allowed before the take
                    // starts, after it has been played through, and while it is paused; during a
                    // scored take it is a developer feature.
                    if (gameState.phase == PlayPhase.READY || gameState.phase == PlayPhase.PAUSED ||
                        gameState.phase == PlayPhase.FINISHED ||
                        devModeEnabled && (gameState.phase == PlayPhase.PLAYING || gameState.phase == PlayPhase.WAITING_FOR_NOTE)) {
                        IconButton(
                            onClick = { if (isPreviewPlaying) viewModel.stopPreviewAndReturn() else viewModel.previewMelody() },
                            modifier = Modifier.size(PANEL_BTN_SIZE)
                        ) {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = if (isPreviewPlaying) stringResource(R.string.melody_stop_preview) else stringResource(R.string.melody_preview),
                                tint = if (isPreviewPlaying) MetronomeBlue else OVERLAY_ICON_TINT,
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
                            contentDescription = if (gameState.metronomeEnabled) stringResource(R.string.freeplay_metronome_on) else stringResource(R.string.freeplay_metronome_off),
                            tint = if (gameState.metronomeEnabled) MetronomeBlue else OVERLAY_ICON_TINT,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    // Voice selector (top-panel chip, like Free Play)
                    if (gameState.melodyTracks.size > 1) {
                        var showVoiceMenu by remember { mutableStateOf(false) }
                        val voiceColor = VOICE_COLORS[gameState.activeVoiceIndex % VOICE_COLORS.size]
                        Box {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(PANEL_BTN_SIZE)
                                    .background(voiceColor.copy(alpha = 0.3f), CircleShape)
                                    .border(1.dp, voiceColor, CircleShape)
                                    .clickable { showVoiceMenu = true }
                            ) {
                                Text("V${gameState.activeVoiceIndex + 1}", fontSize = 10.sp, color = voiceColor)
                            }
                            DropdownMenu(
                                expanded = showVoiceMenu,
                                onDismissRequest = { showVoiceMenu = false },
                                offset = DpOffset((-80).dp, 0.dp)
                            ) {
                                for (i in gameState.melodyTracks.indices) {
                                    val vColor = VOICE_COLORS[i % VOICE_COLORS.size]
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(Modifier.size(10.dp).background(vColor, CircleShape))
                                                Text(
                                                    "V${i + 1}",
                                                    fontWeight = if (i == gameState.activeVoiceIndex) FontWeight.Bold
                                                                 else FontWeight.Normal
                                                )
                                            }
                                        },
                                        onClick = { viewModel.setActiveVoice(i); showVoiceMenu = false }
                                    )
                                }
                            }
                        }
                    }

                    // Settings
                    MelodySettingsMenu(
                        expanded = showConfigMenu,
                        onDismiss = { showConfigMenu = false },
                        onOpen = { showConfigMenu = true },
                        currentLevel = gameState.currentLevel,
                        onLevelChange = { viewModel.setLevel(it) },
                        transposeSemitones = gameState.transposeSemitones,
                        onTransposeChange = { viewModel.setTranspose(it) },
                        accompanimentMode = gameState.accompanimentMode,
                        onAccompanimentModeChange = { viewModel.setAccompanimentMode(it) },
                        showLevelControl = devModeEnabled,
                        showPlayVoices = devModeEnabled
                    )
                }

                // ── Sheet music strip / play canvas ──
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    val track = gameState.melodyTrack
                    // How far the bars run ahead of the game clock, on the same rule the view model uses.
                    val adjustedNow = gameState.currentTimeSeconds +
                            nowLineLeadSeconds(gameState.phase, inputDelay)
                    // A preview drives the canvas with the song's own time, at the pace of the audio.
                    val canvasNow = gameState.previewTimeSeconds ?: adjustedNow

                    if (track != null) {
                        val alphaTex = remember(track) { MidiToAlphaTex.generate(track) }
                        val viewTimeSeconds = canvasNow + viewState.songTimeOffsetSeconds
                        SheetMusicView(
                            melodyTrack = track,
                            alphaTex = alphaTex,
                            currentTimeSeconds = canvasNow,
                            isPlaying = gameState.phase == PlayPhase.PLAYING || gameState.previewTimeSeconds != null,
                            seekTimeSeconds = canvasNow,
                            scrollTimeSeconds = viewTimeSeconds,
                            songTimeOffsetSeconds = viewState.songTimeOffsetSeconds,
                            durationSeconds = track.durationSeconds,
                            onScrolledToTime = { viewModel.seekToViewTime(it) },
                            modifier = Modifier.fillMaxWidth().height(205.dp).zIndex(1f)
                        )
                    }

                    // ── Play canvas ──────────────────────────────────────
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    if (zoom != 1f) viewModel.onVerticalZoom(zoom)
                                    val panValue = pan.y
                                    if (panValue != 0f) {
                                        val secondsPerPx = 1.0 / (viewState.fallSpeedPxPerSec * density.density)
                                        viewModel.onVerticalScroll(panValue * secondsPerPx)
                                    }
                                }
                            }
                            .pointerInput(gameState.phase) {
                                detectTapGestures {
                                    val phase = gameState.phase
                                    if (phase == PlayPhase.PLAYING || phase == PlayPhase.WAITING_FOR_NOTE) {
                                        viewModel.pause()
                                    }
                                }
                            }
                    ) {
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
                                currentTimeSeconds = canvasNow,
                                songTimeOffsetSeconds = viewState.songTimeOffsetSeconds,
                                fallSpeedPxPerSec = viewState.fallSpeedPxPerSec,
                                detectedMidiNote = if (gameState.pitchResult.isValid) gameState.pitchResult.midiNoteNumber else null,
                                detectedCentsOff = if (gameState.pitchResult.isValid) gameState.pitchResult.centsOff else null,
                                hideNoteBars = gameState.hideNoteBars,
                                violinMode = violinMode,
                                violinPositions = viewState.violinPositions
                            ),
                            pianoAtBottom = true,
                            viewMinMidi = viewState.viewMinMidi,
                            viewMaxMidi = viewState.viewMaxMidi,
                            modifier = Modifier.fillMaxSize()
                        )

                        if (gameState.previewTimeSeconds != null) {
                            Text(
                                text = stringResource(R.string.melody_preview),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .background(OVERLAY_BG, RoundedCornerShape(50))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }

                        if (gameState.previewTimeSeconds == null &&
                            (gameState.phase == PlayPhase.READY || gameState.phase == PlayPhase.PAUSED)
                        ) {
                            IconButton(
                                onClick = {
                                    if (isPreviewPlaying) viewModel.stopPreview()
                                    if (gameState.phase == PlayPhase.READY) viewModel.startPlaying()
                                    else viewModel.resume()
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = if (violinMode) VIOLIN_LABEL_AREA_DP.dp + 24.dp else PIANO_HEIGHT_DP.dp + 24.dp)
                                    .size(72.dp)
                                    .background(PlayGreen.copy(alpha = 0.25f), CircleShape)
                                    .border(2.dp, PlayGreen.copy(alpha = 0.7f), CircleShape)
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = if (gameState.phase == PlayPhase.READY) stringResource(R.string.melody_play_start) else stringResource(R.string.melody_play_resume),
                                    tint = PlayGreen.copy(alpha = 0.9f),
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }

                        // Contextual action buttons (bottom-right overlay)
                        val showScrollButton = gameState.phase == PlayPhase.FINISHED && !gameState.isAtScrollStart(viewState.songTimeOffsetSeconds)
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(bottom = if (violinMode) VIOLIN_LABEL_AREA_DP.dp + 8.dp else PIANO_HEIGHT_DP.dp + 8.dp, end = 6.dp),
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
                                        contentDescription = stringResource(R.string.melody_play_scroll_start),
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
                                    Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.melody_play_results), tint = OVERLAY_ICON_TINT, modifier = Modifier.size(20.dp))
                                }
                                IconButton(
                                    onClick = { viewModel.startPlaying() },
                                    modifier = Modifier.size(OVERLAY_BUTTON_SIZE).background(OVERLAY_BG, CircleShape)
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.melody_play_again), tint = OVERLAY_ICON_TINT, modifier = Modifier.size(20.dp))
                                }
                            }
                        }

                    } else {
                        // Error fallback — melody failed to load
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.melody_failed_load_melody), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = onBack) { Text(stringResource(R.string.melody_play_go_back)) }
                            }
                        }
                    }
                    }
                }
            }

            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    val score = gameState.melodyScore
    if (showResultsModal && score != null) {
        ScoreResultModal(
            melodyScore = score,
            onDismiss = { showResultsModal = false },
            scoreHistory = scoreHistory,
            currentLevel = gameState.currentLevel,
            canAdvance = canAdvance,
            onRetry = {
                showResultsModal = false
                viewModel.startPlaying()
            },
            onAdvance = {
                viewModel.advanceLevel()
                showResultsModal = false
                viewModel.startPlaying()
            },
            celebrationLevel = celebrationLevel
        )
    }
}

@Composable
private fun MelodySettingsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    currentLevel: Int,
    onLevelChange: (Int) -> Unit,
    transposeSemitones: Int,
    onTransposeChange: (Int) -> Unit,
    accompanimentMode: AccompanimentMode,
    onAccompanimentModeChange: (AccompanimentMode) -> Unit,
    showLevelControl: Boolean,
    showPlayVoices: Boolean
) {
    val minMenuWidth = LocalConfiguration.current.screenWidthDp.dp * 2f / 3f
    Box {
        IconButton(
            onClick = onOpen,
            modifier = Modifier.size(PANEL_BTN_SIZE)
        ) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings), tint = OVERLAY_ICON_TINT, modifier = Modifier.size(17.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            offset = DpOffset((-180).dp, 0.dp),
            modifier = Modifier.widthIn(min = minMenuWidth)
        ) {
            // Levels are earned by scoring; picking one freely is a developer feature.
            if (showLevelControl) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.melody_play_level, currentLevel)) },
                    onClick = {}
                )
                Slider(
                    value = currentLevel.toFloat(),
                    onValueChange = { onLevelChange(it.roundToInt().coerceIn(1, 5)) },
                    valueRange = 1f..5f,
                    steps = 3,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = {
                    Text(
                        when {
                            transposeSemitones == 0 -> stringResource(R.string.melody_play_transpose_0)
                            transposeSemitones > 0  -> stringResource(R.string.melody_play_transpose_plus, transposeSemitones)
                            else                    -> stringResource(R.string.melody_play_transpose, transposeSemitones)
                        }
                    )
                },
                onClick = {}
            )
            Slider(
                value = transposeSemitones.toFloat(),
                onValueChange = { onTransposeChange(it.roundToInt()) },
                valueRange = -12f..12f,
                steps = 23,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            if (showPlayVoices) {
                HorizontalDivider()
                // Accompaniment mode selector
                DropdownMenuItem(
                    text = { Text("Play voices:") },
                    onClick = {}
                )
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        AccompanimentMode.ALL to "All",
                        AccompanimentMode.EXCEPT to "Except",
                        AccompanimentMode.SELECTED_ONLY to "Selected",
                        AccompanimentMode.NONE to "None"
                    ).forEach { (mode, label) ->
                        val isSelected = accompanimentMode == mode
                        val bg = if (isSelected) MaterialTheme.colorScheme.primary else Color.DarkGray.copy(alpha = 0.3f)
                        val fg = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = 0.7f)
                        Box(
                            modifier = Modifier
                                .height(28.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(bg)
                                .clickable { onAccompanimentModeChange(mode) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = fg,
                                modifier = Modifier.padding(horizontal = 10.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
