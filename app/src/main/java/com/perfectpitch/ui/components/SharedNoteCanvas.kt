package com.perfectpitch.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.perfectpitch.midi.MelodyTrack
import com.perfectpitch.ui.freeplay.PianoVisibility
import com.perfectpitch.ui.freeplay.PlayedNote
import com.perfectpitch.ui.melody.NoteResultState
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private val CANVAS_BG = Color(0xFF0D0D1A)
private val BLACK_COLUMN_BG = Color(0xFF151520)
private val NOTE_COLOR_UPCOMING = Color(0xBB7C4DFF)
private val OVERLAY_PERFECT = Color(0xFF4CAF50)
private val OVERLAY_GREAT = Color(0xFF81C784)
private val OVERLAY_GOOD = Color(0xFFFFC107)
private val OVERLAY_OK = Color(0xFFFF9800)
private val OVERLAY_MISS = Color(0xFFF44336)

private const val BAR_GAP_PX = 2f
private const val INDICATOR_HEIGHT_PX = 8f
private const val CORNER_RADIUS_DP = 4f

val VOICE_COLORS = listOf(
    Color(0xFF4FC3F7), // Teal/light blue
    Color(0xFFFFB74D), // Amber
    Color(0xFFCE93D8), // Purple
    Color(0xFFF48FB1), // Pink
    Color(0xFF81C784), // Green
    Color(0xFFFF8A65), // Orange
    Color(0xFF80CBC4), // Teal
    Color(0xFFFFF176), // Yellow
    Color(0xFF80DEEA), // Cyan
    Color(0xFFBCAAA4), // Brown
)

sealed interface CanvasMode {
    data class FreePlay(
        val notes: List<PlayedNote>,
        val activeNote: PlayedNote?,
        val songPositionMs: Long,
        val fallSpeedPxPerSec: Float,
        val bpm: Float = 120f,
        val beatsPerBar: Int = 4,
        val activeVoiceIndex: Int = 0,
        val loopStartMs: Long? = null,
        val loopEndMs: Long? = null,
        val selectedNoteId: String? = null,
        val pianoVisibility: PianoVisibility = PianoVisibility.BOTH
    ) : CanvasMode

    data class Melody(
        val melodyTrack: MelodyTrack,
        val noteResults: Map<Int, NoteResultState>,
        val currentNoteIndex: Int,
        val noteCentsHistory: Map<Int, List<Float>> = emptyMap(),
        val currentTimeSeconds: Double,
        val songTimeOffsetSeconds: Double,
        val fallSpeedPxPerSec: Float,
        val detectedMidiNote: Int?,
        val detectedCentsOff: Float?
    ) : CanvasMode
}

internal fun computeFreePlayNoteRects(
    notes: List<PlayedNote>,
    now: Long,
    pianoEdgeY: Float,
    pxPerMs: Float,
    layout: Map<Int, KeyLayout>
): List<Pair<String, Rect>> {
    val result = mutableListOf<Pair<String, Rect>>()
    for (note in notes) {
        val endTimeMs = note.endTimeMs ?: continue
        val key = layout[note.midiNumber] ?: continue
        val ageOfEndMs = now - endTimeMs
        val ageOfStartMs = now - note.startTimeMs
        val rawTopY = pianoEdgeY + ageOfEndMs * pxPerMs
        val rawBottomY = pianoEdgeY + ageOfStartMs * pxPerMs
        result.add(
            note.id to Rect(
                left = key.x + BAR_GAP_PX,
                top = rawTopY,
                right = key.x + key.width - BAR_GAP_PX,
                bottom = rawBottomY
            )
        )
    }
    return result
}

/**
 * A unified note canvas used by both Free Play and Melody Play modes.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun SharedNoteCanvas(
    mode: CanvasMode,
    pianoAtBottom: Boolean,
    viewMinMidi: Float,
    viewMaxMidi: Float,
    rightPaddingPx: Float = 0f,
    onNoteRects: ((List<Pair<String, Rect>>) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Emit note rects for edit-mode hit testing (FreePlay only)
    if (onNoteRects != null && mode is CanvasMode.FreePlay && canvasSize != IntSize.Zero) {
        val w = canvasSize.width.toFloat()
        val pianoEdgeY = canvasSize.height / 2f
        val pxPerMs = mode.fallSpeedPxPerSec * density / 1000f
        val now = mode.songPositionMs
        val effMax = if (rightPaddingPx > 0f && w > rightPaddingPx) {
            val midiPerPx = (viewMaxMidi - viewMinMidi) / (w - rightPaddingPx)
            viewMaxMidi + rightPaddingPx * midiPerPx
        } else viewMaxMidi
        val layout = calculatePianoLayout(viewMinMidi, effMax, w)
        val rects = computeFreePlayNoteRects(mode.notes, now, pianoEdgeY, pxPerMs, layout)
        SideEffect { onNoteRects(rects) }
    }

    Canvas(modifier = modifier.fillMaxSize().onSizeChanged { canvasSize = it }) {
        val width = size.width
        val height = size.height
        val pianoHeight = PIANO_HEIGHT_DP * density

        // Extend viewMaxMidi so content fits in (width - rightPaddingPx)
        val effectiveViewMaxMidi = if (rightPaddingPx > 0f && width > rightPaddingPx) {
            val contentWidth = width - rightPaddingPx
            val midiPerPx = (viewMaxMidi - viewMinMidi) / contentWidth
            viewMaxMidi + rightPaddingPx * midiPerPx
        } else {
            viewMaxMidi
        }

        val layout = calculatePianoLayout(viewMinMidi, effectiveViewMaxMidi, width)
        val minMidi = floor(viewMinMidi).toInt()
        val maxMidi = ceil(effectiveViewMaxMidi).toInt()

        val pianoVisibility = if (mode is CanvasMode.FreePlay) mode.pianoVisibility else PianoVisibility.BOTH
        val showTopPiano = pianoVisibility == PianoVisibility.BOTH || pianoVisibility == PianoVisibility.UPPER_ONLY
        val showBottomPiano = pianoVisibility == PianoVisibility.BOTH || pianoVisibility == PianoVisibility.LOWER_ONLY

        // Piano edge and far edge Y coordinates
        val pianoEdgeY: Float   // the "now" line or melody piano edge
        val canvasFarEdgeY: Float
        // For FreePlay: two pianos (top + bottom), now-line at height/2
        val noteAreaTop: Float  // top of the note drawing area (bottom of top piano)
        val noteAreaBottom: Float  // bottom of the note drawing area (top of bottom piano)
        if (mode is CanvasMode.FreePlay) {
            pianoEdgeY = height / 2f
            canvasFarEdgeY = height
            noteAreaTop = if (showTopPiano) pianoHeight else 0f
            noteAreaBottom = if (showBottomPiano) height - pianoHeight else height
        } else if (pianoAtBottom) {
            pianoEdgeY = height - pianoHeight
            canvasFarEdgeY = 0f
            noteAreaTop = canvasFarEdgeY
            noteAreaBottom = pianoEdgeY
        } else {
            pianoEdgeY = pianoHeight
            canvasFarEdgeY = height
            noteAreaTop = pianoEdgeY
            noteAreaBottom = canvasFarEdgeY
        }

        // Draw background
        drawRect(CANVAS_BG)

        // Compute playable-area bottom (before bar 1 = black, no columns/grid)
        val playableBottom: Float = when {
            mode is CanvasMode.FreePlay && mode.fallSpeedPxPerSec > 0f -> {
                val pxPerMs = mode.fallSpeedPxPerSec * density / 1000f
                val now = mode.songPositionMs.toDouble()
                val bar1Y = (pianoEdgeY + now * pxPerMs).toFloat()
                bar1Y.coerceIn(noteAreaTop, noteAreaBottom)
            }
            mode is CanvasMode.Melody && mode.fallSpeedPxPerSec > 0f && pianoAtBottom -> {
                val pps = mode.fallSpeedPxPerSec * density
                val viewTimeSeconds = mode.currentTimeSeconds + mode.songTimeOffsetSeconds
                val bar1Y = (pianoEdgeY + viewTimeSeconds * pps).toFloat()
                bar1Y.coerceIn(noteAreaTop, noteAreaBottom)
            }
            else -> noteAreaBottom
        }

        // Draw black-key column backgrounds (clipped to playable area)
        for (midi in minMidi..maxMidi) {
            val key = layout[midi] ?: continue
            if (key.isBlack && playableBottom > noteAreaTop) {
                drawRect(
                    color = BLACK_COLUMN_BG,
                    topLeft = Offset(key.x, noteAreaTop),
                    size = Size(key.width, playableBottom - noteAreaTop)
                )
            }
        }

        // Draw subtle grid lines at white key boundaries (clipped to playable area)
        if (playableBottom > noteAreaTop) {
            for (midi in minMidi..maxMidi) {
                val key = layout[midi] ?: continue
                if (!key.isBlack) {
                    drawLine(
                        color = Color(0x18FFFFFF),
                        start = Offset(key.x, noteAreaTop),
                        end = Offset(key.x, playableBottom),
                        strokeWidth = 1f
                    )
                }
            }
        }

        // Mode-specific rendering
        when (mode) {
            is CanvasMode.FreePlay -> {
                drawBarLines(mode, pianoEdgeY, noteAreaTop, noteAreaBottom, density, textMeasurer)
                drawFreePlayNotes(mode, layout, pianoEdgeY, noteAreaTop, noteAreaBottom, density)
                // "Now" line — thin bright marker in the center
                drawLine(
                    color = Color.White.copy(alpha = 0.55f),
                    start = Offset(0f, pianoEdgeY),
                    end = Offset(width, pianoEdgeY),
                    strokeWidth = 1.5f * density
                )
                if (showTopPiano) {
                    drawPianoKeyboard(
                        textMeasurer = textMeasurer,
                        layout = layout,
                        pianoHeight = pianoHeight,
                        minMidi = minMidi,
                        maxMidi = maxMidi,
                        pianoAtBottom = false
                    )
                }
                if (showBottomPiano) {
                    drawPianoKeyboard(
                        textMeasurer = textMeasurer,
                        layout = layout,
                        pianoHeight = pianoHeight,
                        minMidi = minMidi,
                        maxMidi = maxMidi,
                        pianoAtBottom = true
                    )
                }
            }
            is CanvasMode.Melody -> {
                drawBarLines(mode, pianoEdgeY, canvasFarEdgeY, pianoAtBottom, density, textMeasurer, playableBottom)
                drawMelodyNotes(mode, layout, pianoEdgeY, canvasFarEdgeY, pianoAtBottom, density)
                drawPitchIndicator(mode, layout, pianoEdgeY, pianoAtBottom, density)
                // Single piano for Melody mode
                drawPianoKeyboard(
                    textMeasurer = textMeasurer,
                    layout = layout,
                    pianoHeight = pianoHeight,
                    minMidi = minMidi,
                    maxMidi = maxMidi,
                    pianoAtBottom = pianoAtBottom
                )
            }
        }
    }
}

// region Bar/beat line rendering

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawBarLines(
    mode: CanvasMode.FreePlay,
    nowY: Float,
    noteAreaTop: Float,
    noteAreaBottom: Float,
    density: Float,
    textMeasurer: TextMeasurer
) {
    if (mode.bpm <= 0f) return
    val pxPerMs = mode.fallSpeedPxPerSec * density / 1000f
    if (pxPerMs <= 0f) return

    val now = mode.songPositionMs.toDouble()
    val beatDurationMs = 60_000.0 / mode.bpm
    val barDurationMs = mode.beatsPerBar * beatDurationMs

    val visibleTop = noteAreaTop
    val visibleBottom = noteAreaBottom

    // Clip all drawing to the playable area — nothing drawn below the bar-1 line
    val bar1Y = (nowY + now * pxPerMs).toFloat()
    val clipBottom = bar1Y.coerceIn(visibleTop, visibleBottom)
    if (clipBottom <= visibleTop) return

    clipRect(left = 0f, top = visibleTop, right = size.width, bottom = clipBottom) {

    // Draw loop region if active
    val loopStartMs = mode.loopStartMs
    val loopEndMs = mode.loopEndMs
    if (loopStartMs != null && loopEndMs != null && loopEndMs > loopStartMs) {
        val loopBandTop = (nowY + (now - loopEndMs) * pxPerMs).toFloat().coerceIn(visibleTop, visibleBottom)
        val loopBandBottom = (nowY + (now - loopStartMs) * pxPerMs).toFloat().coerceIn(visibleTop, visibleBottom)
        if (loopBandBottom > loopBandTop) {
            drawRect(
                color = Color(0x33FFB74D),
                topLeft = Offset(0f, loopBandTop),
                size = Size(size.width, loopBandBottom - loopBandTop)
            )
            if (loopBandTop >= visibleTop && loopBandTop <= visibleBottom) {
                drawLine(Color(0xAAFFB74D), Offset(0f, loopBandTop), Offset(size.width, loopBandTop), strokeWidth = 2f * density)
            }
            if (loopBandBottom >= visibleTop && loopBandBottom <= visibleBottom) {
                drawLine(Color(0xAAFFB74D), Offset(0f, loopBandBottom), Offset(size.width, loopBandBottom), strokeWidth = 2f * density)
            }
        }
    }

    val earliestTimeMs = now - (noteAreaBottom - nowY) / pxPerMs * 1.1
    val firstBeatIndex = kotlin.math.floor(earliestTimeMs / beatDurationMs).toLong() - 1L

    val barLabelStyle = TextStyle(
        color = Color.White.copy(alpha = 0.35f),
        fontSize = (9f * density / 2.75f).sp,
        fontWeight = FontWeight.Medium
    )

    var beatIndex = firstBeatIndex
    var safetyCounter = 0
    while (safetyCounter++ < 2000) {
        val beatTimeMs = beatIndex * beatDurationMs
        // Skip beats before session start (bar 1)
        if (beatIndex < 0) {
            beatIndex++
            continue
        }
        val ageMs = now - beatTimeMs
        val y = (nowY + ageMs * pxPerMs).toFloat()
        if (y < visibleTop - 2f) break
        val isBar = beatIndex % mode.beatsPerBar == 0L
        if (y <= visibleBottom + 2f) {
            drawLine(
                color = Color.White.copy(alpha = if (isBar) 0.20f else 0.08f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = if (isBar) 1.5f * density else 1f * density
            )
            // Draw bar number at the left edge of each bar line
            if (isBar) {
                val barNumber = (beatTimeMs / barDurationMs).toLong() + 1
                val label = textMeasurer.measure(
                    text = buildAnnotatedString { append(barNumber.toString()) },
                    style = barLabelStyle
                )
                val labelY = (y - label.size.height - 2f * density).coerceAtLeast(visibleTop.toFloat())
                if (labelY + label.size.height <= visibleBottom) {
                    drawText(label, topLeft = Offset(3f * density, labelY))
                }
            }
        }
        beatIndex++
    }
    } // end clipRect
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawBarLines(
    mode: CanvasMode.Melody,
    pianoEdgeY: Float,
    canvasFarEdgeY: Float,
    pianoAtBottom: Boolean,
    density: Float,
    textMeasurer: TextMeasurer,
    playableBottom: Float
) {
    val track = mode.melodyTrack
    val pixelsPerSecond = mode.fallSpeedPxPerSec * density
    if (pixelsPerSecond <= 0f) return

    val viewTimeSeconds = mode.currentTimeSeconds + mode.songTimeOffsetSeconds

    val visibleTop = minOf(pianoEdgeY, canvasFarEdgeY)
    val visibleBottom = maxOf(pianoEdgeY, canvasFarEdgeY)
    val visibleRangeSeconds = (visibleBottom - visibleTop) / pixelsPerSecond

    val firstTempoUs = track.tempoChanges.firstOrNull()?.microsecondsPerBeat ?: 500_000L
    val songDurationSeconds = track.durationSeconds

    val earliestViewSeconds = viewTimeSeconds - visibleRangeSeconds * 1.1
    val latestViewSeconds = viewTimeSeconds + visibleRangeSeconds * 1.1

    var currentSeconds = 0.0
    var currentTick = 0L
    var tempoIndex = 0
    var beatCount = 0L
    var barNumber = 1L

    data class BeatPoint(val seconds: Double, val isBarLine: Boolean, val barNumber: Long = 0L)
    val beats = mutableListOf<BeatPoint>()

    while (currentSeconds < latestViewSeconds + 1.0 && currentSeconds <= songDurationSeconds + 1.0) {
        val tempoUs = if (tempoIndex < track.tempoChanges.size) {
            track.tempoChanges[tempoIndex].microsecondsPerBeat
        } else firstTempoUs
        val beatDurationSeconds = tempoUs / 1_000_000.0

        val timeSig = track.timeSignatureAt(currentTick)
        val beatsPerBarHere = timeSig.numerator
        val isBar = beatsPerBarHere > 0 && (beatCount % beatsPerBarHere == 0L)

        if (currentSeconds >= earliestViewSeconds) {
            beats.add(BeatPoint(currentSeconds, isBar, if (isBar) barNumber else 0L))
        }
        if (isBar) barNumber++

        val nextBeatSeconds = currentSeconds + beatDurationSeconds
        val nextBeatTick = currentTick + track.ticksPerQuarterNote.toLong()

        if (tempoIndex + 1 < track.tempoChanges.size) {
            val nextTempoTick = track.tempoChanges[tempoIndex + 1].tick
            if (nextBeatTick >= nextTempoTick) {
                tempoIndex++
            }
        }

        currentSeconds = nextBeatSeconds
        currentTick = nextBeatTick
        beatCount++

        if (beatCount > 10_000L) break
    }

    val barLabelStyle = TextStyle(
        color = Color.White.copy(alpha = 0.35f),
        fontSize = (9f * density / 2.75f).sp,
        fontWeight = FontWeight.Medium
    )

    for (beat in beats) {
        val relativeSeconds = beat.seconds - viewTimeSeconds
        val y = pianoEdgeY - (relativeSeconds * pixelsPerSecond).toFloat()
        if (y < visibleTop - 2f || y > playableBottom + 2f) continue
        drawLine(
            color = Color.White.copy(alpha = if (beat.isBarLine) 0.20f else 0.08f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = if (beat.isBarLine) 1.5f * density else 1f * density
        )
        if (beat.isBarLine && beat.barNumber > 0L) {
            val label = textMeasurer.measure(
                text = buildAnnotatedString { append(beat.barNumber.toString()) },
                style = barLabelStyle
            )
            val labelY = (y - label.size.height - 2f * density).coerceAtLeast(visibleTop)
            if (labelY + label.size.height <= playableBottom) {
                drawText(label, topLeft = Offset(3f * density, labelY))
            }
        }
    }
}

// endregion

// region Free Play rendering

private fun DrawScope.drawFreePlayNotes(
    mode: CanvasMode.FreePlay,
    layout: Map<Int, KeyLayout>,
    pianoEdgeY: Float,
    noteAreaTop: Float,
    noteAreaBottom: Float,
    density: Float
) {
    val pxPerMs = mode.fallSpeedPxPerSec * density / 1000f
    val now = mode.songPositionMs
    val handleRadius = 12f * density

    val allNotes = buildList {
        addAll(mode.notes)
        mode.activeNote?.let { add(it) }
    }

    // Pass 1: draw all note bars and selection glows
    for (note in allNotes) {
        val key = layout[note.midiNumber] ?: continue

        val noteEndMs = note.endTimeMs ?: mode.songPositionMs
        val ageOfEndMs = now - noteEndMs
        val ageOfStartMs = now - note.startTimeMs

        // Notes fall downward: past (positive age) → below now-line, future (negative age) → above
        val rawTopY = pianoEdgeY + ageOfEndMs * pxPerMs
        val rawBottomY = pianoEdgeY + ageOfStartMs * pxPerMs

        // Color based on voice; non-active voices are slightly dimmed, black keys slightly darker.
        // No position-based color changes: all notes look the same regardless of where they are
        // relative to the now-line (avoids jarring colour shift as notes scroll past).
        val baseVoiceColor = VOICE_COLORS[note.voiceIndex % VOICE_COLORS.size]
        val isActiveVoice = note.voiceIndex == mode.activeVoiceIndex || note.isActive
        var barColor = if (isActiveVoice) baseVoiceColor else baseVoiceColor.copy(alpha = 0.6f)
        if (key.isBlack) {
            barColor = barColor.copy(
                red = barColor.red * 0.75f,
                green = barColor.green * 0.75f,
                blue = barColor.blue * 0.75f
            )
        }

        val isSelected = note.id == mode.selectedNoteId

        drawNoteBar(
            barX = key.x + BAR_GAP_PX,
            barW = key.width - BAR_GAP_PX * 2f,
            rawTopY = rawTopY,
            rawBottomY = rawBottomY,
            visibleTop = noteAreaTop,
            visibleBottom = noteAreaBottom,
            color = barColor,
            borderAlpha = if (isSelected) 0.0f else 0.25f,  // selection glow replaces border
            centsHistory = note.centsHistory,
            density = density
        )

        // Selection glow: bright thick rounded outline drawn on top of the note bar
        if (isSelected) {
            val barX = key.x + BAR_GAP_PX
            val barW = key.width - BAR_GAP_PX * 2f
            if (barW >= 2f) {
                val clippedTop    = rawTopY.coerceIn(noteAreaTop, noteAreaBottom)
                val clippedBottom = rawBottomY.coerceIn(noteAreaTop, noteAreaBottom)
                if (clippedBottom - clippedTop >= 1f) {
                    val r = CORNER_RADIUS_DP * density
                    val topR    = if (rawTopY    <= noteAreaTop)    0f else r
                    val bottomR = if (rawBottomY >= noteAreaBottom) 0f else r
                    val selPath = Path().apply {
                        addRoundRect(RoundRect(
                            left = barX, top = clippedTop, right = barX + barW, bottom = clippedBottom,
                            topLeftCornerRadius    = CornerRadius(topR, topR),
                            topRightCornerRadius   = CornerRadius(topR, topR),
                            bottomLeftCornerRadius = CornerRadius(bottomR, bottomR),
                            bottomRightCornerRadius = CornerRadius(bottomR, bottomR)
                        ))
                    }
                    // Soft outer glow
                    drawPath(selPath, Color.White.copy(alpha = 0.30f), style = Stroke(width = 5f * density))
                    // Crisp bright inner border
                    drawPath(selPath, Color.White.copy(alpha = 0.90f), style = Stroke(width = 1.5f * density))
                }
            }
        }
    }

    // Pass 2: draw handles for the selected note on top of all bars
    val selectedNote = allNotes.firstOrNull { it.id == mode.selectedNoteId }
    if (selectedNote != null && selectedNote.endTimeMs != null) {
        val key = layout[selectedNote.midiNumber]
        if (key != null) {
            val noteEndMs = selectedNote.endTimeMs
            val rawTopY    = (pianoEdgeY + (now - noteEndMs)             * pxPerMs).coerceIn(noteAreaTop, noteAreaBottom)
            val rawBottomY = (pianoEdgeY + (now - selectedNote.startTimeMs) * pxPerMs).coerceIn(noteAreaTop, noteAreaBottom)
            val barX = key.x + BAR_GAP_PX
            val barW = key.width - BAR_GAP_PX * 2f
            val baseVoiceColor = VOICE_COLORS[selectedNote.voiceIndex % VOICE_COLORS.size]
            var barColor = if (selectedNote.voiceIndex == mode.activeVoiceIndex || selectedNote.isActive) baseVoiceColor
                           else baseVoiceColor.copy(alpha = 0.6f)
            if (key.isBlack) barColor = barColor.copy(red = barColor.red * 0.75f, green = barColor.green * 0.75f, blue = barColor.blue * 0.75f)

            // End handle: top-right corner
            val endHandleX = barX + barW - handleRadius
            drawCircle(color = Color.White.copy(alpha = 0.9f), radius = handleRadius,                center = Offset(endHandleX, rawTopY))
            drawCircle(color = barColor,                        radius = handleRadius - 3f * density, center = Offset(endHandleX, rawTopY))
            // Start handle: bottom-left corner
            val startHandleX = barX + handleRadius
            drawCircle(color = Color.White.copy(alpha = 0.9f), radius = handleRadius,                center = Offset(startHandleX, rawBottomY))
            drawCircle(color = barColor,                        radius = handleRadius - 3f * density, center = Offset(startHandleX, rawBottomY))
        }
    }
}

// endregion

private fun DrawScope.drawMelodyNotes(
    mode: CanvasMode.Melody,
    layout: Map<Int, KeyLayout>,
    pianoEdgeY: Float,
    canvasFarEdgeY: Float,
    pianoAtBottom: Boolean,
    density: Float
) {
    val pixelsPerSecond = mode.fallSpeedPxPerSec * density
    val viewTimeSeconds = mode.currentTimeSeconds + mode.songTimeOffsetSeconds
    val minGapPx = 4f * density

    mode.melodyTrack.notes.forEachIndexed { index, note ->
        val noteStartTime = mode.melodyTrack.noteStartSeconds(note)
        val noteDuration = mode.melodyTrack.noteDurationSeconds(note)
        val noteEndTime = noteStartTime + noteDuration

        val relativeStart = noteStartTime - viewTimeSeconds
        val relativeEnd = noteEndTime - viewTimeSeconds

        var rawBottomY = pianoEdgeY - (relativeStart * pixelsPerSecond).toFloat()
        var rawTopY = pianoEdgeY - (relativeEnd * pixelsPerSecond).toFloat()

        if (index > 0 && mode.melodyTrack.notes[index - 1].noteNumber == note.noteNumber) {
            rawBottomY -= minGapPx / 2f
        }
        if (index < mode.melodyTrack.notes.size - 1 && mode.melodyTrack.notes[index + 1].noteNumber == note.noteNumber) {
            rawTopY += minGapPx / 2f
        }

        val visibleTop = if (pianoAtBottom) canvasFarEdgeY else pianoEdgeY
        val visibleBottom = if (pianoAtBottom) pianoEdgeY else canvasFarEdgeY
        if (rawTopY > visibleBottom + 100f || rawBottomY < visibleTop - 100f) return@forEachIndexed

        val key = layout[note.noteNumber] ?: return@forEachIndexed
        val barX = key.x + BAR_GAP_PX
        val barW = key.width - BAR_GAP_PX * 2f
        if (barW < 2f) return@forEachIndexed

        val noteColor = if (mode.noteResults[index] == NoteResultState.ACTIVE) {
            Color.White
        } else {
            NOTE_COLOR_UPCOMING
        }

        drawNoteBar(
            barX = barX,
            barW = barW,
            rawTopY = rawTopY,
            rawBottomY = rawBottomY,
            visibleTop = visibleTop,
            visibleBottom = visibleBottom,
            color = noteColor,
            borderAlpha = 0.15f,
            centsHistory = mode.noteCentsHistory[index] ?: emptyList(),
            density = density,
            wigglyPianoAtBottom = pianoAtBottom
        )
    }
}

private fun DrawScope.drawPitchIndicator(
    mode: CanvasMode.Melody,
    layout: Map<Int, KeyLayout>,
    pianoEdgeY: Float,
    pianoAtBottom: Boolean,
    density: Float
) {
    val detectedMidi = mode.detectedMidiNote ?: return
    val key = layout[detectedMidi] ?: return

    val viewTimeSeconds = mode.currentTimeSeconds + mode.songTimeOffsetSeconds
    val indicatorColor = getIndicatorColor(detectedMidi, mode.detectedCentsOff, mode.melodyTrack, viewTimeSeconds)

    val indicatorX = key.x + BAR_GAP_PX
    val indicatorW = key.width - BAR_GAP_PX * 2f

    val indicatorY = if (pianoAtBottom) {
        pianoEdgeY - INDICATOR_HEIGHT_PX - 2f
    } else {
        pianoEdgeY + 2f
    }

    drawRoundRect(
        color = indicatorColor,
        topLeft = Offset(indicatorX, indicatorY),
        size = Size(indicatorW, INDICATOR_HEIGHT_PX),
        cornerRadius = CornerRadius(2f * density, 2f * density)
    )
}

// endregion

// region Shared note bar rendering

private fun DrawScope.drawNoteBar(
    barX: Float,
    barW: Float,
    rawTopY: Float,
    rawBottomY: Float,
    visibleTop: Float,
    visibleBottom: Float,
    color: Color,
    borderAlpha: Float,
    centsHistory: List<Float>,
    density: Float,
    wigglyPianoAtBottom: Boolean = false
) {
    if (barW < 2f) return

    if (rawTopY > visibleBottom || rawBottomY < visibleTop) return

    val clippedTop = rawTopY.coerceIn(visibleTop, visibleBottom)
    val clippedBottom = rawBottomY.coerceIn(visibleTop, visibleBottom)
    val barHeight = clippedBottom - clippedTop

    if (barHeight < 1f) return

    val r = CORNER_RADIUS_DP * density

    // Square corners when the bar is clipped at an edge (continuous with the boundary)
    val topR = if (rawTopY <= visibleTop) 0f else r
    val bottomR = if (rawBottomY >= visibleBottom) 0f else r

    val roundRect = RoundRect(
        left = barX,
        top = clippedTop,
        right = barX + barW,
        bottom = clippedBottom,
        topLeftCornerRadius = CornerRadius(topR, topR),
        topRightCornerRadius = CornerRadius(topR, topR),
        bottomLeftCornerRadius = CornerRadius(bottomR, bottomR),
        bottomRightCornerRadius = CornerRadius(bottomR, bottomR)
    )
    val path = Path().apply { addRoundRect(roundRect) }

    drawPath(path, color)
    drawPath(path, Color.White.copy(alpha = borderAlpha), style = Stroke(width = 1f))

    if (centsHistory.isNotEmpty() && barHeight > 6f && barW > 8f) {
        drawWigglyPitchLine(
            barX = barX,
            barW = barW,
            clippedTop = clippedTop,
            clippedHeight = barHeight,
            centsHistory = centsHistory,
            pianoAtBottom = wigglyPianoAtBottom,
            density = density
        )
    }
}

// endregion

// region Wiggly pitch line

private fun DrawScope.drawWigglyPitchLine(
    barX: Float,
    barW: Float,
    clippedTop: Float,
    clippedHeight: Float,
    centsHistory: List<Float>,
    pianoAtBottom: Boolean,
    density: Float
) {
    val numSamples = centsHistory.size
    if (numSamples < 1) return

    val path = Path()
    val centerX = barX + barW / 2f
    val maxDeviationPx = barW / 2f - 2f

    val yStep = if (numSamples > 1) clippedHeight / (numSamples - 1).toFloat() else 0f

    for (i in centsHistory.indices) {
        val normalized = (centsHistory[i] / 50f).coerceIn(-1f, 1f)
        val x = centerX - normalized * maxDeviationPx

        val y = if (pianoAtBottom) {
            clippedTop + clippedHeight - i * yStep
        } else {
            clippedTop + i * yStep
        }

        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    drawGlowPath(path, density)
}

// endregion

// region Color helpers

/** Two-pass glow stroke: wide translucent halo + narrow bright line. */
private fun DrawScope.drawGlowPath(path: Path, density: Float) {
    drawPath(path, Color.White.copy(alpha = 0.15f), style = Stroke(width = 4f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, Color.White.copy(alpha = 0.7f),  style = Stroke(width = 1.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Color for the pitch indicator based on proximity to expected note */
private fun getIndicatorColor(
    detectedMidi: Int,
    centsOff: Float?,
    melodyTrack: MelodyTrack,
    viewTimeSeconds: Double
): Color {
    val expectedNote = melodyTrack.getNoteAtTime(viewTimeSeconds)
        ?: return Color.White.copy(alpha = 0.5f)

    return if (detectedMidi == expectedNote.noteNumber) {
        val absCents = abs(centsOff ?: 50f)
        when {
            absCents <= 5f -> OVERLAY_PERFECT
            absCents <= 10f -> OVERLAY_GREAT
            absCents <= 30f -> OVERLAY_GOOD
            absCents <= 50f -> OVERLAY_OK
            else -> OVERLAY_MISS
        }
    } else {
        OVERLAY_MISS.copy(alpha = 0.6f)
    }
}

// endregion
