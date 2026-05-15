package com.perfectpitch.ui.melody

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.rememberTextMeasurer
import com.perfectpitch.midi.MelodyTrack
import com.perfectpitch.ui.components.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private val CANVAS_BG = Color(0xFF0D0D1A)
private val BLACK_COLUMN_BG = Color(0xFF151520)
private val NOTE_COLOR_UPCOMING = Color(0xBB7C4DFF)     // Purple, semi-transparent
private val NOTE_COLOR_ACTIVE = Color.White
private val OVERLAY_PERFECT = Color(0xFF4CAF50)          // Green
private val OVERLAY_GREAT = Color(0xFF81C784)            // Light green
private val OVERLAY_GOOD = Color(0xFFFFC107)             // Yellow
private val OVERLAY_OK = Color(0xFFFF9800)               // Orange
private val OVERLAY_MISS = Color(0xFFF44336)             // Red

private const val BAR_GAP_PX = 2f
private const val INDICATOR_HEIGHT_PX = 8f

/**
 * Melody play canvas with piano at the bottom and falling notes.
 * Notes fall from top toward the piano keys at the bottom.
 * The "hit zone" is right at the top edge of the piano.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun MelodyPlayCanvas(
    state: MelodyPlayState,
    melodyTrack: MelodyTrack,
    detectedMidiNote: Int?,
    detectedCentsOff: Float?,
    rightPaddingPx: Float = 0f,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val pianoHeight = PIANO_HEIGHT_DP * density

        // Piano at bottom — hit zone is top edge of piano
        val hitZoneY = height - pianoHeight
        val canvasTop = 0f

        // Extend viewMaxMidi for right padding (same pattern as FreePlayCanvas)
        val effectiveViewMaxMidi = if (rightPaddingPx > 0f && width > rightPaddingPx) {
            val contentWidth = width - rightPaddingPx
            val midiPerPx = (state.viewMaxMidi - state.viewMinMidi) / contentWidth
            state.viewMaxMidi + rightPaddingPx * midiPerPx
        } else {
            state.viewMaxMidi
        }

        val layout = calculatePianoLayout(state.viewMinMidi, effectiveViewMaxMidi, width)
        val minMidi = floor(state.viewMinMidi).toInt()
        val maxMidi = ceil(effectiveViewMaxMidi).toInt()

        // Pixels per second for note positioning
        val pixelsPerSecond = state.fallSpeedPxPerSec * density

        // The effective time to render (song time + scroll offset)
        val viewTimeSeconds = state.currentTimeSeconds + state.songTimeOffsetSeconds

        // Draw background
        drawRect(CANVAS_BG)

        // Draw black-key column backgrounds
        for (midi in minMidi..maxMidi) {
            val key = layout[midi] ?: continue
            if (key.isBlack) {
                drawRect(
                    color = BLACK_COLUMN_BG,
                    topLeft = Offset(key.x, canvasTop),
                    size = Size(key.width, hitZoneY)
                )
            }
        }

        // Draw subtle grid lines at white key boundaries
        for (midi in minMidi..maxMidi) {
            val key = layout[midi] ?: continue
            if (!key.isBlack) {
                drawLine(
                    color = Color(0x18FFFFFF),
                    start = Offset(key.x, canvasTop),
                    end = Offset(key.x, hitZoneY),
                    strokeWidth = 1f
                )
            }
        }

        // Draw melody notes
        val minGapPx = 4f * density  // minimum 4dp gap between consecutive same-pitch notes
        for ((index, note) in melodyTrack.notes.withIndex()) {
            val noteStartTime = melodyTrack.noteStartSeconds(note)
            val noteDuration = melodyTrack.noteDurationSeconds(note)
            val noteEndTime = noteStartTime + noteDuration

            val relativeStart = noteStartTime - viewTimeSeconds
            val relativeEnd = noteEndTime - viewTimeSeconds

            var noteBottomY = hitZoneY - (relativeStart * pixelsPerSecond).toFloat()
            var noteTopY = hitZoneY - (relativeEnd * pixelsPerSecond).toFloat()

            // Enforce minimum gap with adjacent same-pitch notes
            if (index > 0 && melodyTrack.notes[index - 1].noteNumber == note.noteNumber) {
                noteBottomY -= minGapPx / 2f
            }
            if (index < melodyTrack.notes.size - 1 && melodyTrack.notes[index + 1].noteNumber == note.noteNumber) {
                noteTopY += minGapPx / 2f
            }

            val noteHeight = (noteBottomY - noteTopY).coerceAtLeast(4f)

            // Skip off-screen notes (above canvas or behind piano)
            if (noteTopY > hitZoneY + 100f || noteBottomY < -100f) continue

            val key = layout[note.noteNumber] ?: continue
            val barX = key.x + BAR_GAP_PX
            val barW = key.width - BAR_GAP_PX * 2f
            if (barW < 2f) continue

            // Clip note to the visible area (above piano)
            val clippedTop = noteTopY.coerceAtLeast(canvasTop)
            val clippedBottom = noteBottomY.coerceAtMost(hitZoneY)
            val clippedHeight = clippedBottom - clippedTop
            if (clippedHeight < 1f) continue

            // Determine color
            val noteColor = getNoteColor(index, state, relativeStart)

            // Pill-shaped corners
            val cornerRadius = CornerRadius(
                kotlin.math.min(barW, clippedHeight) / 2f,
                kotlin.math.min(barW, clippedHeight) / 2f
            )

            drawRoundRect(
                color = noteColor,
                topLeft = Offset(barX, clippedTop),
                size = Size(barW, clippedHeight),
                cornerRadius = cornerRadius
            )

            // Draw border
            drawRoundRect(
                color = Color.White.copy(alpha = 0.15f),
                topLeft = Offset(barX, clippedTop),
                size = Size(barW, clippedHeight),
                cornerRadius = cornerRadius,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
            )
        }

        // Draw pitch trace (player performance overlay)
        drawPitchTrace(state, layout, hitZoneY, pixelsPerSecond, viewTimeSeconds, canvasTop, density)

        // Draw player pitch indicator (thin bar just above piano)
        if (detectedMidiNote != null) {
            val key = layout[detectedMidiNote]
            if (key != null) {
                val indicatorColor = getIndicatorColor(detectedMidiNote, detectedCentsOff, melodyTrack, viewTimeSeconds)
                val indicatorX = key.x + BAR_GAP_PX
                val indicatorW = key.width - BAR_GAP_PX * 2f
                val indicatorY = hitZoneY - INDICATOR_HEIGHT_PX - 2f

                drawRoundRect(
                    color = indicatorColor,
                    topLeft = Offset(indicatorX, indicatorY),
                    size = Size(indicatorW, INDICATOR_HEIGHT_PX),
                    cornerRadius = CornerRadius(2f * density, 2f * density)
                )
            }
        }

        // Draw piano keyboard at bottom
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

private fun getNoteColor(
    noteIndex: Int,
    state: MelodyPlayState,
    relativeStart: Double
): Color {
    // Check note result state for active highlighting only
    val result = state.noteResults[noteIndex]
    if (result == NoteResultState.ACTIVE) {
        return NOTE_COLOR_ACTIVE
    }

    // All other notes: upcoming purple color
    return NOTE_COLOR_UPCOMING
}

private fun DrawScope.drawPitchTrace(
    state: MelodyPlayState,
    layout: Map<Int, KeyLayout>,
    hitZoneY: Float,
    pixelsPerSecond: Float,
    viewTimeSeconds: Double,
    canvasTop: Float,
    density: Float
) {
    val trace = state.pitchTrace
    if (trace.isEmpty()) return

    val frameDuration = 0.093

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

        val startTime = trace[segStart].timeSeconds
        val endTime = trace[segEnd].timeSeconds + frameDuration

        val relativeStart = startTime - viewTimeSeconds
        val relativeEnd = endTime - viewTimeSeconds

        val noteBottomY = hitZoneY - (relativeStart * pixelsPerSecond).toFloat()
        val noteTopY = hitZoneY - (relativeEnd * pixelsPerSecond).toFloat()

        // Skip off-screen segments
        if (noteTopY > hitZoneY + 100f || noteBottomY < -100f) {
            segStart = segEnd + 1
            continue
        }

        // Narrower bar: 60% of key width, centered
        val traceWidth = key.width * 0.6f
        val traceX = key.x + (key.width - traceWidth) / 2f

        // Clip to visible area
        val clippedTop = noteTopY.coerceAtLeast(canvasTop)
        val clippedBottom = noteBottomY.coerceAtMost(hitZoneY)
        if (clippedBottom - clippedTop < 1f) {
            segStart = segEnd + 1
            continue
        }
        val clippedHeight = (clippedBottom - clippedTop).coerceAtLeast(2f * density)

        // Compute average centsOff for the segment
        var centsSum = 0f
        for (i in segStart..segEnd) {
            centsSum += abs(trace[i].centsOff)
        }
        val avgCentsOff = centsSum / (segEnd - segStart + 1)

        // Map to color based on accuracy thresholds
        val baseColor = when {
            avgCentsOff <= 5f -> OVERLAY_PERFECT
            avgCentsOff <= 10f -> OVERLAY_GREAT
            avgCentsOff <= 30f -> OVERLAY_GOOD
            avgCentsOff <= 50f -> OVERLAY_OK
            else -> OVERLAY_MISS
        }

        val cornerRadius = CornerRadius(3f * density, 3f * density)

        // Draw the trace bar (semi-transparent)
        drawRoundRect(
            color = baseColor.copy(alpha = 0.75f),
            topLeft = Offset(traceX, clippedTop),
            size = Size(traceWidth, clippedHeight),
            cornerRadius = cornerRadius
        )

        // Draw subtle white border
        drawRoundRect(
            color = Color.White.copy(alpha = 0.25f),
            topLeft = Offset(traceX, clippedTop),
            size = Size(traceWidth, clippedHeight),
            cornerRadius = cornerRadius,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
        )

        segStart = segEnd + 1
    }
}

/**
 * Color the player's pitch indicator based on proximity to expected note.
 */
private fun getIndicatorColor(
    detectedMidi: Int,
    centsOff: Float?,
    melodyTrack: MelodyTrack,
    viewTimeSeconds: Double
): Color {
    // Find the expected note at the current time
    val expectedNote = melodyTrack.getNoteAtTime(viewTimeSeconds)
    if (expectedNote == null) return Color.White.copy(alpha = 0.5f)

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
