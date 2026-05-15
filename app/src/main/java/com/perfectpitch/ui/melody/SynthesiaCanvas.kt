package com.perfectpitch.ui.melody

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.perfectpitch.midi.MelodyTrack
import com.perfectpitch.midi.NoteEvent
import com.perfectpitch.music.NoteUtils
import com.perfectpitch.ui.theme.*

/**
 * Synthesia-style falling-notes Canvas.
 * Notes fall from top to bottom. The "hit zone" is near the bottom.
 * X-axis = pitch lanes, Y-axis = time (scrolling).
 */
@Composable
fun SynthesiaCanvas(
    melodyTrack: MelodyTrack,
    currentTimeSeconds: Double,
    noteResults: Map<Int, NoteResultState>,
    detectedMidiNote: Int?,
    visibleWindowSeconds: Double = 4.0,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val noteUtils = NoteUtils()

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        val hitZoneY = size.height * 0.85f
        val hitZoneHeight = 4f
        val noteRange = melodyTrack.noteRange
        val noteSpan = (noteRange.last - noteRange.first + 1).coerceAtLeast(12)
        val laneWidth = size.width / (noteSpan + 2) // +2 for padding
        val pixelsPerSecond = (hitZoneY) / visibleWindowSeconds

        // Draw lane lines (subtle grid)
        for (i in 0..noteSpan) {
            val x = (i + 1) * laneWidth
            drawLine(
                color = Color.White.copy(alpha = 0.05f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1f
            )
        }

        // Draw hit zone line
        drawRect(
            color = Color.White.copy(alpha = 0.3f),
            topLeft = Offset(0f, hitZoneY),
            size = Size(size.width, hitZoneHeight)
        )

        // Draw notes
        for ((index, note) in melodyTrack.notes.withIndex()) {
            val noteStartTime = melodyTrack.noteStartSeconds(note)
            val noteDuration = melodyTrack.noteDurationSeconds(note)

            // Calculate Y position: note arrives at hit zone when currentTime = noteStartTime
            val relativeTime = noteStartTime - currentTimeSeconds
            val noteTopY = hitZoneY - ((relativeTime + noteDuration) * pixelsPerSecond).toFloat()
            val noteBottomY = hitZoneY - (relativeTime * pixelsPerSecond).toFloat()
            val noteHeight = (noteBottomY - noteTopY).coerceAtLeast(4f)

            // Skip notes completely off-screen
            if (noteBottomY < -100f || noteTopY > size.height + 100f) continue

            // Calculate X position based on pitch
            val laneIndex = note.noteNumber - noteRange.first + 1
            val noteX = laneIndex * laneWidth
            val noteWidth = laneWidth * 0.85f

            // Determine color based on state
            val noteColor = when (noteResults[index]) {
                NoteResultState.PERFECT -> Green80
                NoteResultState.GOOD -> Green80.copy(alpha = 0.7f)
                NoteResultState.FAIR -> Yellow80
                NoteResultState.MISS -> Red80.copy(alpha = 0.6f)
                NoteResultState.ACTIVE -> Color.White
                null -> {
                    // Upcoming note
                    if (relativeTime <= 0 && relativeTime + noteDuration > 0) {
                        Color.White // Currently in hit zone
                    } else {
                        Purple80.copy(alpha = 0.7f) // Upcoming
                    }
                }
            }

            // Draw note rectangle
            drawRoundRect(
                color = noteColor,
                topLeft = Offset(noteX, noteTopY),
                size = Size(noteWidth, noteHeight),
                cornerRadius = CornerRadius(4f, 4f)
            )

            // Draw note label if note is large enough
            if (noteHeight > 20f) {
                val noteName = noteUtils.midiToNoteName(note.noteNumber)
                val textResult = textMeasurer.measure(
                    text = noteName,
                    style = TextStyle(fontSize = 10.sp, color = DarkBackground)
                )
                val textX = noteX + (noteWidth - textResult.size.width) / 2
                val textY = noteTopY + (noteHeight - textResult.size.height) / 2
                if (textY > 0 && textY < size.height) {
                    drawText(
                        textLayoutResult = textResult,
                        topLeft = Offset(textX, textY)
                    )
                }
            }
        }

        // Draw detected note indicator (where the player is playing)
        if (detectedMidiNote != null && detectedMidiNote in noteRange) {
            val laneIndex = detectedMidiNote - noteRange.first + 1
            val indicatorX = laneIndex * laneWidth
            val indicatorWidth = laneWidth * 0.85f
            drawRoundRect(
                color = Teal80.copy(alpha = 0.6f),
                topLeft = Offset(indicatorX, hitZoneY - 8f),
                size = Size(indicatorWidth, 16f),
                cornerRadius = CornerRadius(4f, 4f)
            )
        }
    }
}

enum class NoteResultState {
    ACTIVE, PERFECT, GOOD, FAIR, MISS
}
