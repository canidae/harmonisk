package com.perfectpitch.ui.freeplay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ceil

private val NOTE_NAMES = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

private val GREEN_IN_TUNE = Color(0xFF4CAF50)
private val YELLOW_CLOSE = Color(0xFFFFC107)
private val RED_OFF = Color(0xFFF44336)
private val BAR_BORDER_COLOR = Color(0x40FFFFFF)
private val BLACK_COLUMN_BG = Color(0xFF151520)
private val CANVAS_BG = Color(0xFF0D0D1A)
private val C4_DOT_COLOR = Color(0xFF999999)

internal const val PIANO_HEIGHT_DP = 52f
private const val BLACK_KEY_HEIGHT_FRACTION = 0.62f
private const val BLACK_KEY_WIDTH_FRACTION = 0.6f
private const val BAR_GAP_PX = 2f
private const val CENTS_THRESHOLD_PERFECT = 10f
private const val CENTS_THRESHOLD_CLOSE = 25f
private const val C4_MIDI = 60
private const val C4_DOT_RADIUS_DP = 2.5f

// Black key semitone indices within an octave
private val BLACK_KEY_INDICES = setOf(1, 3, 6, 8, 10)

// White key semitone indices
private val WHITE_KEY_INDICES = setOf(0, 2, 4, 5, 7, 9, 11)

/**
 * Piano layout helper: calculates the X position and width of each MIDI note
 * using proper piano proportions (white keys are full-width, black keys are narrower
 * and positioned between white keys).
 */
private data class KeyLayout(
    val x: Float,
    val width: Float,
    val isBlack: Boolean
)

/**
 * Calculate piano-proportional layout for a range of MIDI notes.
 * Returns a map of MIDI number -> KeyLayout.
 * 
 * Uses a stable calculation: white key width is derived from the MIDI range
 * divided by the average density of white keys (7/12), avoiding jumps when
 * the view boundary crosses a key.
 */
private fun calculatePianoLayout(
    viewMinMidi: Float,
    viewMaxMidi: Float,
    totalWidth: Float
): Map<Int, KeyLayout> {
    val result = mutableMapOf<Int, KeyLayout>()

    // Extend range by 2 keys on each side for smooth edges
    val minMidi = (floor(viewMinMidi).toInt() - 2).coerceAtLeast(21)
    val maxMidi = (ceil(viewMaxMidi).toInt() + 2).coerceAtMost(108)

    // Stable key width: use the continuous MIDI range and average white key density
    // In any 12-semitone span there are 7 white keys, so white keys per semitone = 7/12
    val midiRange = viewMaxMidi - viewMinMidi
    val whiteKeysInView = midiRange * 7f / 12f
    if (whiteKeysInView < 0.5f) return result

    val whiteKeyWidth = totalWidth / whiteKeysInView
    val blackKeyWidth = whiteKeyWidth * BLACK_KEY_WIDTH_FRACTION

    // Position white keys using a direct formula instead of counting from 0.
    // White keys per octave = 7. Within an octave (semitones 0-11):
    // C=0(w0), D=2(w1), E=4(w2), F=5(w3), G=7(w4), A=9(w5), B=11(w6)
    val whiteKeyOffsetInOctave = intArrayOf(0, -1, 1, -1, 2, 3, -1, 4, -1, 5, -1, 6)

    fun whiteKeyIndex(midi: Int): Int {
        val octave = midi / 12
        val note = midi % 12
        // Count white keys in all complete octaves + offset within this octave
        val offset = whiteKeyOffsetInOctave[note]
        return if (offset >= 0) octave * 7 + offset else -1
    }

    // Find the white key index for viewMinMidi (use floor, find nearest white key)
    val refMidiInt = floor(viewMinMidi).toInt()
    var refWhiteMidi = refMidiInt
    while (refWhiteMidi % 12 in BLACK_KEY_INDICES && refWhiteMidi >= minMidi) refWhiteMidi--
    val refWhiteIdx = whiteKeyIndex(refWhiteMidi)

    // Pixel offset: how many semitones from refWhiteMidi to viewMinMidi, converted to px
    // Since white keys are evenly spaced, we measure offset in "white key fractions"
    // The distance from refWhiteMidi to viewMinMidi in semitones
    val semitonesFromRef = viewMinMidi - refWhiteMidi.toFloat()
    // On average 7 white keys per 12 semitones, so semitone → white key fraction ≈ 7/12
    val refPixelOffset = semitonesFromRef * (7f / 12f) * whiteKeyWidth

    // Position each white key
    for (midi in minMidi..maxMidi) {
        if (midi % 12 in BLACK_KEY_INDICES) continue
        val idx = whiteKeyIndex(midi)
        if (idx < 0) continue
        val x = (idx - refWhiteIdx).toFloat() * whiteKeyWidth - refPixelOffset
        result[midi] = KeyLayout(x = x, width = whiteKeyWidth, isBlack = false)
    }

    // Position black keys between their adjacent white keys
    for (midi in minMidi..maxMidi) {
        if (midi % 12 !in BLACK_KEY_INDICES) continue
        val prevWhite = result[midi - 1]
        val nextWhite = result[midi + 1]

        val x = when {
            prevWhite != null && nextWhite != null -> {
                prevWhite.x + prevWhite.width - blackKeyWidth / 2f
            }
            prevWhite != null -> prevWhite.x + prevWhite.width - blackKeyWidth / 2f
            nextWhite != null -> nextWhite.x - blackKeyWidth / 2f
            else -> continue
        }

        result[midi] = KeyLayout(x = x, width = blackKeyWidth, isBlack = true)
    }

    return result
}

@OptIn(ExperimentalTextApi::class)
@Composable
fun FreePlayCanvas(
    state: FreePlayState,
    currentTimeMs: Long = System.currentTimeMillis(),
    rightPaddingPx: Float = 0f,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val pianoHeight = PIANO_HEIGHT_DP * density
        val canvasTop = pianoHeight
        val now = currentTimeMs - state.timeOffsetMs

        // Extend viewMaxMidi so the content range fits in (width - rightPaddingPx)
        val effectiveViewMaxMidi = if (rightPaddingPx > 0f && width > rightPaddingPx) {
            val contentWidth = width - rightPaddingPx
            val midiPerPx = (state.viewMaxMidi - state.viewMinMidi) / contentWidth
            state.viewMaxMidi + rightPaddingPx * midiPerPx
        } else {
            state.viewMaxMidi
        }

        // Calculate piano layout
        val layout = calculatePianoLayout(state.viewMinMidi, effectiveViewMaxMidi, width)

        // Draw background
        drawRect(CANVAS_BG)

        // Draw black-key column backgrounds (darker shade)
        val minMidi = floor(state.viewMinMidi).toInt()
        val maxMidi = ceil(effectiveViewMaxMidi).toInt()

        for (midi in minMidi..maxMidi) {
            val key = layout[midi] ?: continue
            if (key.isBlack) {
                drawRect(
                    color = BLACK_COLUMN_BG,
                    topLeft = Offset(key.x, canvasTop),
                    size = Size(key.width, height - canvasTop)
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
                    end = Offset(key.x, height),
                    strokeWidth = 1f
                )
            }
        }

        // Draw played notes (falling bars)
        val allNotes = buildList {
            addAll(state.playedNotes)
            state.activeNote?.let { add(it) }
        }

        for (note in allNotes) {
            val key = layout[note.midiNumber] ?: continue
            drawNoteBar(
                note = note,
                now = now,
                canvasTop = canvasTop,
                keyLayout = key,
                fallSpeed = state.fallSpeedPxPerSec * density
            )
        }

        // Draw piano keyboard at top (on top of everything else)
        drawPianoKeyboard(
            textMeasurer = textMeasurer,
            layout = layout,
            pianoHeight = pianoHeight,
            minMidi = minMidi,
            maxMidi = maxMidi
        )
    }
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawPianoKeyboard(
    textMeasurer: TextMeasurer,
    layout: Map<Int, KeyLayout>,
    pianoHeight: Float,
    minMidi: Int,
    maxMidi: Int
) {
    val blackKeyHeight = pianoHeight * BLACK_KEY_HEIGHT_FRACTION
    val cornerRadius = CornerRadius(4f * density, 4f * density)

    // Background behind piano
    drawRect(
        color = Color(0xFF1A1A2E),
        topLeft = Offset(0f, 0f),
        size = Size(size.width, pianoHeight)
    )

    // Draw white keys first
    for (midi in minMidi..maxMidi) {
        val key = layout[midi] ?: continue
        if (key.isBlack) continue

        // White key body
        drawRoundRect(
            color = Color(0xFFF5F5F5),
            topLeft = Offset(key.x + 0.5f, 1f),
            size = Size(key.width - 1f, pianoHeight - 3f),
            cornerRadius = cornerRadius
        )

        // Key border
        drawLine(
            color = Color(0xFFAAAAAA),
            start = Offset(key.x, 0f),
            end = Offset(key.x, pianoHeight),
            strokeWidth = 1f
        )

        // Label on white key
        val noteIndex = midi % 12
        val octave = midi / 12 - 1
        val noteName = NOTE_NAMES[noteIndex]
        val label = if (key.width > 35f * density) "$noteName$octave" else noteName
        val fontSize = (key.width / density * 0.3f).coerceIn(7f, 13f)

        if (key.width > 12f * density) {
            val textLayout = textMeasurer.measure(
                text = AnnotatedString(label),
                style = TextStyle(
                    color = Color(0xFF222222),
                    fontSize = fontSize.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            if (textLayout.size.width < key.width - 4f) {
                val textX = key.x + (key.width - textLayout.size.width) / 2f
                val textY = pianoHeight - textLayout.size.height - 4f * density
                drawText(textLayout, topLeft = Offset(textX, textY))
            }
        }

        // C4 (middle C) indicator dot
        if (midi == C4_MIDI) {
            val dotRadius = C4_DOT_RADIUS_DP * density
            val dotCenterX = key.x + key.width / 2f
            val dotCenterY = pianoHeight * 0.45f
            drawCircle(
                color = C4_DOT_COLOR,
                radius = dotRadius,
                center = Offset(dotCenterX, dotCenterY)
            )
        }
    }

    // Draw black keys on top
    for (midi in minMidi..maxMidi) {
        val key = layout[midi] ?: continue
        if (!key.isBlack) continue

        // Black key body
        drawRoundRect(
            color = Color(0xFF1A1A1A),
            topLeft = Offset(key.x + 0.5f, 0f),
            size = Size(key.width - 1f, blackKeyHeight),
            cornerRadius = cornerRadius
        )

        // Subtle highlight
        drawRect(
            color = Color(0xFF3A3A3A),
            topLeft = Offset(key.x + 2f, 0f),
            size = Size(key.width - 4f, 2.5f * density)
        )

        // Shadow at bottom of black key
        drawLine(
            color = Color(0xFF000000),
            start = Offset(key.x + 2f, blackKeyHeight - 1f),
            end = Offset(key.x + key.width - 2f, blackKeyHeight - 1f),
            strokeWidth = 2f
        )

        // Label on black key (only if wide enough)
        if (key.width > 22f * density) {
            val noteIndex = midi % 12
            val noteName = NOTE_NAMES[noteIndex]
            val fontSize = (key.width / density * 0.28f).coerceIn(7f, 10f)
            val textLayout = textMeasurer.measure(
                text = AnnotatedString(noteName),
                style = TextStyle(
                    color = Color(0xFFCCCCCC),
                    fontSize = fontSize.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            if (textLayout.size.width < key.width - 4f) {
                val textX = key.x + (key.width - textLayout.size.width) / 2f
                val textY = blackKeyHeight - textLayout.size.height - 3f * density
                drawText(textLayout, topLeft = Offset(textX, textY))
            }
        }
    }

    // Bottom edge
    drawLine(
        color = Color(0xFF333344),
        start = Offset(0f, pianoHeight - 1f),
        end = Offset(size.width, pianoHeight - 1f),
        strokeWidth = 2f
    )
}

private fun DrawScope.drawNoteBar(
    note: PlayedNote,
    now: Long,
    canvasTop: Float,
    keyLayout: KeyLayout,
    fallSpeed: Float
) {
    val pxPerMs = fallSpeed / 1000f

    val noteEndMs = note.endTimeMs ?: now
    val ageOfEndMs = now - noteEndMs
    val ageOfStartMs = now - note.startTimeMs

    val barTop = canvasTop + ageOfEndMs * pxPerMs
    val barBottom = canvasTop + ageOfStartMs * pxPerMs

    if (barTop > size.height || barBottom < canvasTop) return

    val clippedTop = barTop.coerceAtLeast(canvasTop)
    val clippedBottom = barBottom.coerceAtMost(size.height)
    val barHeight = clippedBottom - clippedTop

    if (barHeight < 1f) return

    val barX = keyLayout.x + BAR_GAP_PX
    val barW = keyLayout.width - BAR_GAP_PX * 2f

    if (barW < 2f) return

    // Color based on accuracy — darken slightly for black-key notes
    var barColor = accuracyColor(note.averageCentsOff)
    if (keyLayout.isBlack) {
        barColor = barColor.copy(
            red = barColor.red * 0.75f,
            green = barColor.green * 0.75f,
            blue = barColor.blue * 0.75f
        )
    }

    // Draw the bar
    drawRoundRect(
        color = barColor,
        topLeft = Offset(barX, clippedTop),
        size = Size(barW, barHeight),
        cornerRadius = CornerRadius(3f * density, 3f * density)
    )

    // Draw border
    drawRoundRect(
        color = BAR_BORDER_COLOR,
        topLeft = Offset(barX, clippedTop),
        size = Size(barW, barHeight),
        cornerRadius = CornerRadius(3f * density, 3f * density),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
    )

    // Draw pitch accuracy gradient line
    if (barHeight > 6f && barW > 8f) {
        drawPitchLine(
            barX = barX,
            barW = barW,
            barTop = clippedTop,
            barHeight = barHeight,
            centsOff = note.averageCentsOff
        )
    }
}

private fun DrawScope.drawPitchLine(
    barX: Float,
    barW: Float,
    barTop: Float,
    barHeight: Float,
    centsOff: Float
) {
    val normalized = (centsOff / 50f).coerceIn(-1f, 1f)
    val lineX = barX + barW / 2f + normalized * (barW / 2f - 2f)

    val gradientWidth = barW * 0.3f
    val brush = Brush.horizontalGradient(
        colors = listOf(
            Color.Transparent,
            Color.White.copy(alpha = 0.12f),
            Color.White.copy(alpha = 0.85f),
            Color.White.copy(alpha = 0.12f),
            Color.Transparent
        ),
        startX = lineX - gradientWidth / 2f,
        endX = lineX + gradientWidth / 2f
    )

    drawRect(
        brush = brush,
        topLeft = Offset(barX, barTop),
        size = Size(barW, barHeight)
    )

    drawLine(
        color = Color.White.copy(alpha = 0.6f),
        start = Offset(lineX, barTop + 2f),
        end = Offset(lineX, barTop + barHeight - 2f),
        strokeWidth = 1.5f
    )
}

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

private fun lerpColor(start: Color, end: Color, fraction: Float): Color {
    return Color(
        red = start.red + (end.red - start.red) * fraction,
        green = start.green + (end.green - start.green) * fraction,
        blue = start.blue + (end.blue - start.blue) * fraction,
        alpha = 1f
    )
}
