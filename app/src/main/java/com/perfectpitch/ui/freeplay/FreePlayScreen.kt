package com.perfectpitch.ui.freeplay

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Undo
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.perfectpitch.ui.components.CanvasMode
import com.perfectpitch.ui.components.RequireMicPermission
import com.perfectpitch.ui.components.SharedNoteCanvas
import com.perfectpitch.ui.components.VOICE_COLORS
import com.perfectpitch.ui.components.KeepScreenOn
import kotlin.math.roundToInt

private val OVERLAY_BG = Color(0xAA1A1A2E)
private val OVERLAY_ICON_TINT = Color.White.copy(alpha = 0.9f)
private val PANEL_BG = Color(0xDD0D0D1A)
private val PANEL_BTN_SIZE = 32.dp

private val NOTE_DIVISIONS = listOf(4, 8, 16, 32, 64)
private val NOTE_DIVISION_LABELS = mapOf(4 to "♩ Quarter", 8 to "♪ 8th", 16 to "♬ 16th", 32 to "32nd", 64 to "64th")

private val PIANO_VISIBILITY_LABELS = mapOf(
    PianoVisibility.BOTH        to "Upper + lower keyboards",
    PianoVisibility.UPPER_ONLY  to "Upper keyboard only",
    PianoVisibility.LOWER_ONLY  to "Lower keyboard only",
    PianoVisibility.NONE        to "No piano keyboards"
)

@Composable
fun FreePlayScreen(
    onBack: () -> Unit,
    viewModel: FreePlayViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val continuousPitchLine by viewModel.continuousPitchLine.collectAsState()
    val density = LocalDensity.current
    val context = LocalContext.current

    KeepScreenOn()

    var frameTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            frameTime = System.currentTimeMillis()
            kotlinx.coroutines.delay(16L)
        }
    }

    val effectiveTime = viewModel.getEffectiveTime(frameTime)
    val midiRange = state.viewMaxMidi - state.viewMinMidi

    val barMs = state.beatsPerBar * (60000.0 / state.bpm)
    val loopStartMs = state.loopStartBar?.let { (state.sessionStartMs + it * barMs).toLong() }
    val loopEndMs   = state.loopEndBar?.let   { (state.sessionStartMs + it * barMs).toLong() }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.warnings.collect { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Long)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/midi")
    ) { uri: Uri? -> uri?.let { viewModel.exportToMidi(it, context) } }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.importFromMidi(it, context) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        // ── Panel row 1: I/O + all action buttons ─────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PANEL_BG)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Input source dropdown
            var showInputMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(
                    onClick = { showInputMenu = true },
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                    modifier = Modifier.height(PANEL_BTN_SIZE)
                ) {
                    Icon(
                        if (state.midiInputEnabled) Icons.Filled.MusicNote else Icons.Filled.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (state.midiInputEnabled) Color(0xFF7E57C2) else OVERLAY_ICON_TINT
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = if (state.midiInputEnabled) (state.midiInputDeviceName?.take(9) ?: "MIDI") else "Mic",
                        fontSize = 11.sp,
                        color = if (state.midiInputEnabled) Color(0xFF7E57C2) else OVERLAY_ICON_TINT,
                        maxLines = 1
                    )
                    Icon(Icons.Filled.ArrowDropDown, null, modifier = Modifier.size(14.dp), tint = OVERLAY_ICON_TINT)
                }
                DropdownMenu(expanded = showInputMenu, onDismissRequest = { showInputMenu = false }) {
                    DropdownMenuItem(
                        text = { Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Mic, null, Modifier.size(16.dp)); Text("Microphone") } },
                        onClick = { viewModel.setMidiInputEnabled(false); showInputMenu = false }
                    )
                    val inputDeviceName = state.midiInputDeviceName
                    if (inputDeviceName != null) {
                        DropdownMenuItem(
                            text = { Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.MusicNote, null, Modifier.size(16.dp), tint = Color(0xFF7E57C2)); Text(inputDeviceName) } },
                            onClick = { viewModel.setMidiInputEnabled(true); showInputMenu = false }
                        )
                    }
                }
            }

            // Output source dropdown
            var showOutputMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(
                    onClick = { showOutputMenu = true },
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                    modifier = Modifier.height(PANEL_BTN_SIZE)
                ) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (state.midiOutputEnabled) Color(0xFF7E57C2) else OVERLAY_ICON_TINT
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = if (state.midiOutputEnabled) (state.midiOutputDeviceName?.take(9) ?: "MIDI") else "Synth",
                        fontSize = 11.sp,
                        color = if (state.midiOutputEnabled) Color(0xFF7E57C2) else OVERLAY_ICON_TINT,
                        maxLines = 1
                    )
                    Icon(Icons.Filled.ArrowDropDown, null, modifier = Modifier.size(14.dp), tint = OVERLAY_ICON_TINT)
                }
                DropdownMenu(expanded = showOutputMenu, onDismissRequest = { showOutputMenu = false }) {
                    DropdownMenuItem(text = { Text("Internal synth") }, onClick = { viewModel.setMidiOutputEnabled(false); showOutputMenu = false })
                    val outputDeviceName = state.midiOutputDeviceName
                    if (outputDeviceName != null) {
                        DropdownMenuItem(
                            text = { Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.MusicNote, null, Modifier.size(16.dp), tint = Color(0xFF7E57C2)); Text(outputDeviceName) } },
                            onClick = { viewModel.setMidiOutputEnabled(true); showOutputMenu = false }
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Settings
            var showConfigMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(
                    onClick = { showConfigMenu = true },
                    modifier = Modifier.size(PANEL_BTN_SIZE).background(OVERLAY_BG, CircleShape)
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = OVERLAY_ICON_TINT, modifier = Modifier.size(17.dp))
                }
                DropdownMenu(expanded = showConfigMenu, onDismissRequest = { showConfigMenu = false }, offset = DpOffset((-160).dp, 0.dp)) {
                    // Snap
                    DropdownMenuItem(
                        text = { Text("Snap notes") },
                        trailingIcon = { Switch(checked = state.snapEnabled, onCheckedChange = { viewModel.toggleSnap() }, modifier = Modifier.height(20.dp)) },
                        onClick = { viewModel.toggleSnap() }
                    )
                    if (state.snapEnabled) {
                        val divIndex = NOTE_DIVISIONS.indexOf(state.minNoteDivision).takeIf { it >= 0 } ?: 2
                        DropdownMenuItem(text = { Text("Min duration: ${NOTE_DIVISION_LABELS[state.minNoteDivision] ?: "${state.minNoteDivision}th"}") }, onClick = {})
                        Slider(value = divIndex.toFloat(), onValueChange = { viewModel.setMinNoteDivision(NOTE_DIVISIONS[it.roundToInt()]) }, valueRange = 0f..4f, steps = 3, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    // Piano visibility
                    HorizontalDivider()
                    var showPianoVisMenu by remember { mutableStateOf(false) }
                    Box {
                        DropdownMenuItem(
                            text = { Text("Piano: ${PIANO_VISIBILITY_LABELS[state.pianoVisibility]}") },
                            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(16.dp)) },
                            onClick = { showPianoVisMenu = true }
                        )
                        DropdownMenu(expanded = showPianoVisMenu, onDismissRequest = { showPianoVisMenu = false }) {
                            PianoVisibility.entries.forEach { vis ->
                                DropdownMenuItem(
                                    text = { Text(PIANO_VISIBILITY_LABELS[vis] ?: vis.name, fontWeight = if (vis == state.pianoVisibility) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal) },
                                    onClick = { viewModel.setPianoVisibility(vis); showPianoVisMenu = false; showConfigMenu = false }
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.FileOpen, null, Modifier.size(18.dp)); Text("Import MIDI") } },
                        onClick = { importLauncher.launch(arrayOf("audio/midi", "audio/x-midi", "*/*")); showConfigMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Save, null, Modifier.size(18.dp)); Text("Export MIDI") } },
                        onClick = { exportLauncher.launch("session.mid"); showConfigMenu = false }
                    )
                }
            }

            // Voice selector
            val voiceColor = VOICE_COLORS[state.activeVoiceIndex % VOICE_COLORS.size]
            var showVoiceMenu by remember { mutableStateOf(false) }
            Box {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(PANEL_BTN_SIZE)
                        .background(voiceColor.copy(alpha = 0.3f), CircleShape)
                        .border(1.dp, voiceColor, CircleShape)
                        .pointerInput(Unit) { detectTapGestures { showVoiceMenu = true } }
                ) {
                    Text("V${state.activeVoiceIndex + 1}", fontSize = 10.sp, color = voiceColor)
                }
                DropdownMenu(expanded = showVoiceMenu, onDismissRequest = { showVoiceMenu = false }, offset = DpOffset((-100).dp, 0.dp)) {
                    for (v in 0 until state.voiceCount) {
                        val vColor = VOICE_COLORS[v % VOICE_COLORS.size]
                        DropdownMenuItem(
                            text = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(10.dp).background(vColor, CircleShape)); Text("Voice ${v + 1}", fontWeight = if (v == state.activeVoiceIndex) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal) } },
                            onClick = { viewModel.setActiveVoice(v); showVoiceMenu = false }
                        )
                    }
                    if (state.voiceCount < 8) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("+ Add voice") }, onClick = { viewModel.addVoice(); showVoiceMenu = false })
                    }
                }
            }

            // Record toggle
            IconButton(
                onClick = { viewModel.toggleRecording() },
                modifier = Modifier.size(PANEL_BTN_SIZE)
                    .background(if (state.isRecording) Color(0xFFEF5350).copy(alpha = 0.35f) else OVERLAY_BG, CircleShape)
                    .then(if (state.isRecording) Modifier.border(1.dp, Color(0xFFEF5350), CircleShape) else Modifier)
            ) {
                Icon(Icons.Filled.FiberManualRecord, null, modifier = Modifier.size(17.dp), tint = if (state.isRecording) Color(0xFFEF5350) else OVERLAY_ICON_TINT)
            }

            // Play/Pause
            if (state.playedNotes.isNotEmpty()) {
                IconButton(
                    onClick = { if (state.isReplaying) viewModel.stopReplay() else viewModel.replayNotes() },
                    modifier = Modifier.size(PANEL_BTN_SIZE)
                        .background(if (state.isReplaying) Color(0xFF26A69A).copy(alpha = 0.35f) else OVERLAY_BG, CircleShape)
                        .then(if (state.isReplaying) Modifier.border(1.dp, Color(0xFF26A69A), CircleShape) else Modifier)
                ) {
                    Icon(if (state.isReplaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, modifier = Modifier.size(17.dp), tint = if (state.isReplaying) Color(0xFF26A69A) else OVERLAY_ICON_TINT)
                }
            }

            // Undo
            if (state.undoStack.isNotEmpty()) {
                IconButton(
                    onClick = { viewModel.undo() },
                    modifier = Modifier.size(PANEL_BTN_SIZE).background(OVERLAY_BG, CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, null, modifier = Modifier.size(17.dp), tint = OVERLAY_ICON_TINT)
                }
            }

            // Scroll-to-present
            if (state.timeOffsetMs > 0L) {
                IconButton(
                    onClick = { viewModel.scrollToTop() },
                    modifier = Modifier.size(PANEL_BTN_SIZE).background(OVERLAY_BG, CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, null, modifier = Modifier.size(17.dp), tint = Color(0xFFFFF176))
                }
            }

            Spacer(Modifier.width(4.dp))

            // Loop controls
            TextButton(
                onClick = { viewModel.setLoopStart() },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                modifier = Modifier.height(PANEL_BTN_SIZE)
            ) {
                Text("S", fontSize = 10.sp, color = if (state.loopStartBar != null) Color(0xFFFFB74D) else OVERLAY_ICON_TINT)
            }
            TextButton(
                onClick = { viewModel.setLoopEnd() },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                modifier = Modifier.height(PANEL_BTN_SIZE)
            ) {
                Text("E", fontSize = 10.sp, color = if (state.loopEndBar != null) Color(0xFFFFB74D) else OVERLAY_ICON_TINT)
            }
            if (state.loopStartBar != null && state.loopEndBar != null) {
                IconButton(
                    onClick = { viewModel.toggleLoop() },
                    modifier = Modifier.size(PANEL_BTN_SIZE)
                        .then(if (state.isLooping) Modifier.border(1.dp, Color(0xFFFFB74D), CircleShape) else Modifier)
                ) {
                    Icon(Icons.Filled.Loop, null, modifier = Modifier.size(16.dp), tint = if (state.isLooping) Color(0xFFFFB74D) else OVERLAY_ICON_TINT)
                }
            }
        }

        // ── Panel row 2: BPM and beats per bar ────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xBB0D0D1A))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("BPM", fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f))
            IconButton(
                onClick = { viewModel.setBpm((state.bpm - 1f).coerceAtLeast(20f)) },
                modifier = Modifier.size(24.dp)
            ) { Text("−", fontSize = 12.sp, color = OVERLAY_ICON_TINT) }
            Text(
                text = state.bpm.roundToInt().toString(),
                fontSize = 13.sp,
                color = Color.White,
                modifier = Modifier.widthIn(min = 28.dp),
            )
            IconButton(
                onClick = { viewModel.setBpm((state.bpm + 1f).coerceAtMost(300f)) },
                modifier = Modifier.size(24.dp)
            ) { Text("+", fontSize = 12.sp, color = OVERLAY_ICON_TINT) }

            Spacer(Modifier.width(12.dp))

            Text("Beats/bar", fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f))
            IconButton(
                onClick = { viewModel.setBeatsPerBar((state.beatsPerBar - 1).coerceAtLeast(1)) },
                modifier = Modifier.size(24.dp)
            ) { Text("−", fontSize = 12.sp, color = OVERLAY_ICON_TINT) }
            Text(
                text = "${state.beatsPerBar}/4",
                fontSize = 13.sp,
                color = Color.White,
                modifier = Modifier.widthIn(min = 24.dp),
            )
            IconButton(
                onClick = { viewModel.setBeatsPerBar((state.beatsPerBar + 1).coerceAtMost(16)) },
                modifier = Modifier.size(24.dp)
            ) { Text("+", fontSize = 12.sp, color = OVERLAY_ICON_TINT) }
        }

        // ── Main canvas ───────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val canvasWidth = size.width.toFloat()
                        if (zoom != 1f) {
                            viewModel.onHorizontalZoom(zoom, centroid.x / canvasWidth)
                            viewModel.onVerticalZoom(zoom)
                        }
                        if (pan.x != 0f || pan.y != 0f) {
                            viewModel.onHorizontalScroll(pan.x * (midiRange / canvasWidth))
                            viewModel.onVerticalScroll((-pan.y * (1000f / (state.fallSpeedPxPerSec * density.density))).toLong())
                        }
                    }
                }
        ) {
            RequireMicPermission(skip = state.midiInputEnabled) {
                LaunchedEffect(Unit) {
                    viewModel.initSynthesizer(context)
                    viewModel.startListening()
                }
                DisposableEffect(Unit) { onDispose { viewModel.stopListening() } }

                SharedNoteCanvas(
                    mode = CanvasMode.FreePlay(
                        notes = state.playedNotes,
                        activeNote = state.activeNote,
                        currentTimeMs = effectiveTime,
                        timeOffsetMs = state.timeOffsetMs,
                        fallSpeedPxPerSec = state.fallSpeedPxPerSec,
                        pitchTrace = state.pitchTrace,
                        bpm = state.bpm,
                        beatsPerBar = state.beatsPerBar,
                        activeVoiceIndex = state.activeVoiceIndex,
                        loopStartMs = if (state.isLooping || state.loopStartBar != null) loopStartMs else null,
                        loopEndMs = if (state.isLooping || state.loopEndBar != null) loopEndMs else null,
                        selectedNoteId = state.selectedNoteId,
                        sessionStartMs = state.sessionStartMs,
                        pianoVisibility = state.pianoVisibility
                    ),
                    pianoAtBottom = false,
                    viewMinMidi = state.viewMinMidi,
                    viewMaxMidi = state.viewMaxMidi,
                    continuousPitchLine = continuousPitchLine,
                    rightPaddingPx = 0f,
                    modifier = Modifier.fillMaxSize()
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
