package com.perfectpitch.ui.freeplay

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
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
import com.perfectpitch.ui.components.CanvasMode
import com.perfectpitch.ui.components.KeepScreenOn
import com.perfectpitch.ui.components.PIANO_HEIGHT_DP
import com.perfectpitch.ui.components.RequireMicPermission
import com.perfectpitch.ui.components.SharedNoteCanvas

private val BUTTON_AREA_WIDTH = 52.dp
private val OVERLAY_BG = Color(0xAA1A1A2E)
private val OVERLAY_ICON_TINT = Color.White.copy(alpha = 0.9f)
private val OVERLAY_BUTTON_SIZE = 40.dp

@Composable
fun FreePlayScreen(
    onBack: () -> Unit,
    viewModel: FreePlayViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val density = LocalDensity.current

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val canvasWidth = size.width.toFloat()
                    val canvasHeight = size.height.toFloat()

                    // Horizontal zoom (pinch): adjust visible key range
                    if (zoom != 1f) {
                        val focusFractionX = centroid.x / canvasWidth
                        viewModel.onHorizontalZoom(zoom, focusFractionX)
                        viewModel.onVerticalZoom(zoom)
                    }

                    // Pan (drag): scroll
                    if (pan.x != 0f || pan.y != 0f) {
                        val midiRange = state.viewMaxMidi - state.viewMinMidi
                        val midiPerPx = midiRange / canvasWidth
                        viewModel.onHorizontalScroll(pan.x * midiPerPx)

                        // Vertical pan: scroll through time (negate so drag-up = see older notes)
                        val msPerPx = 1000f / (state.fallSpeedPxPerSec * density.density)
                        viewModel.onVerticalScroll((-pan.y * msPerPx).toLong())
                    }
                }
            }
    ) {
        RequireMicPermission {
            LaunchedEffect(Unit) {
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
                    fallSpeedPxPerSec = state.fallSpeedPxPerSec
                ),
                pianoAtBottom = false,
                viewMinMidi = state.viewMinMidi,
                viewMaxMidi = state.viewMaxMidi,
                rightPaddingPx = rightPaddingPx,
                modifier = Modifier.fillMaxSize()
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
                    offset = DpOffset((-120).dp, 0.dp)
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
                }
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
    }
}
