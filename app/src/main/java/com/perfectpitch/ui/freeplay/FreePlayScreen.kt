package com.perfectpitch.ui.freeplay

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.ui.geometry.Rect
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
private val OVERLAY_BUTTON_SIZE = 40.dp
private val PANEL_BG = Color(0xDD0D0D1A)

private val NOTE_DIVISIONS = listOf(4, 8, 16, 32, 64)
private val NOTE_DIVISION_LABELS = mapOf(4 to "♩ Quarter", 8 to "♪ 8th", 16 to "♬ 16th", 32 to "32nd", 64 to "64th")

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

    // Animation frame counter
    var frameTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            frameTime = System.currentTimeMillis()
            kotlinx.coroutines.delay(16L)
        }
    }

    val effectiveTime = viewModel.getEffectiveTime(frameTime)

    // Note rects for edit mode hit testing.
    val noteRectsState = remember { mutableStateOf<List<Pair<String, Rect>>>(emptyList()) }
    var dragNoteId by remember { mutableStateOf<String?>(null) }
    var dragHandleType by remember { mutableStateOf<String?>(null) }

    val handleRadiusPx = with(density) { 18.dp.toPx() }
    val pxPerMs = state.fallSpeedPxPerSec * density.density / 1000f
    val midiRange = state.viewMaxMidi - state.viewMinMidi

    val barMs = state.beatsPerBar * (60000.0 / state.bpm)
    val currentBarNum = ((effectiveTime - state.sessionStartMs) / barMs).toInt()

    val loopStartMs = state.loopStartBar?.let { (state.sessionStartMs + it * barMs).toLong() }
    val loopEndMs   = state.loopEndBar?.let   { (state.sessionStartMs + it * barMs).toLong() }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.warnings.collect { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Long)
        }
    }

    // File pickers
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/midi")
    ) { uri: Uri? ->
        uri?.let { viewModel.exportToMidi(it, context) }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importFromMidi(it, context) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        // ── Top panel ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PANEL_BG)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Input source dropdown
            var showInputMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(
                    onClick = { showInputMenu = true },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(
                        if (state.midiInputEnabled) Icons.Filled.MusicNote else Icons.Filled.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (state.midiInputEnabled) Color(0xFF7E57C2) else OVERLAY_ICON_TINT
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = if (state.midiInputEnabled)
                            (state.midiInputDeviceName?.take(10) ?: "MIDI")
                        else "Mic",
                        fontSize = 12.sp,
                        color = if (state.midiInputEnabled) Color(0xFF7E57C2) else OVERLAY_ICON_TINT,
                        maxLines = 1
                    )
                    Icon(Icons.Filled.ArrowDropDown, null, modifier = Modifier.size(16.dp), tint = OVERLAY_ICON_TINT)
                }
                DropdownMenu(expanded = showInputMenu, onDismissRequest = { showInputMenu = false }) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Filled.Mic, null, modifier = Modifier.size(16.dp))
                                Text("Microphone")
                            }
                        },
                        onClick = { viewModel.setMidiInputEnabled(false); showInputMenu = false }
                    )
                    val inputDeviceName = state.midiInputDeviceName
                    if (inputDeviceName != null) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Filled.MusicNote, null, modifier = Modifier.size(16.dp), tint = Color(0xFF7E57C2))
                                    Text(inputDeviceName)
                                }
                            },
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
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (state.midiOutputEnabled) Color(0xFF7E57C2) else OVERLAY_ICON_TINT
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = if (state.midiOutputEnabled)
                            (state.midiOutputDeviceName?.take(10) ?: "MIDI")
                        else "Synth",
                        fontSize = 12.sp,
                        color = if (state.midiOutputEnabled) Color(0xFF7E57C2) else OVERLAY_ICON_TINT,
                        maxLines = 1
                    )
                    Icon(Icons.Filled.ArrowDropDown, null, modifier = Modifier.size(16.dp), tint = OVERLAY_ICON_TINT)
                }
                DropdownMenu(expanded = showOutputMenu, onDismissRequest = { showOutputMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Internal synth") },
                        onClick = { viewModel.setMidiOutputEnabled(false); showOutputMenu = false }
                    )
                    val outputDeviceName = state.midiOutputDeviceName
                    if (outputDeviceName != null) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Filled.MusicNote, null, modifier = Modifier.size(16.dp), tint = Color(0xFF7E57C2))
                                    Text(outputDeviceName)
                                }
                            },
                            onClick = { viewModel.setMidiOutputEnabled(true); showOutputMenu = false }
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Loop controls
            TextButton(
                onClick = { viewModel.setLoopStart() },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text(
                    "S${currentBarNum + 1}",
                    fontSize = 12.sp,
                    color = if (state.loopStartBar != null) Color(0xFFFFB74D) else OVERLAY_ICON_TINT
                )
            }
            TextButton(
                onClick = { viewModel.setLoopEnd() },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text(
                    "E${currentBarNum + 2}",
                    fontSize = 12.sp,
                    color = if (state.loopEndBar != null) Color(0xFFFFB74D) else OVERLAY_ICON_TINT
                )
            }
            if (state.loopStartBar != null && state.loopEndBar != null) {
                IconButton(
                    onClick = { viewModel.toggleLoop() },
                    modifier = Modifier
                        .size(36.dp)
                        .then(if (state.isLooping) Modifier.border(1.dp, Color(0xFFFFB74D), CircleShape) else Modifier)
                ) {
                    Icon(
                        Icons.Filled.Loop,
                        contentDescription = "Toggle loop",
                        tint = if (state.isLooping) Color(0xFFFFB74D) else OVERLAY_ICON_TINT,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // ── Main content (canvas + right buttons) ─────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // Transform gestures (scroll/zoom)
                .pointerInput(state.editMode) {
                    if (state.editMode == NoteEditMode.NONE) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val canvasWidth = size.width.toFloat()
                            if (zoom != 1f) {
                                val focusFractionX = centroid.x / canvasWidth
                                viewModel.onHorizontalZoom(zoom, focusFractionX)
                                viewModel.onVerticalZoom(zoom)
                            }
                            if (pan.x != 0f || pan.y != 0f) {
                                val midiPerPxLocal = midiRange / canvasWidth
                                viewModel.onHorizontalScroll(pan.x * midiPerPxLocal)
                                val msPerPx = 1000f / (state.fallSpeedPxPerSec * density.density)
                                viewModel.onVerticalScroll((-pan.y * msPerPx).toLong())
                            }
                        }
                    }
                }
                .pointerInput(state.editMode) {
                    if (state.editMode == NoteEditMode.EDIT) {
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                val hit = noteRectsState.value.firstOrNull { (_, rect) -> rect.contains(offset) }
                                hit?.let { (id, _) -> viewModel.deleteNote(id) }
                            },
                            onTap = { offset ->
                                val hit = noteRectsState.value.firstOrNull { (_, rect) -> rect.contains(offset) }
                                viewModel.selectNote(hit?.first)
                            }
                        )
                    }
                }
                .pointerInput(state.editMode) {
                    if (state.editMode == NoteEditMode.EDIT) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                dragNoteId = noteRectsState.value
                                    .firstOrNull { (_, rect) -> rect.contains(offset) }?.first
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val id = dragNoteId ?: return@detectDragGesturesAfterLongPress
                                val canvasWidth = size.width.toFloat()
                                val midiPerPxLocal = midiRange / canvasWidth
                                val deltaMidi = (dragAmount.x * midiPerPxLocal).roundToInt()
                                val deltaMs  = -(dragAmount.y / pxPerMs).toLong()
                                viewModel.moveNote(id, deltaMidi, deltaMs)
                            },
                            onDragEnd = {
                                dragNoteId?.let { viewModel.commitNoteEdit(it) }
                                dragNoteId = null
                            },
                            onDragCancel = { dragNoteId = null }
                        )
                    }
                }
                .pointerInput(state.editMode, state.selectedNoteId) {
                    if (state.editMode == NoteEditMode.EDIT && state.selectedNoteId != null) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val selId = state.selectedNoteId ?: return@detectDragGestures
                                val noteRect = noteRectsState.value
                                    .firstOrNull { (id, _) -> id == selId }?.second
                                if (noteRect != null) {
                                    val endDist   = kotlin.math.abs(offset.y - noteRect.top)
                                    val startDist = kotlin.math.abs(offset.y - noteRect.bottom)
                                    dragHandleType = when {
                                        endDist   <= handleRadiusPx * 2 -> "end"
                                        startDist <= handleRadiusPx * 2 -> "start"
                                        else -> null
                                    }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val handType = dragHandleType ?: return@detectDragGestures
                                val selId = state.selectedNoteId ?: return@detectDragGestures
                                val deltaMs = -(dragAmount.y / pxPerMs).toLong()
                                when (handType) {
                                    "end"   -> viewModel.extendNoteEnd(selId, deltaMs)
                                    "start" -> viewModel.extendNoteStart(selId, deltaMs)
                                }
                            },
                            onDragEnd = {
                                dragHandleType?.let {
                                    state.selectedNoteId?.let { id -> viewModel.commitNoteEdit(id) }
                                }
                                dragHandleType = null
                            },
                            onDragCancel = { dragHandleType = null }
                        )
                    }
                }
        ) {
            RequireMicPermission(skip = state.midiInputEnabled) {
                LaunchedEffect(Unit) {
                    viewModel.initSynthesizer(context)
                    viewModel.startListening()
                }
                DisposableEffect(Unit) {
                    onDispose { viewModel.stopListening() }
                }

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
                        editMode = state.editMode
                    ),
                    pianoAtBottom = false,
                    viewMinMidi = state.viewMinMidi,
                    viewMaxMidi = state.viewMaxMidi,
                    continuousPitchLine = continuousPitchLine,
                    rightPaddingPx = 0f,
                    onNoteRects = { rects -> noteRectsState.value = rects },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // BPM / bar info chip (top-left)
            Surface(
                color = OVERLAY_BG,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = 8.dp)
            ) {
                Text(
                    text = "${state.bpm.roundToInt()} BPM  ${state.beatsPerBar}/4  Bar ${currentBarNum + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // ── Right-side button column ───────────────────────────────────
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Settings / config dropdown
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
                            text = { Text("Tempo: ${state.bpm.roundToInt()} BPM") },
                            onClick = {}
                        )
                        Slider(
                            value = state.bpm,
                            onValueChange = { viewModel.setBpm(it) },
                            valueRange = 20f..300f,
                            steps = 279,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        DropdownMenuItem(
                            text = { Text("Beats per bar: ${state.beatsPerBar}") },
                            onClick = {}
                        )
                        Slider(
                            value = state.beatsPerBar.toFloat(),
                            onValueChange = { viewModel.setBeatsPerBar(it.roundToInt()) },
                            valueRange = 1f..16f,
                            steps = 14,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Snap notes") },
                            trailingIcon = {
                                Switch(
                                    checked = state.snapEnabled,
                                    onCheckedChange = { viewModel.toggleSnap() },
                                    modifier = Modifier.height(20.dp)
                                )
                            },
                            onClick = { viewModel.toggleSnap() }
                        )
                        if (state.snapEnabled) {
                            val divIndex = NOTE_DIVISIONS.indexOf(state.minNoteDivision).takeIf { it >= 0 } ?: 2
                            DropdownMenuItem(
                                text = { Text("Min duration: ${NOTE_DIVISION_LABELS[state.minNoteDivision] ?: "${state.minNoteDivision}th"}") },
                                onClick = {}
                            )
                            Slider(
                                value = divIndex.toFloat(),
                                onValueChange = { viewModel.setMinNoteDivision(NOTE_DIVISIONS[it.roundToInt()]) },
                                valueRange = 0f..4f,
                                steps = 3,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Filled.FileOpen, null, modifier = Modifier.size(18.dp))
                                    Text("Import MIDI")
                                }
                            },
                            onClick = {
                                importLauncher.launch(arrayOf("audio/midi", "audio/x-midi", "*/*"))
                                showConfigMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Filled.Save, null, modifier = Modifier.size(18.dp))
                                    Text("Export MIDI")
                                }
                            },
                            onClick = {
                                exportLauncher.launch("session.mid")
                                showConfigMenu = false
                            }
                        )
                    }
                }

                // Voice selector dropdown
                val voiceColor = VOICE_COLORS[state.activeVoiceIndex % VOICE_COLORS.size]
                var showVoiceMenu by remember { mutableStateOf(false) }
                Box {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(OVERLAY_BUTTON_SIZE)
                            .background(voiceColor.copy(alpha = 0.35f), CircleShape)
                            .border(1.dp, voiceColor, CircleShape)
                            .pointerInput(Unit) { detectTapGestures { showVoiceMenu = true } }
                    ) {
                        Text(
                            "V${state.activeVoiceIndex + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = voiceColor
                        )
                    }
                    DropdownMenu(
                        expanded = showVoiceMenu,
                        onDismissRequest = { showVoiceMenu = false },
                        offset = DpOffset((-120).dp, 0.dp)
                    ) {
                        for (v in 0 until state.voiceCount) {
                            val vColor = VOICE_COLORS[v % VOICE_COLORS.size]
                            val isActive = v == state.activeVoiceIndex
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(
                                            Modifier
                                                .size(12.dp)
                                                .background(vColor, CircleShape)
                                        )
                                        Text("Voice ${v + 1}", fontWeight = if (isActive) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal)
                                    }
                                },
                                onClick = { viewModel.setActiveVoice(v); showVoiceMenu = false }
                            )
                        }
                        if (state.voiceCount < 8) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("+ Add voice") },
                                onClick = { viewModel.addVoice(); showVoiceMenu = false }
                            )
                        }
                    }
                }

                // Record toggle
                IconButton(
                    onClick = { viewModel.toggleRecording() },
                    modifier = Modifier
                        .size(OVERLAY_BUTTON_SIZE)
                        .background(
                            if (state.isRecording) Color(0xFFEF5350).copy(alpha = 0.4f) else OVERLAY_BG,
                            CircleShape
                        )
                        .then(if (state.isRecording) Modifier.border(1.dp, Color(0xFFEF5350), CircleShape) else Modifier)
                ) {
                    Icon(
                        Icons.Filled.FiberManualRecord,
                        contentDescription = if (state.isRecording) "Recording" else "Not recording",
                        tint = if (state.isRecording) Color(0xFFEF5350) else OVERLAY_ICON_TINT,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Play/Pause button
                if (state.playedNotes.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            if (state.isReplaying) viewModel.stopReplay() else viewModel.replayNotes()
                        },
                        modifier = Modifier
                            .size(OVERLAY_BUTTON_SIZE)
                            .background(
                                if (state.isReplaying) Color(0xFF26A69A).copy(alpha = 0.4f) else OVERLAY_BG,
                                CircleShape
                            )
                            .then(if (state.isReplaying) Modifier.border(1.dp, Color(0xFF26A69A), CircleShape) else Modifier)
                    ) {
                        Icon(
                            if (state.isReplaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isReplaying) "Pause" else "Play",
                            tint = if (state.isReplaying) Color(0xFF26A69A) else OVERLAY_ICON_TINT,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Undo button
                if (state.undoStack.isNotEmpty()) {
                    IconButton(
                        onClick = { viewModel.undo() },
                        modifier = Modifier
                            .size(OVERLAY_BUTTON_SIZE)
                            .background(OVERLAY_BG, CircleShape)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo",
                            tint = OVERLAY_ICON_TINT,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Scroll-to-present button
                if (state.timeOffsetMs > 0L) {
                    IconButton(
                        onClick = { viewModel.scrollToTop() },
                        modifier = Modifier
                            .size(OVERLAY_BUTTON_SIZE)
                            .background(OVERLAY_BG, CircleShape)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Back to present",
                            tint = OVERLAY_ICON_TINT,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
