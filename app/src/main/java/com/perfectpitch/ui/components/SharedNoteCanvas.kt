package com.perfectpitch.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.rememberTextMeasurer
import com.perfectpitch.midi.MelodyTrack
import com.perfectpitch.ui.freeplay.PlayedNote
import com.perfectpitch.ui.melody.NoteResultState
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private val CANVAS_BG = Color(0xFF0D0D1A)
private val BLACK_COLUMN_BG = Color(0xFF151520)
private val NOTE_COLOR_UPCOMING = Color(0xBB7C4DFF)
private val GREEN_IN_TUNE = Color(0xFF4CAF50)
private val YELLOW_CLOSE = Color(0xFFFFC107)
private val RED_OFF = Color(0xFFF44336)
private val OVERLAY_PERFECT = Color(0xFF4CAF50)
private val OVERLAY_GREAT = Color(0xFF81C784)
private val OVERLAY_GOOD = Color(0xFFFFC107)
private val OVERLAY_OK = Color(0xFFFF9800)
private val OVERLAY_MISS = Color(0xFFF44336)

private const val BAR_GAP_PX = 2f
private const val INDICATOR_HEIGHT_PX = 8f
private const val CENTS_THRESHOLD_PERFECT = 10f
private const val CENTS_THRESHOLD_CLOSE = 25f
private const val CORNER_RADIUS_DP = 4f

sealed interface CanvasMode {
    data class FreePlay(
        val notes: List<PlayedNote>,
        val activeNote: PlayedNote?,
        val currentTimeMs: Long,
        val timeOffsetMs: Long,
        val fallSpeedPxPerSec: Float,
        val pitchTrace: List<PitchFrame> = emptyList()
    ) : CanvasMode

    data class Melody(
        val melodyTrack: MelodyTrack,
        val noteResults: Map<Int, NoteResultState>,
        val currentNoteIndex: Int,
        val pitchTrace: List<PitchFrame>,
        val currentTimeSeconds: Double,
        val songTimeOffsetSeconds: Double,
        val fallSpeedPxPerSec: Float,
        val detectedMidiNote: Int?,
        val detectedCentsOff: Float?
    ) : CanvasMode
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
    continuousPitchLine: Boolean = true,
    rightPaddingPx: Float = 0f,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier.fillMaxSize()) {
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

        // Piano edge and far edge Y coordinates
        val pianoEdgeY: Float
        val canvasFarEdgeY: Float
        if (pianoAtBottom) {
            pianoEdgeY = height - pianoHeight
            canvasFarEdgeY = 0f
        } else {
            pianoEdgeY = pianoHeight
            canvasFarEdgeY = height
        }

        // Draw background
        drawRect(CANVAS_BG)

        // Draw black-key column backgrounds
        for (midi in minMidi..maxMidi) {
            val key = layout[midi] ?: continue
            if (key.isBlack) {
                val colTop = if (pianoAtBottom) canvasFarEdgeY else pianoEdgeY
                val colHeight = if (pianoAtBottom) pianoEdgeY else canvasFarEdgeY - pianoEdgeY
                drawRect(
                    color = BLACK_COLUMN_BG,
                    topLeft = Offset(key.x, colTop),
                    size = Size(key.width, colHeight)
                )
            }
        }

        // Draw subtle grid lines at white key boundaries
        for (midi in minMidi..maxMidi) {
            val key = layout[midi] ?: continue
            if (!key.isBlack) {
                val lineTop = if (pianoAtBottom) canvasFarEdgeY else pianoEdgeY
                val lineBottom = if (pianoAtBottom) pianoEdgeY else canvasFarEdgeY
                drawLine(
                    color = Color(0x18FFFFFF),
                    start = Offset(key.x, lineTop),
                    end = Offset(key.x, lineBottom),
                    strokeWidth = 1f
                )
            }
        }

        // Mode-specific rendering
        when (mode) {
            is CanvasMode.FreePlay -> {
                drawFreePlayNotes(mode, layout, pianoEdgeY, canvasFarEdgeY, pianoAtBottom, density, continuousPitchLine)
                if (continuousPitchLine) {
                    val viewTimeMs = mode.currentTimeMs - mode.timeOffsetMs
                    drawContinuousPitchLine(mode.pitchTrace, viewTimeMs, mode.fallSpeedPxPerSec, layout, pianoEdgeY, canvasFarEdgeY, pianoAtBottom, density)
                }
            }
            is CanvasMode.Melody -> {
                drawMelodyNotes(mode, layout, pianoEdgeY, canvasFarEdgeY, pianoAtBottom, density)
                drawPitchTrace(mode, layout, pianoEdgeY, canvasFarEdgeY, pianoAtBottom, density, continuousPitchLine)
                if (continuousPitchLine) {
                    val viewTimeMs = ((mode.currentTimeSeconds + mode.songTimeOffsetSeconds) * 1000).toLong()
                    drawContinuousPitchLine(mode.pitchTrace, viewTimeMs, mode.fallSpeedPxPerSec, layout, pianoEdgeY, canvasFarEdgeY, pianoAtBottom, density)
                }
                drawPitchIndicator(mode, layout, pianoEdgeY, pianoAtBottom, density)
            }
        }

        // Draw piano keyboard
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

// region Free Play rendering

private fun DrawScope.drawFreePlayNotes(
    mode: CanvasMode.FreePlay,
    layout: Map<Int, KeyLayout>,
    pianoEdgeY: Float,
    canvasFarEdgeY: Float,
    pianoAtBottom: Boolean,
    density: Float,
    continuousPitchLine: Boolean
) {
    val pxPerMs = mode.fallSpeedPxPerSec * density / 1000f
    val now = mode.currentTimeMs - mode.timeOffsetMs

    val allNotes = buildList {
        addAll(mode.notes)
        mode.activeNote?.let { add(it) }
    }

    for (note in allNotes) {
        val key = layout[note.midiNumber] ?: continue

        val noteEndMs = note.endTimeMs ?: mode.currentTimeMs
        val ageOfEndMs = now - noteEndMs
        val ageOfStartMs = now - note.startTimeMs

        // Piano at top: bars grow downward from piano edge
        // Piano at bottom: bars grow upward from piano edge
        val rawTopY: Float
        val rawBottomY: Float
        if (pianoAtBottom) {
            rawBottomY = pianoEdgeY - (ageOfEndMs * pxPerMs)
            rawTopY = pianoEdgeY - (ageOfStartMs * pxPerMs)
        } else {
            rawTopY = pianoEdgeY + ageOfEndMs * pxPerMs
            rawBottomY = pianoEdgeY + ageOfStartMs * pxPerMs
        }

        // Color based on accuracy — darken slightly for black-key notes
        var barColor = accuracyColor(note.averageCentsOff)
        if (key.isBlack) {
            barColor = barColor.copy(
                red = barColor.red * 0.75f,
                green = barColor.green * 0.75f,
                blue = barColor.blue * 0.75f
            )
        }

        drawNoteBar(
            barX = key.x + BAR_GAP_PX,
            barW = key.width - BAR_GAP_PX * 2f,
            rawTopY = rawTopY,
            rawBottomY = rawBottomY,
            pianoEdgeY = pianoEdgeY,
            canvasFarEdgeY = canvasFarEdgeY,
            pianoAtBottom = pianoAtBottom,
            color = barColor,
            borderAlpha = 0.25f,
            centsHistory = if (continuousPitchLine) emptyList() else note.centsHistory,
            density = density
        )
    }
}

// endregion

// region Continuous pitch line (unified)

/**
 * Draws a single continuous pitch line across ALL pitch trace frames,
 * using absolute MIDI-to-pixel mapping for smooth transitions.
 * The path is broken at silence gaps (>150ms between frames) and
 * pitch jumps (>1 semitone between consecutive frames).
 * Uses clipRect so frames that scroll past the piano edge remain
 * connected (the path extends past the piano, but only the visible
 * portion is rendered).
 *
 * Shared by both Free Play and Melody modes.
 */
private fun DrawScope.drawContinuousPitchLine(
    trace: List<PitchFrame>,
    viewTimeMs: Long,
    fallSpeedPxPerSec: Float,
    layout: Map<Int, KeyLayout>,
    pianoEdgeY: Float,
    canvasFarEdgeY: Float,
    pianoAtBottom: Boolean,
    density: Float
) {
    if (trace.isEmpty()) return

    val pxPerMs = fallSpeedPxPerSec * density / 1000f
    if (pxPerMs <= 0f) return

    val visibleTop = minOf(pianoEdgeY, canvasFarEdgeY)
    val visibleBottom = maxOf(pianoEdgeY, canvasFarEdgeY)
    val maxGapMs = 150L

    // Time range visible on screen, plus a generous margin so lines
    // entering/leaving the viewport stay connected.
    // Derive bounds from the Y formula (y = pianoEdgeY - relativeTimeMs * pxPerMs):
    //   relativeTimeMs = (pianoEdgeY - y) / pxPerMs
    // FreePlay (piano at top): past frames go downward → minRelTime = -(height - pianoEdgeY)/pxPerMs
    // Melody  (piano at bottom): future notes go upward → maxRelTime = pianoEdgeY/pxPerMs
    val visibleSpanMs = ((visibleBottom - visibleTop) / pxPerMs).toLong()
    val marginMs = visibleSpanMs / 2
    val minRelTimeMs = ((pianoEdgeY - visibleBottom) / pxPerMs).toLong()
    val maxRelTimeMs = ((pianoEdgeY - visibleTop) / pxPerMs).toLong()
    val minTimeMs = viewTimeMs + minRelTimeMs - marginMs
    val maxTimeMs = viewTimeMs + maxRelTimeMs + marginMs

    val path = Path()
    var pathStarted = false
    var prevTimeMs = Long.MIN_VALUE
    var prevMidiNote = -1

    for (frame in trace) {
        // Skip frames far outside the visible time range (performance)
        if (frame.timeMs < minTimeMs || frame.timeMs > maxTimeMs) {
            if (pathStarted) pathStarted = false
            prevTimeMs = frame.timeMs
            prevMidiNote = frame.midiNote
            continue
        }

        val fractionalMidi = frame.midiNote + frame.centsOff / 100f
        val x = midiToPixelX(layout, fractionalMidi) ?: continue

        val relativeTimeMs = frame.timeMs - viewTimeMs
        val y = pianoEdgeY - relativeTimeMs * pxPerMs

        val hasGap = prevTimeMs != Long.MIN_VALUE && (frame.timeMs - prevTimeMs) > maxGapMs
        val hasJump = prevMidiNote >= 0 && abs(frame.midiNote - prevMidiNote) > 0

        if (!pathStarted || hasGap || hasJump) {
            path.moveTo(x, y)
            pathStarted = true
        } else {
            path.lineTo(x, y)
        }
        prevTimeMs = frame.timeMs
        prevMidiNote = frame.midiNote
    }

    if (path.isEmpty) return

    // Clip to the canvas area (between piano edge and far edge) so the
    // line is visible even when its Y coordinates extend past the piano.
    clipRect(
        left = 0f,
        top = visibleTop,
        right = size.width,
        bottom = visibleBottom
    ) {
        drawPath(
            path,
            Color.White.copy(alpha = 0.15f),
            style = Stroke(width = 4f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path,
            Color.White.copy(alpha = 0.7f),
            style = Stroke(width = 1.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
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

    for ((index, note) in mode.melodyTrack.notes.withIndex()) {
        val noteStartTime = mode.melodyTrack.noteStartSeconds(note)
        val noteDuration = mode.melodyTrack.noteDurationSeconds(note)
        val noteEndTime = noteStartTime + noteDuration

        val relativeStart = noteStartTime - viewTimeSeconds
        val relativeEnd = noteEndTime - viewTimeSeconds

        // Piano at bottom: notes fall downward toward piano edge
        // relativeStart < 0 means note has passed the hit zone
        var rawBottomY = pianoEdgeY - (relativeStart * pixelsPerSecond).toFloat()
        var rawTopY = pianoEdgeY - (relativeEnd * pixelsPerSecond).toFloat()

        // Enforce minimum gap between consecutive same-pitch notes
        if (index > 0 && mode.melodyTrack.notes[index - 1].noteNumber == note.noteNumber) {
            rawBottomY -= minGapPx / 2f
        }
        if (index < mode.melodyTrack.notes.size - 1 && mode.melodyTrack.notes[index + 1].noteNumber == note.noteNumber) {
            rawTopY += minGapPx / 2f
        }

        // Skip off-screen notes
        val visibleTop = if (pianoAtBottom) canvasFarEdgeY else pianoEdgeY
        val visibleBottom = if (pianoAtBottom) pianoEdgeY else canvasFarEdgeY
        if (rawTopY > visibleBottom + 100f || rawBottomY < visibleTop - 100f) continue

        val key = layout[note.noteNumber] ?: continue
        val barX = key.x + BAR_GAP_PX
        val barW = key.width - BAR_GAP_PX * 2f
        if (barW < 2f) continue

        // Determine color based on note state
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
            pianoEdgeY = pianoEdgeY,
            canvasFarEdgeY = canvasFarEdgeY,
            pianoAtBottom = pianoAtBottom,
            color = noteColor,
            borderAlpha = 0.15f,
            centsHistory = emptyList(),
            density = density
        )
    }
}

private fun DrawScope.drawPitchTrace(
    mode: CanvasMode.Melody,
    layout: Map<Int, KeyLayout>,
    pianoEdgeY: Float,
    canvasFarEdgeY: Float,
    pianoAtBottom: Boolean,
    density: Float,
    suppressWigglyLine: Boolean = false
) {
    val trace = mode.pitchTrace
    if (trace.isEmpty()) return

    val pxPerMs = mode.fallSpeedPxPerSec * density / 1000f
    val viewTimeMs = ((mode.currentTimeSeconds + mode.songTimeOffsetSeconds) * 1000).toLong()
    val frameDurationMs = 23L

    // Group consecutive frames with the same midiNote into segments
    var segStart = 0
    while (segStart < trace.size) {
        val segMidi = trace[segStart].midiNote
        var segEnd = segStart
        while (segEnd + 1 < trace.size && trace[segEnd + 1].midiNote == segMidi) {
            segEnd++
        }

        val key = layout[segMidi]
        if (key == null) {
            segStart = segEnd + 1
            continue
        }

        val startTimeMs = trace[segStart].timeMs
        val endTimeMs = trace[segEnd].timeMs + frameDurationMs

        val relativeStartMs = startTimeMs - viewTimeMs
        val relativeEndMs = endTimeMs - viewTimeMs

        val rawBottomY = pianoEdgeY - relativeStartMs * pxPerMs
        val rawTopY = pianoEdgeY - relativeEndMs * pxPerMs

        // Skip off-screen segments
        val visibleTop = if (pianoAtBottom) canvasFarEdgeY else pianoEdgeY
        val visibleBottom = if (pianoAtBottom) pianoEdgeY else canvasFarEdgeY
        if (rawTopY > visibleBottom + 100f || rawBottomY < visibleTop - 100f) {
            segStart = segEnd + 1
            continue
        }

        // Narrower bar: 60% of key width, centered
        val traceWidth = key.width * 0.6f
        val traceX = key.x + (key.width - traceWidth) / 2f

        // Collect centsHistory for the wiggly line
        val segmentCents = mutableListOf<Float>()
        for (i in segStart..segEnd) {
            segmentCents.add(trace[i].centsOff)
        }

        // Compute average centsOff for color
        val avgCentsOff = segmentCents.map { abs(it) }.average().toFloat()
        val baseColor = traceAccuracyColor(avgCentsOff)

        drawNoteBar(
            barX = traceX,
            barW = traceWidth,
            rawTopY = rawTopY,
            rawBottomY = rawBottomY,
            pianoEdgeY = pianoEdgeY,
            canvasFarEdgeY = canvasFarEdgeY,
            pianoAtBottom = pianoAtBottom,
            color = baseColor.copy(alpha = 0.75f),
            borderAlpha = 0.25f,
            centsHistory = if (suppressWigglyLine) emptyList() else segmentCents,
            density = density
        )

        segStart = segEnd + 1
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

    // Position indicator just inside the piano edge
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

/**
 * Draws a single note bar with per-corner rounding.
 * The piano-adjacent edge gets sharp corners (0 radius) when clipped,
 * while the far edge gets subtle rounded corners (4dp).
 */
private fun DrawScope.drawNoteBar(
    barX: Float,
    barW: Float,
    rawTopY: Float,
    rawBottomY: Float,
    pianoEdgeY: Float,
    canvasFarEdgeY: Float,
    pianoAtBottom: Boolean,
    color: Color,
    borderAlpha: Float,
    centsHistory: List<Float>,
    density: Float
) {
    if (barW < 2f) return

    // Determine visible region (top < bottom in screen coordinates)
    val visibleTop = minOf(pianoEdgeY, canvasFarEdgeY)
    val visibleBottom = maxOf(pianoEdgeY, canvasFarEdgeY)

    // Skip if entirely off-screen
    if (rawTopY > visibleBottom || rawBottomY < visibleTop) return

    val clippedTop = rawTopY.coerceIn(visibleTop, visibleBottom)
    val clippedBottom = rawBottomY.coerceIn(visibleTop, visibleBottom)
    val barHeight = clippedBottom - clippedTop

    if (barHeight < 1f) return

    // Per-corner radius logic
    val r = CORNER_RADIUS_DP * density

    // Determine which edge is piano-side
    // pianoAtBottom: bottom edge is piano-side → sharp corners on bottom when bar touches piano
    // pianoAtTop: top edge is piano-side → sharp corners on top when bar touches piano
    val topIsPianoSide = !pianoAtBottom
    val bottomIsPianoSide = pianoAtBottom

    // Top corners: sharp if top is piano-side AND bar is clipped at piano edge
    val topClippedAtPiano = topIsPianoSide && rawTopY <= pianoEdgeY
    val topR = if (topClippedAtPiano) 0f else r

    // Bottom corners: sharp if bottom is piano-side AND bar is clipped at piano edge
    val bottomClippedAtPiano = bottomIsPianoSide && rawBottomY >= pianoEdgeY
    val bottomR = if (bottomClippedAtPiano) 0f else r

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

    // Draw filled bar
    drawPath(path, color)

    // Draw border
    drawPath(path, Color.White.copy(alpha = borderAlpha), style = Stroke(width = 1f))

    // Draw wiggly pitch line
    if (centsHistory.isNotEmpty() && barHeight > 6f && barW > 8f) {
        drawWigglyPitchLine(
            barX = barX,
            barW = barW,
            clippedTop = clippedTop,
            clippedHeight = barHeight,
            centsHistory = centsHistory,
            pianoAtBottom = pianoAtBottom,
            density = density
        )
    }
}

// endregion

// region Wiggly pitch line

/**
 * Draws a wiggly pitch line inside a note bar.
 * The line runs vertically through the bar, with horizontal position
 * determined by cents deviation at each sample point.
 *
 * Center = perfect pitch (0 cents), left/right = ±50 cents.
 */
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
    val maxDeviationPx = barW / 2f - 2f  // leave 2px margin on each side

    val yStep = if (numSamples > 1) clippedHeight / (numSamples - 1).toFloat() else 0f

    for (i in centsHistory.indices) {
        val normalized = (centsHistory[i] / 50f).coerceIn(-1f, 1f)
        val x = centerX - normalized * maxDeviationPx

        // Y direction depends on piano position:
        // Piano at bottom: oldest sample (index 0) at bottom (near piano), newest at top
        // Piano at top: oldest sample (index 0) at top (near piano), newest at bottom
        val y = if (pianoAtBottom) {
            clippedTop + clippedHeight - i * yStep
        } else {
            clippedTop + i * yStep
        }

        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    // Draw glow (thicker, more transparent)
    drawPath(
        path,
        Color.White.copy(alpha = 0.15f),
        style = Stroke(width = 4f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    // Draw line
    drawPath(
        path,
        Color.White.copy(alpha = 0.7f),
        style = Stroke(width = 1.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

// endregion

// region Color helpers

/** Gradient accuracy color (Free Play style): green → yellow → red */
private fun accuracyColor(centsOff: Float): Color {
    val absCents = abs(centsOff)
    return when {
        absCents <= CENTS_THRESHOLD_PERFECT -> GREEN_IN_TUNE
        absCents <= CENTS_THRESHOLD_CLOSE -> {
            val t = (absCents - CENTS_THRESHOLD_PERFECT) / (CENTS_THRESHOLD_CLOSE - CENTS_THRESHOLD_PERFECT)
            lerpColor(GREEN_IN_TUNE, YELLOW_CLOSE, t)
        }
        else -> {
            val t = ((absCents - CENTS_THRESHOLD_CLOSE) / 25f).coerceAtMost(1f)
            lerpColor(YELLOW_CLOSE, RED_OFF, t)
        }
    }
}

/** Discrete accuracy color (Melody mode trace) */
private fun traceAccuracyColor(avgCentsOff: Float): Color {
    return when {
        avgCentsOff <= 5f -> OVERLAY_PERFECT
        avgCentsOff <= 10f -> OVERLAY_GREAT
        avgCentsOff <= 30f -> OVERLAY_GOOD
        avgCentsOff <= 50f -> OVERLAY_OK
        else -> OVERLAY_MISS
    }
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

private fun lerpColor(start: Color, end: Color, fraction: Float): Color {
    return Color(
        red = start.red + (end.red - start.red) * fraction,
        green = start.green + (end.green - start.green) * fraction,
        blue = start.blue + (end.blue - start.blue) * fraction,
        alpha = 1f
    )
}

// endregion
