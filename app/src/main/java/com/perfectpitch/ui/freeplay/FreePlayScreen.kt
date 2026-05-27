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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Loop
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.perfectpitch.ui.components.CanvasMode
import com.perfectpitch.ui.components.PIANO_HEIGHT_DP
import com.perfectpitch.ui.components.RequireMicPermission
import com.perfectpitch.ui.components.SharedNoteCanvas
import com.perfectpitch.ui.components.VOICE_COLORS
import com.perfectpitch.ui.components.KeepScreenOn
import kotlin.math.roundToInt

private val BUTTON_AREA_WIDTH = 52.dp
private val OVERLAY_BG = Color(0xAA1A1A2E)
private val OVERLAY_ICON_TINT = Color.White.copy(alpha = 0.9f)
private val OVERLAY_BUTTON_SIZE = 40.dp

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
    val rightPaddingPx = with(density) { BUTTON_AREA_WIDTH.toPx() }

    // Note rects for edit mode hit testing.
    // Stored in a plain mutableStateOf so that pointerInput blocks can read the
    // CURRENT rects without including them in the restart key (they change every frame).
    val noteRectsState = remember { mutableStateOf<List<Pair<String, Rect>>>(emptyList()) }
    var dragNoteId by remember { mutableStateOf<String?>(null) }
    var dragHandleType by remember { mutableStateOf<String?>(null) } // "start" or "end"

    // Handle touch radius (generous to make selection easy)
    val handleRadiusPx = with(density) { 18.dp.toPx() }

    // Current bar (1-based), session-relative
    val barMs = state.beatsPerBar * (60000.0 / state.bpm)
    val currentBarNum = ((effectiveTime - state.sessionStartMs) / barMs).toInt()

    // Compute loop start/end in absolute ms (session base + bar offset)
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

    // Compute pxPerMs for gesture deltas
    val pxPerMs = state.fallSpeedPxPerSec * density.density / 1000f
    val midiRange = state.viewMaxMidi - state.viewMinMidi

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            // Transform gestures (only in NONE mode)
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
            // Tap gestures for edit mode (single=select, double=delete).
            // Key: only editMode — noteRectsState is read at tap time without restarting block.
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
            // Long-press drag for MOVE.
            // Key: only editMode — noteRectsState read at drag-start time.
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
                            // Right drag → higher MIDI note; down drag → note moves to older time
                            val deltaMidi = (dragAmount.x * midiPerPxLocal).roundToInt()
                            val deltaMs  = -(dragAmount.y / pxPerMs).toLong()
                            viewModel.moveNote(id, deltaMidi, deltaMs)
                        },
                        onDragEnd = {
                            dragNoteId?.let { viewModel.commitNoteEdit(it) }
                            dragNoteId = null
                        },
                        onDragCancel = {
                            dragNoteId = null
                        }
                    )
                }
            }
            // Handle drag for EXTEND start/end.
            // Key: editMode + selectedNoteId (changes infrequently, not every frame).
            .pointerInput(state.editMode, state.selectedNoteId) {
                if (state.editMode == NoteEditMode.EDIT && state.selectedNoteId != null) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val selId = state.selectedNoteId ?: return@detectDragGestures
                            val noteRect = noteRectsState.value
                                .firstOrNull { (id, _) -> id == selId }?.second
                            if (noteRect != null) {
                                // End handle: top edge of bar (most recent = close to piano)
                                // Start handle: bottom edge of bar (oldest = far from piano)
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
                            // Down drag = moving toward older time = reduce the ms value
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
                    editMode = state.editMode,
                    replayMode = state.replayMode
                ),
                pianoAtBottom = false,
                viewMinMidi = state.viewMinMidi,
                viewMaxMidi = state.viewMaxMidi,
                continuousPitchLine = continuousPitchLine,
                rightPaddingPx = rightPaddingPx,
                onNoteRects = { rects -> noteRectsState.value = rects },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Tempo / meter chip (top-left, just below piano)
        Surface(
            color = OVERLAY_BG,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 8.dp, top = PIANO_HEIGHT_DP.dp + 6.dp)
        ) {
            Text(
                text = "${state.bpm.roundToInt()} BPM  ${state.beatsPerBar}/4  Bar ${currentBarNum + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        // Right-side button column (below piano keys)
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = PIANO_HEIGHT_DP.dp + 8.dp, end = 6.dp),
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
                        text = { Text("Time progression") },
                        trailingIcon = {
                            Switch(
                                checked = state.timeProgressionEnabled,
                                onCheckedChange = { viewModel.toggleTimeProgression() },
                                modifier = Modifier.height(20.dp)
                            )
                        },
                        onClick = { viewModel.toggleTimeProgression() }
                    )
                    HorizontalDivider()
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
                }
            }

            // Edit mode toggle
            val isEditing = state.editMode == NoteEditMode.EDIT
            IconButton(
                onClick = { viewModel.setEditMode(if (isEditing) NoteEditMode.NONE else NoteEditMode.EDIT) },
                modifier = Modifier
                    .size(OVERLAY_BUTTON_SIZE)
                    .background(if (isEditing) Color(0xFF4FC3F7).copy(alpha = 0.4f) else OVERLAY_BG, CircleShape)
                    .then(if (isEditing) Modifier.border(1.dp, Color(0xFF4FC3F7), CircleShape) else Modifier)
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Edit mode",
                    tint = if (isEditing) Color(0xFF4FC3F7) else OVERLAY_ICON_TINT,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Voice selector buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.wrapContentWidth()
            ) {
                for (v in 0 until state.voiceCount) {
                    val voiceColor = VOICE_COLORS[v % VOICE_COLORS.size]
                    val isActive = v == state.activeVoiceIndex
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .background(
                                if (isActive) voiceColor.copy(alpha = 0.8f) else OVERLAY_BG,
                                CircleShape
                            )
                            .border(1.dp, voiceColor, CircleShape)
                            .pointerInput(v) {
                                detectTapGestures { viewModel.setActiveVoice(v) }
                            }
                    ) {
                        Text(
                            text = "${v + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isActive) Color.Black else voiceColor
                        )
                    }
                }
                // Add voice button
                if (state.voiceCount < 8) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .background(OVERLAY_BG, CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                            .pointerInput(Unit) {
                                detectTapGestures { viewModel.addVoice() }
                            }
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Add voice",
                            tint = OVERLAY_ICON_TINT,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Loop Start button
            IconButton(
                onClick = { viewModel.setLoopStart() },
                modifier = Modifier
                    .size(OVERLAY_BUTTON_SIZE)
                    .background(
                        if (state.loopStartBar != null) Color(0xFFFFB74D).copy(alpha = 0.3f) else OVERLAY_BG,
                        CircleShape
                    )
            ) {
                Text(
                    text = "S${currentBarNum + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.loopStartBar != null) Color(0xFFFFB74D) else OVERLAY_ICON_TINT
                )
            }

            // Loop End button
            IconButton(
                onClick = { viewModel.setLoopEnd() },
                modifier = Modifier
                    .size(OVERLAY_BUTTON_SIZE)
                    .background(
                        if (state.loopEndBar != null) Color(0xFFFFB74D).copy(alpha = 0.3f) else OVERLAY_BG,
                        CircleShape
                    )
            ) {
                Text(
                    text = "E${currentBarNum + 2}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.loopEndBar != null) Color(0xFFFFB74D) else OVERLAY_ICON_TINT
                )
            }

            // Loop toggle
            if (state.loopStartBar != null && state.loopEndBar != null) {
                IconButton(
                    onClick = { viewModel.toggleLoop() },
                    modifier = Modifier
                        .size(OVERLAY_BUTTON_SIZE)
                        .background(
                            if (state.isLooping) Color(0xFFFFB74D).copy(alpha = 0.4f) else OVERLAY_BG,
                            CircleShape
                        )
                        .then(if (state.isLooping) Modifier.border(1.dp, Color(0xFFFFB74D), CircleShape) else Modifier)
                ) {
                    Icon(
                        Icons.Filled.Loop,
                        contentDescription = "Toggle loop",
                        tint = if (state.isLooping) Color(0xFFFFB74D) else OVERLAY_ICON_TINT,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Undo button (only when stack non-empty)
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

            // MIDI input source chip
            if (state.midiAvailable) {
                IconButton(
                    onClick = { viewModel.toggleMidiInput() },
                    modifier = Modifier
                        .size(OVERLAY_BUTTON_SIZE)
                        .background(
                            if (state.midiInputEnabled) Color(0xFF7E57C2).copy(alpha = 0.4f) else OVERLAY_BG,
                            CircleShape
                        )
                        .then(
                            if (state.midiInputEnabled) Modifier.border(1.dp, Color(0xFF7E57C2), CircleShape)
                            else Modifier
                        )
                ) {
                    Icon(
                        if (state.midiInputEnabled) Icons.Filled.MusicNote else Icons.Filled.Mic,
                        contentDescription = if (state.midiInputEnabled) "MIDI input (tap for mic)" else "Mic input (tap for MIDI)",
                        tint = if (state.midiInputEnabled) Color(0xFF7E57C2) else OVERLAY_ICON_TINT,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // MIDI output / replay button
            if (state.midiAvailable || state.playedNotes.isNotEmpty()) {
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
                        .then(
                            if (state.isReplaying) Modifier.border(1.dp, Color(0xFF26A69A), CircleShape)
                            else Modifier
                        )
                ) {
                    Icon(
                        if (state.isReplaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isReplaying) "Stop replay" else "Replay notes",
                        tint = if (state.isReplaying) Color(0xFF26A69A) else OVERLAY_ICON_TINT,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // MIDI output toggle (only when device available)
            if (state.midiAvailable) {
                IconButton(
                    onClick = { viewModel.toggleMidiOutput() },
                    modifier = Modifier
                        .size(OVERLAY_BUTTON_SIZE)
                        .background(
                            if (state.midiOutputEnabled) Color(0xFF7E57C2).copy(alpha = 0.4f) else OVERLAY_BG,
                            CircleShape
                        )
                        .then(
                            if (state.midiOutputEnabled) Modifier.border(1.dp, Color(0xFF7E57C2), CircleShape)
                            else Modifier
                        )
                ) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = if (state.midiOutputEnabled) "MIDI output on" else "MIDI output off",
                        tint = if (state.midiOutputEnabled) Color(0xFF7E57C2) else OVERLAY_ICON_TINT,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Import MIDI
            IconButton(
                onClick = {
                    importLauncher.launch(arrayOf("audio/midi", "audio/x-midi", "*/*"))
                },
                modifier = Modifier
                    .size(OVERLAY_BUTTON_SIZE)
                    .background(OVERLAY_BG, CircleShape)
            ) {
                Icon(
                    Icons.Filled.FileOpen,
                    contentDescription = "Import MIDI",
                    tint = OVERLAY_ICON_TINT,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Export MIDI
            IconButton(
                onClick = {
                    exportLauncher.launch("session.mid")
                },
                modifier = Modifier
                    .size(OVERLAY_BUTTON_SIZE)
                    .background(OVERLAY_BG, CircleShape)
            ) {
                Icon(
                    Icons.Filled.Save,
                    contentDescription = "Export MIDI",
                    tint = OVERLAY_ICON_TINT,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Scroll-to-top button (only when scrolled back)
            if (state.timeOffsetMs > 0L) {
                IconButton(
                    onClick = { viewModel.scrollToTop() },
                    modifier = Modifier
                        .size(OVERLAY_BUTTON_SIZE)
                        .background(OVERLAY_BG, CircleShape)
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = "Scroll to top",
                        tint = OVERLAY_ICON_TINT,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Auto-fit button
            IconButton(
                onClick = { viewModel.autoFit() },
                modifier = Modifier
                    .size(OVERLAY_BUTTON_SIZE)
                    .background(OVERLAY_BG, CircleShape)
            ) {
                Icon(
                    Icons.Filled.FitScreen,
                    contentDescription = "Fit to content",
                    tint = OVERLAY_ICON_TINT,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
