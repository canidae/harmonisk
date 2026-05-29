package com.perfectpitch.ui.freeplay

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.perfectpitch.ui.components.CanvasMode
import com.perfectpitch.ui.components.KeepScreenOn
import com.perfectpitch.ui.components.RequireMicPermission
import com.perfectpitch.ui.components.SharedNoteCanvas
import com.perfectpitch.ui.components.VOICE_COLORS
import kotlin.math.abs
import kotlin.math.roundToInt

private val OVERLAY_BG = Color(0xAA1A1A2E)
private val OVERLAY_ICON_TINT = Color.White.copy(alpha = 0.9f)
private val PANEL_BG = Color(0xDD0D0D1A)
private val PANEL_ROW2_BG = Color(0xBB0D0D1A)
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
    val density = LocalDensity.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

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
    val pxPerMs = state.fallSpeedPxPerSec * density.density / 1000f

    val barMs = state.beatsPerBar * (60000.0 / state.bpm)
    val loopStartMs = state.loopStartBar?.let { (it * barMs).toLong() }
    val loopEndMs   = state.loopEndBar?.let   { (it * barMs).toLong() }

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

    // Edit mode interaction state
    val noteRectsState = remember { mutableStateOf<List<Pair<String, Rect>>>(emptyList()) }
    var dragNoteId by remember { mutableStateOf<String?>(null) }
    var dragHandleType by remember { mutableStateOf<String?>(null) }
    val handleRadiusPx = with(density) { 18.dp.toPx() }

    // ── Dropdown state (defined here so lambdas below can capture them) ──
    var showInputMenu by remember { mutableStateOf(false) }
    var showOutputMenu by remember { mutableStateOf(false) }
    var showConfigMenu by remember { mutableStateOf(false) }
    var showVoiceMenu by remember { mutableStateOf(false) }
    var showBeatsMenu by remember { mutableStateOf(false) }
    var showDivisionMenu by remember { mutableStateOf(false) }

    // ── Panel 1 content: I/O · Settings · Play · Record · Edit · ScrollTop ──
    val panel1Content: @Composable RowScope.() -> Unit = {
        // Input source dropdown
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
        Box {
            val outputLabel = when (state.outputDevice) {
                OutputDevice.MIDI -> state.midiOutputDeviceName?.take(9) ?: "MIDI"
                OutputDevice.SYNTH -> "Synth"
                OutputDevice.NONE -> "None"
            }
            val outputTint = if (state.outputDevice == OutputDevice.MIDI) Color(0xFF7E57C2) else OVERLAY_ICON_TINT
            TextButton(
                onClick = { showOutputMenu = true },
                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                modifier = Modifier.height(PANEL_BTN_SIZE)
            ) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = outputTint
                )
                Spacer(Modifier.width(3.dp))
                Text(text = outputLabel, fontSize = 11.sp, color = outputTint, maxLines = 1)
                Icon(Icons.Filled.ArrowDropDown, null, modifier = Modifier.size(14.dp), tint = OVERLAY_ICON_TINT)
            }
            DropdownMenu(expanded = showOutputMenu, onDismissRequest = { showOutputMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Internal synth", fontWeight = if (state.outputDevice == OutputDevice.SYNTH) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { viewModel.setOutputDevice(OutputDevice.SYNTH); showOutputMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("None (silent)", fontWeight = if (state.outputDevice == OutputDevice.NONE) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { viewModel.setOutputDevice(OutputDevice.NONE); showOutputMenu = false }
                )
                val outputDeviceName = state.midiOutputDeviceName
                if (outputDeviceName != null) {
                    DropdownMenuItem(
                        text = { Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.MusicNote, null, Modifier.size(16.dp), tint = Color(0xFF7E57C2)); Text(outputDeviceName, fontWeight = if (state.outputDevice == OutputDevice.MIDI) FontWeight.Bold else FontWeight.Normal) } },
                        onClick = { viewModel.setOutputDevice(OutputDevice.MIDI); showOutputMenu = false }
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Settings
        Box {
            IconButton(
                onClick = { showConfigMenu = true },
                modifier = Modifier.size(PANEL_BTN_SIZE).background(OVERLAY_BG, CircleShape)
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = OVERLAY_ICON_TINT, modifier = Modifier.size(17.dp))
            }
            DropdownMenu(expanded = showConfigMenu, onDismissRequest = { showConfigMenu = false }, offset = DpOffset((-160).dp, 0.dp)) {
                DropdownMenuItem(
                    text = { Text("Snap notes") },
                    trailingIcon = { Switch(checked = state.snapEnabled, onCheckedChange = { viewModel.toggleSnap() }, modifier = Modifier.height(20.dp)) },
                    onClick = { viewModel.toggleSnap() }
                )
                if (state.snapEnabled) {
                    val divIndex = NOTE_DIVISIONS.indexOf(state.minNoteDivision).takeIf { it >= 0 } ?: 2
                    val reversedIndex = 4 - divIndex
                    DropdownMenuItem(text = { Text("Min note duration: ${NOTE_DIVISION_LABELS[state.minNoteDivision] ?: "${state.minNoteDivision}th"}") }, onClick = {})
                    Slider(value = reversedIndex.toFloat(), onValueChange = { viewModel.setMinNoteDivision(NOTE_DIVISIONS[4 - it.roundToInt()]) }, valueRange = 0f..4f, steps = 3, modifier = Modifier.padding(horizontal = 16.dp))
                }
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
                                text = { Text(PIANO_VISIBILITY_LABELS[vis] ?: vis.name, fontWeight = if (vis == state.pianoVisibility) FontWeight.Bold else FontWeight.Normal) },
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

        // Play / Stop toggle
        IconButton(
            onClick = { viewModel.togglePlaying() },
            modifier = Modifier.size(PANEL_BTN_SIZE)
                .background(if (state.isPlaying) Color(0xFF66BB6A).copy(alpha = 0.35f) else OVERLAY_BG, CircleShape)
                .then(if (state.isPlaying) Modifier.border(1.dp, Color(0xFF66BB6A), CircleShape) else Modifier)
        ) {
            Icon(
                if (state.isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (state.isPlaying) "Stop" else "Play",
                modifier = Modifier.size(17.dp),
                tint = if (state.isPlaying) Color(0xFF66BB6A) else OVERLAY_ICON_TINT
            )
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

        // Edit mode toggle
        val isEditing = state.editMode == NoteEditMode.EDIT
        IconButton(
            onClick = { viewModel.setEditMode(if (isEditing) NoteEditMode.NONE else NoteEditMode.EDIT) },
            modifier = Modifier.size(PANEL_BTN_SIZE)
                .background(if (isEditing) Color(0xFFFFB74D).copy(alpha = 0.35f) else OVERLAY_BG, CircleShape)
                .then(if (isEditing) Modifier.border(1.dp, Color(0xFFFFB74D), CircleShape) else Modifier)
        ) {
            Icon(Icons.Filled.Edit, null, modifier = Modifier.size(17.dp), tint = if (isEditing) Color(0xFFFFB74D) else OVERLAY_ICON_TINT)
        }

    }

    // ── Panel 2 content: BPM · Meter · Voice · Loop ──────────────────────
    val panel2Content: @Composable RowScope.() -> Unit = {
        // BPM drag state
        var bpmDragAccumPx by remember { mutableFloatStateOf(0f) }
        var bpmDragging by remember { mutableStateOf(false) }

        // While dragging, continuously apply BPM changes proportional to accumulated offset
        LaunchedEffect(bpmDragging) {
            if (!bpmDragging) return@LaunchedEffect
            while (true) {
                val off = bpmDragAccumPx
                if (abs(off) > 1f) {
                    val maxDistPx = with(density) { 120.dp.toPx() }
                    val fraction = (off / maxDistPx).coerceIn(-1f, 1f)
                    val deltaPerFrame = fraction * 10f * 16f / 1000f  // 10 BPM/sec at ~60fps
                    viewModel.setBpm(viewModel.state.value.bpm + deltaPerFrame)
                }
                kotlinx.coroutines.delay(16L)
            }
        }

        // BPM label + value — draggable vertically
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    bpmDragAccumPx = 0f
                    bpmDragging = true
                    val startY = down.position.y
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            bpmDragAccumPx = startY - change.position.y
                            change.consume()
                        }
                    } finally {
                        bpmDragging = false
                        bpmDragAccumPx = 0f
                    }
                }
            }
        ) {
            Text(
                "BPM",
                fontSize = 10.sp,
                color = Color.White.copy(alpha = if (bpmDragging) 0.9f else 0.5f)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = state.bpm.roundToInt().toString(),
                fontSize = 13.sp,
                color = if (bpmDragging) Color(0xFF7E57C2) else Color.White,
                modifier = Modifier.widthIn(min = 28.dp)
            )
        }

        Spacer(Modifier.width(8.dp))

        // Meter (time signature) — two separate dropdowns
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Meter", fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f))
            Spacer(Modifier.width(4.dp))

            // Beats (numerator) dropdown
            Box {
                Text(
                    text = state.beatsPerBar.toString(),
                    fontSize = 13.sp,
                    color = Color.White,
                    modifier = Modifier
                        .pointerInput(Unit) { detectTapGestures { showBeatsMenu = true } }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
                DropdownMenu(expanded = showBeatsMenu, onDismissRequest = { showBeatsMenu = false }) {
                    (2..12).forEach { beats ->
                        DropdownMenuItem(
                            text = { Text(beats.toString(), fontWeight = if (beats == state.beatsPerBar) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { viewModel.setBeatsPerBar(beats); showBeatsMenu = false }
                        )
                    }
                }
            }

            Text("/", fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f))

            // Division (denominator) dropdown
            Box {
                Text(
                    text = state.barDivision.toString(),
                    fontSize = 13.sp,
                    color = Color.White,
                    modifier = Modifier
                        .pointerInput(Unit) { detectTapGestures { showDivisionMenu = true } }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
                DropdownMenu(expanded = showDivisionMenu, onDismissRequest = { showDivisionMenu = false }) {
                    listOf(2, 4, 8, 16, 32).forEach { div ->
                        DropdownMenuItem(
                            text = { Text(div.toString(), fontWeight = if (div == state.barDivision) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { viewModel.setBarDivision(div); showDivisionMenu = false }
                        )
                    }
                }
            }
        }

        // Voice selector
        val voiceColor = VOICE_COLORS[state.activeVoiceIndex % VOICE_COLORS.size]
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
            DropdownMenu(expanded = showVoiceMenu, onDismissRequest = { showVoiceMenu = false }, offset = DpOffset((-80).dp, 0.dp)) {
                for (v in 0 until state.voiceCount) {
                    val vColor = VOICE_COLORS[v % VOICE_COLORS.size]
                    DropdownMenuItem(
                        text = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(10.dp).background(vColor, CircleShape)); Text("Voice ${v + 1}", fontWeight = if (v == state.activeVoiceIndex) FontWeight.Bold else FontWeight.Normal) } },
                        onClick = { viewModel.setActiveVoice(v); showVoiceMenu = false }
                    )
                }
                if (state.voiceCount < 8) {
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("+ Add voice") }, onClick = { viewModel.addVoice(); showVoiceMenu = false })
                }
            }
        }

        Spacer(Modifier.width(8.dp))

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

    // ── Main layout ───────────────────────────────────────────────────────
    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        if (isLandscape) {
            // In landscape: both panels in a single horizontal bar to save vertical space
            Row(
                modifier = Modifier.fillMaxWidth().background(PANEL_BG).padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) { panel1Content() }
                VerticalDivider(
                    modifier = Modifier.height(PANEL_BTN_SIZE).padding(horizontal = 4.dp),
                    color = Color.White.copy(alpha = 0.15f)
                )
                Row(
                    modifier = Modifier.wrapContentWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) { panel2Content() }
            }
        } else {
            // Portrait: two stacked rows
            Row(
                modifier = Modifier.fillMaxWidth().background(PANEL_BG).padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) { panel1Content() }
            Row(
                modifier = Modifier.fillMaxWidth().background(PANEL_ROW2_BG).padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) { panel2Content() }
        }

        // ── Main canvas ────────────────────────────────────────────────────
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
                .pointerInput(state.editMode) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            if (state.editMode == NoteEditMode.EDIT) {
                                noteRectsState.value.firstOrNull { (_, r) -> r.contains(offset) }?.let { (id, _) -> viewModel.deleteNote(id) }
                            }
                        },
                        onTap = { offset ->
                            if (state.editMode == NoteEditMode.EDIT) {
                                viewModel.selectNote(noteRectsState.value.firstOrNull { (_, r) -> r.contains(offset) }?.first)
                            } else {
                                viewModel.togglePlaying()
                            }
                        }
                    )
                }
                .pointerInput(state.editMode) {
                    if (state.editMode == NoteEditMode.EDIT) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                dragNoteId = noteRectsState.value.firstOrNull { (_, r) -> r.contains(offset) }?.first
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val id = dragNoteId ?: return@detectDragGesturesAfterLongPress
                                viewModel.moveNote(
                                    id,
                                    (dragAmount.x * (midiRange / size.width.toFloat())).roundToInt(),
                                    -(dragAmount.y / pxPerMs).toLong()
                                )
                            },
                            onDragEnd = { dragNoteId?.let { viewModel.commitNoteEdit(it) }; dragNoteId = null },
                            onDragCancel = { dragNoteId = null }
                        )
                    }
                }
                .pointerInput(state.editMode, state.selectedNoteId) {
                    if (state.editMode == NoteEditMode.EDIT && state.selectedNoteId != null) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val rect = noteRectsState.value.firstOrNull { (id, _) -> id == state.selectedNoteId }?.second
                                if (rect != null) {
                                    dragHandleType = when {
                                        abs(offset.y - rect.top) <= handleRadiusPx * 2 -> "end"
                                        abs(offset.y - rect.bottom) <= handleRadiusPx * 2 -> "start"
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
                            onDragEnd = { dragHandleType?.let { state.selectedNoteId?.let { id -> viewModel.commitNoteEdit(id) } }; dragHandleType = null },
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
                DisposableEffect(Unit) { onDispose { viewModel.stopListening() } }

                SharedNoteCanvas(
                    mode = CanvasMode.FreePlay(
                        notes = state.playedNotes,
                        activeNote = state.activeNote,
                        songPositionMs = effectiveTime,
                        fallSpeedPxPerSec = state.fallSpeedPxPerSec,
                        bpm = state.bpm,
                        beatsPerBar = state.beatsPerBar,
                        activeVoiceIndex = state.activeVoiceIndex,
                        loopStartMs = if (state.isLooping || state.loopStartBar != null) loopStartMs else null,
                        loopEndMs = if (state.isLooping || state.loopEndBar != null) loopEndMs else null,
                        selectedNoteId = state.selectedNoteId,
                        isEditMode = state.editMode == NoteEditMode.EDIT,
                        pianoVisibility = state.pianoVisibility
                    ),
                    pianoAtBottom = false,
                    viewMinMidi = state.viewMinMidi,
                    viewMaxMidi = state.viewMaxMidi,
                    rightPaddingPx = 0f,
                    onNoteRects = { rects -> noteRectsState.value = rects },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Play overlay: shown when paused and not in edit mode
            if (!state.isPlaying && state.editMode == NoteEditMode.NONE) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Tap to play",
                        modifier = Modifier.size(72.dp),
                        tint = Color.White.copy(alpha = 0.3f)
                    )
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
