package net.exent.perfectpitch.ui.freeplay

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import net.exent.perfectpitch.ui.components.CanvasMode
import net.exent.perfectpitch.ui.components.KeepScreenOn
import net.exent.perfectpitch.ui.components.PIANO_HEIGHT_DP
import net.exent.perfectpitch.ui.components.RequireMicPermission
import net.exent.perfectpitch.ui.components.SharedNoteCanvas
import net.exent.perfectpitch.ui.components.VOICE_COLORS
import net.exent.perfectpitch.ui.NOTE_DIVISION_OPTIONS
import net.exent.perfectpitch.ui.OVERLAY_BG
import net.exent.perfectpitch.ui.OVERLAY_ICON_TINT
import net.exent.perfectpitch.ui.PANEL_BG
import net.exent.perfectpitch.ui.PANEL_BTN_SIZE
import net.exent.perfectpitch.ui.PANEL_ROW2_BG
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

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
            withFrameMillis { ms -> frameTime = ms }
        }
    }

    val effectiveTime = viewModel.getEffectiveTime(frameTime)
    val midiRange = state.viewMaxMidi - state.viewMinMidi
    val pxPerMs = state.fallSpeedPxPerSec * density.density / 1000f

    // Stable references usable inside pointerInput blocks with Unit keys
    // (those blocks are not restarted on state changes, so raw values would be stale)
    val currentMidiRange by rememberUpdatedState(midiRange)
    val currentPxPerMs by rememberUpdatedState(pxPerMs)
    val currentSelectedNoteId by rememberUpdatedState(state.selectedNoteId)
    val currentIsRecording by rememberUpdatedState(state.isRecording)
    val currentIsPlaying by rememberUpdatedState(state.isPlaying)

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { snackbarHostState.showSnackbar(it) }
    }
    val barMs = state.beatsPerBar * (60000.0 / state.bpm)
    val defaultLoopEndBar = run {
        val maxEndMs = state.playedNotes.mapNotNull { it.endTimeMs }.maxOrNull() ?: 0L
        ceil(maxEndMs.toDouble() / barMs).toInt() + 4
    }.coerceAtLeast(4)
    val effectiveLoopStartBar = state.loopStartBar ?: 1
    val effectiveLoopEndBar = state.loopEndBar ?: defaultLoopEndBar
    val loopStartMs = ((effectiveLoopStartBar - 1) * barMs).toLong()
    val loopEndMs   = ((effectiveLoopEndBar - 1) * barMs).toLong()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/midi")
    ) { uri: Uri? -> uri?.let { viewModel.exportToMidi(it, context) } }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.importFromMidi(it, context) } }

    // Note interaction state
    val noteRectsState = remember { mutableStateOf<List<Pair<String, Rect>>>(emptyList()) }
    val handleRadiusPx = with(density) { 18.dp.toPx() }

    // ── Dropdown / dialog state ──
    var showInputMenu by remember { mutableStateOf(false) }
    var showOutputMenu by remember { mutableStateOf(false) }
    var showConfigMenu by remember { mutableStateOf(false) }
    var showVoiceMenu by remember { mutableStateOf(false) }
    var showBeatsMenu by remember { mutableStateOf(false) }
    var showDivisionMenu by remember { mutableStateOf(false) }
    var showEditHelp by remember { mutableStateOf(false) }

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

        // Help button (always visible)
        IconButton(
            onClick = { showEditHelp = true },
            modifier = Modifier.size(PANEL_BTN_SIZE)
        ) {
            Icon(Icons.Filled.HelpOutline, contentDescription = "Help", tint = OVERLAY_ICON_TINT, modifier = Modifier.size(17.dp))
        }

        // Metronome toggle
        IconButton(
            onClick = { viewModel.toggleMetronome() },
            modifier = Modifier.size(PANEL_BTN_SIZE)
        ) {
            Icon(
                Icons.Filled.Timer,
                contentDescription = if (state.metronomeEnabled) "Metronome on" else "Metronome off",
                tint = if (state.metronomeEnabled) Color(0xFF90CAF9) else OVERLAY_ICON_TINT,
                modifier = Modifier.size(17.dp)
            )
        }

        // Settings
        Box {
            IconButton(
                onClick = { showConfigMenu = true },
                modifier = Modifier.size(PANEL_BTN_SIZE)
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
                    val divIndex = NOTE_DIVISION_OPTIONS.indexOfFirst { it.division == state.minNoteDivision }.takeIf { it >= 0 } ?: 2
                    val reversedIndex = 4 - divIndex
                    DropdownMenuItem(text = { Text("Min note duration: ${NOTE_DIVISION_OPTIONS.find { it.division == state.minNoteDivision }?.label ?: "${state.minNoteDivision}th"}") }, onClick = {})
                    Slider(value = reversedIndex.toFloat(), onValueChange = { viewModel.setMinNoteDivision(NOTE_DIVISION_OPTIONS[4 - it.roundToInt()].division) }, valueRange = 0f..4f, steps = 3, modifier = Modifier.padding(horizontal = 16.dp))
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

        // Help dialog
        if (showEditHelp) {
            AlertDialog(
                onDismissRequest = { showEditHelp = false },
                confirmButton = { TextButton(onClick = { showEditHelp = false }) { Text("Got it") } },
                title = { Text("Free Play — Help", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Note editing", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val noteItems = listOf(
                            Triple(Icons.Filled.TouchApp,      null as Color?,     "Tap a note bar"       to "Select note for editing."),
                            Triple(Icons.Filled.OpenWith,      null,               "Drag a selected note" to "Change time/semitone."),
                            Triple(Icons.Filled.SwipeVertical, null,               "Drag a handle"        to "Adjust start/end."),
                            Triple(Icons.Filled.ContentCut,    Color(0xFFFFB74D),  "Split (✂)"            to "Halves the note."),
                            Triple(Icons.Filled.Delete,        Color(0xFFEF5350),  "Delete (🗑)"           to "Removes the note."),
                            Triple(Icons.Filled.TouchApp,      null,               "Tap empty space"      to "Deselect the note.")
                        )
                        noteItems.forEach { (icon, color, labelAndDesc) ->
                            val (label, description) = labelAndDesc
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                                Icon(icon, null, modifier = Modifier.size(18.dp).padding(top = 1.dp),
                                    tint = color ?: LocalContentColor.current)
                                Column {
                                    Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                        Text("Looping", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.Loop, null, modifier = Modifier.size(18.dp).padding(top = 1.dp))
                            Column {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Loop", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("1", fontSize = 13.sp, color = Color.White,
                                        modifier = Modifier.background(Color(0xFF1A1A2E), MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp))
                                    Text("–", fontSize = 12.sp)
                                    Text("4", fontSize = 13.sp, color = Color(0xFFFFB74D),
                                        modifier = Modifier.background(Color(0xFF1A1A2E), MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp))
                                }
                                Text("Tap a bar number to lock it to the current position (turns orange). Tap the same locked number again to unlock it.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            )
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

        // Loop controls — "Loop X – Y"
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Loop", fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f))
            Spacer(Modifier.width(4.dp))
            Text(
                text = effectiveLoopStartBar.toString(),
                fontSize = 11.sp,
                color = if (state.loopStartBar != null) Color(0xFFFFB74D) else Color.White,
                modifier = Modifier
                    .clickable { viewModel.setLoopStart() }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
            Text("–", fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f))
            Text(
                text = effectiveLoopEndBar.toString(),
                fontSize = 11.sp,
                color = if (state.loopEndBar != null) Color(0xFFFFB74D) else Color.White,
                modifier = Modifier
                    .clickable { viewModel.setLoopEnd() }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }

    // ── Main layout ───────────────────────────────────────────────────────
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopMetronome()
            viewModel.stopPlaying()
            viewModel.stopListening()
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
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
                            viewModel.onHorizontalScroll(pan.x * (currentMidiRange / canvasWidth))
                            viewModel.onVerticalScroll((-pan.y / currentPxPerMs).toLong())
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val tappedId = noteRectsState.value.firstOrNull { (_, r) -> r.contains(offset) }?.first
                        if (tappedId != null) {
                            // Toggle selection on tap
                            viewModel.selectNote(if (tappedId == currentSelectedNoteId) null else tappedId)
                        } else {
                            // Check if tap landed on a handle circle of the selected note
                            // (handles are centered on corners, half outside the noteRect)
                            val selRect = currentSelectedNoteId?.let { id ->
                                noteRectsState.value.firstOrNull { (rid, _) -> rid == id }?.second
                            }
                            val onHandle = selRect != null && (
                                run { val dx = offset.x - selRect.right;  val dy = offset.y - selRect.top;    dx*dx+dy*dy <= handleRadiusPx*handleRadiusPx } ||
                                run { val dx = offset.x - selRect.left;   val dy = offset.y - selRect.bottom; dx*dx+dy*dy <= handleRadiusPx*handleRadiusPx }
                            )
                            if (!onHandle) {
                                // Tap on truly empty space: deselect and pause
                                viewModel.selectNote(null)
                                if (currentIsRecording) viewModel.toggleRecording()
                                else if (currentIsPlaying) viewModel.togglePlaying()
                            }
                        }
                    }
                }
                // Drag on selected note: move body or resize via handles (no long-press needed)
                .pointerInput(state.selectedNoteId) {
                    val selId = state.selectedNoteId ?: return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val noteRect = noteRectsState.value.firstOrNull { (id, _) -> id == selId }?.second
                            ?: return@awaitEachGesture

                        // Handle hit areas: end centered on top-right corner, start on bottom-left corner
                        val isEndHandle = run {
                            val dx = down.position.x - noteRect.right
                            val dy = down.position.y - noteRect.top
                            dx * dx + dy * dy <= (handleRadiusPx * handleRadiusPx)
                        }
                        val isStartHandle = !isEndHandle && run {
                            val dx = down.position.x - noteRect.left
                            val dy = down.position.y - noteRect.bottom
                            dx * dx + dy * dy <= (handleRadiusPx * handleRadiusPx)
                        }
                        val isNoteBody = !isEndHandle && !isStartHandle && noteRect.contains(down.position)

                        if (!isEndHandle && !isStartHandle && !isNoteBody) return@awaitEachGesture
                        down.consume()

                        // Capture note times at drag start; all deltas are computed from origin so
                        // snap jumps to the next grid as the total drag crosses a grid-interval boundary
                        val origInfo = viewModel.getNoteInfo(selId) ?: return@awaitEachGesture
                        val origStartMs = origInfo.startTimeMs
                        val origEndMs   = origInfo.endTimeMs
                        val origMidi    = origInfo.midiNumber

                        var totalYPx     = 0f
                        var localMidiAccum = 0f
                        var totalMidiDelta = 0
                        var lastPlayedMidiDelta = Int.MIN_VALUE
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val delta = change.position - change.previousPosition
                                change.consume()

                                totalYPx += delta.y
                                val totalDeltaMs = -(totalYPx / currentPxPerMs).toLong()

                                when {
                                    isEndHandle   -> {
                                        if (origEndMs != null)
                                            viewModel.extendNoteEnd(selId, origEndMs + totalDeltaMs)
                                    }
                                    isStartHandle -> viewModel.extendNoteStart(selId, origStartMs + totalDeltaMs)
                                    isNoteBody    -> {
                                        localMidiAccum += delta.x * (currentMidiRange / size.width.toFloat())
                                        val deltaMidi = localMidiAccum.toInt()
                                        localMidiAccum -= deltaMidi.toFloat()
                                        if (deltaMidi != 0) totalMidiDelta += deltaMidi
                                        viewModel.moveNote(selId, origMidi + totalMidiDelta, origStartMs + totalDeltaMs)
                                        // Audible feedback when semitone changes
                                        if (totalMidiDelta != lastPlayedMidiDelta) {
                                            lastPlayedMidiDelta = totalMidiDelta
                                            viewModel.previewNoteTone((origMidi + totalMidiDelta).coerceIn(21, 108))
                                        }
                                    }
                                }
                            }
                        } finally {
                            viewModel.commitNoteEdit(selId)
                        }
                    }
                }
        ) {
            RequireMicPermission(skip = state.midiInputEnabled) {
                LaunchedEffect(Unit) {
                    viewModel.initSynthesizer()
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
                        loopStartMs = loopStartMs,
                        loopEndMs = loopEndMs,
                        selectedNoteId = state.selectedNoteId,
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

            // Action buttons — shown to the right of the selected note
            val selectedRect = state.selectedNoteId?.let { id ->
                noteRectsState.value.firstOrNull { (nid, _) -> nid == id }?.second
            }
            if (selectedRect != null) {
                val btnSize = 36.dp
                val btnSizePx = with(density) { btnSize.toPx() }
                val paddingPx = with(density) { 6.dp.toPx() }
                val noteMidY = (selectedRect.top + selectedRect.bottom) / 2f
                val colHeight = btnSizePx * 2 + with(density) { 4.dp.toPx() }
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset((selectedRect.right + paddingPx).toInt(), (noteMidY - colHeight / 2f).toInt()) },
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { viewModel.splitNote(state.selectedNoteId!!) },
                        modifier = Modifier.size(btnSize)
                            .background(Color(0xDD1A1A2E), CircleShape)
                            .border(1.dp, Color(0xFFFFB74D).copy(alpha = 0.7f), CircleShape)
                    ) {
                        Icon(Icons.Filled.ContentCut, contentDescription = "Split note",
                            tint = Color(0xFFFFB74D), modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = { viewModel.deleteNote(state.selectedNoteId!!) },
                        modifier = Modifier.size(btnSize)
                            .background(Color(0xDD1A1A2E), CircleShape)
                            .border(1.dp, Color(0xFFEF5350).copy(alpha = 0.7f), CircleShape)
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete note",
                            tint = Color(0xFFEF5350), modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Idle overlay: play/record buttons shown when neither playing nor recording
            if (!state.isPlaying && !state.isRecording) {
                val showBottomPiano = state.pianoVisibility == PianoVisibility.BOTH ||
                        state.pianoVisibility == PianoVisibility.LOWER_ONLY
                val bottomPadding = (if (showBottomPiano) PIANO_HEIGHT_DP else 0f).dp + 24.dp
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = bottomPadding),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.togglePlaying() },
                        modifier = Modifier
                            .size(72.dp)
                            .background(Color(0xFF66BB6A).copy(alpha = 0.25f), CircleShape)
                            .border(2.dp, Color(0xFF66BB6A).copy(alpha = 0.7f), CircleShape)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play",
                            modifier = Modifier.size(40.dp), tint = Color(0xFF66BB6A).copy(alpha = 0.9f))
                    }
                    IconButton(
                        onClick = { viewModel.toggleRecording() },
                        modifier = Modifier
                            .size(72.dp)
                            .background(Color(0xFFEF5350).copy(alpha = 0.25f), CircleShape)
                            .border(2.dp, Color(0xFFEF5350).copy(alpha = 0.7f), CircleShape)
                    ) {
                        Icon(Icons.Filled.FiberManualRecord, contentDescription = "Record",
                            modifier = Modifier.size(40.dp), tint = Color(0xFFEF5350).copy(alpha = 0.9f))
                    }
                }
            }

        }
    }
    SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).systemBarsPadding())
    } // end Box
}
