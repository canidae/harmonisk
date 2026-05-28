package com.perfectpitch.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.floor

private val NOTE_NAMES = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

const val PIANO_HEIGHT_DP = 52f
private const val BLACK_KEY_HEIGHT_FRACTION = 0.62f
private const val BLACK_KEY_WIDTH_FRACTION = 0.6f
private const val C4_MIDI = 60
private const val C4_DOT_RADIUS_DP = 2.5f

val BLACK_KEY_INDICES = setOf(1, 3, 6, 8, 10)
private val WHITE_KEY_INDICES = setOf(0, 2, 4, 5, 7, 9, 11)

data class KeyLayout(
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
fun calculatePianoLayout(
    viewMinMidi: Float,
    viewMaxMidi: Float,
    totalWidth: Float
): Map<Int, KeyLayout> {
    val result = mutableMapOf<Int, KeyLayout>()

    val minMidi = (floor(viewMinMidi).toInt() - 2).coerceAtLeast(21)
    val maxMidi = (ceil(viewMaxMidi).toInt() + 2).coerceAtMost(108)

    val midiRange = viewMaxMidi - viewMinMidi
    val whiteKeysInView = midiRange * 7f / 12f
    if (whiteKeysInView < 0.5f) return result

    val whiteKeyWidth = totalWidth / whiteKeysInView
    val blackKeyWidth = whiteKeyWidth * BLACK_KEY_WIDTH_FRACTION

    val whiteKeyOffsetInOctave = intArrayOf(0, -1, 1, -1, 2, 3, -1, 4, -1, 5, -1, 6)

    fun whiteKeyIndex(midi: Int): Int {
        val octave = midi / 12
        val note = midi % 12
        val offset = whiteKeyOffsetInOctave[note]
        return if (offset >= 0) octave * 7 + offset else -1
    }

    val refMidiInt = floor(viewMinMidi).toInt()
    var refWhiteMidi = refMidiInt
    while (refWhiteMidi % 12 in BLACK_KEY_INDICES && refWhiteMidi >= minMidi) refWhiteMidi--
    val refWhiteIdx = whiteKeyIndex(refWhiteMidi)

    val semitonesFromRef = viewMinMidi - refWhiteMidi.toFloat()
    val refPixelOffset = semitonesFromRef * (7f / 12f) * whiteKeyWidth

    for (midi in minMidi..maxMidi) {
        if (midi % 12 in BLACK_KEY_INDICES) continue
        val idx = whiteKeyIndex(midi)
        if (idx < 0) continue
        val x = (idx - refWhiteIdx).toFloat() * whiteKeyWidth - refPixelOffset
        result[midi] = KeyLayout(x = x, width = whiteKeyWidth, isBlack = false)
    }

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

/**
 * Map a fractional MIDI value (e.g., 60.3 = C4 + 30 cents) to a pixel X position
 * by interpolating between the center positions of adjacent keys in the layout.
 * Returns null if the key layout doesn't contain the needed keys.
 */
fun midiToPixelX(layout: Map<Int, KeyLayout>, fractionalMidi: Float): Float? {
    val lowerMidi = kotlin.math.floor(fractionalMidi).toInt()
    val upperMidi = lowerMidi + 1
    val fraction = fractionalMidi - lowerMidi

    val lowerKey = layout[lowerMidi]
    val upperKey = layout[upperMidi]

    if (lowerKey != null && upperKey != null) {
        val lowerCenterX = lowerKey.x + lowerKey.width / 2f
        val upperCenterX = upperKey.x + upperKey.width / 2f
        return lowerCenterX + (upperCenterX - lowerCenterX) * fraction
    }
    // Fall back to whichever key we have
    lowerKey?.let { return it.x + it.width / 2f }
    upperKey?.let { return it.x + it.width / 2f }
    return null
}



@OptIn(ExperimentalTextApi::class)
fun DrawScope.drawPianoKeyboard(
    textMeasurer: TextMeasurer,
    layout: Map<Int, KeyLayout>,
    pianoHeight: Float,
    minMidi: Int,
    maxMidi: Int,
    pianoAtBottom: Boolean = false
) {
    val blackKeyHeight = pianoHeight * BLACK_KEY_HEIGHT_FRACTION
    val cornerRadius = CornerRadius(4f * density, 4f * density)
    val pianoTop = if (pianoAtBottom) size.height - pianoHeight else 0f

    // Background behind piano
    drawRect(
        color = Color(0xFF1A1A2E),
        topLeft = Offset(0f, pianoTop),
        size = Size(size.width, pianoHeight)
    )

    // Draw white keys first
    for (midi in minMidi..maxMidi) {
        val key = layout[midi] ?: continue
        if (key.isBlack) continue

        drawRoundRect(
            color = Color(0xFFF5F5F5),
            topLeft = Offset(key.x + 0.5f, pianoTop + 1f),
            size = Size(key.width - 1f, pianoHeight - 3f),
            cornerRadius = cornerRadius
        )

        drawLine(
            color = Color(0xFFAAAAAA),
            start = Offset(key.x, pianoTop),
            end = Offset(key.x, pianoTop + pianoHeight),
            strokeWidth = 1f
        )

        // Label on white key
        val noteIndex = midi % 12
        val octave = midi / 12 - 1
        val noteName = NOTE_NAMES[noteIndex]
        val label = if (key.width > 35f * density) "$noteName$octave" else noteName
        val fontSize = (key.width / density * 0.3f).coerceIn(7f, 13f)

        var labelTopY = pianoTop + pianoHeight - 4f * density

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
                labelTopY = pianoTop + pianoHeight - textLayout.size.height - 4f * density
                drawText(textLayout, topLeft = Offset(textX, labelTopY))
            }
        }

        // C4 indicator dot
        if (midi == C4_MIDI) {
            val dotRadius = C4_DOT_RADIUS_DP * density
            val dotCenterX = key.x + key.width / 2f
            val dotCenterY = labelTopY - dotRadius - 2f * density
            drawCircle(
                color = Color.Black,
                radius = dotRadius,
                center = Offset(dotCenterX, dotCenterY)
            )
        }
    }

    // Draw black keys on top
    for (midi in minMidi..maxMidi) {
        val key = layout[midi] ?: continue
        if (!key.isBlack) continue

        val blackKeyTop = if (pianoAtBottom) pianoTop + pianoHeight - blackKeyHeight else pianoTop

        drawRoundRect(
            color = Color(0xFF1A1A1A),
            topLeft = Offset(key.x + 0.5f, blackKeyTop),
            size = Size(key.width - 1f, blackKeyHeight),
            cornerRadius = cornerRadius
        )

        drawRect(
            color = Color(0xFF3A3A3A),
            topLeft = Offset(key.x + 2f, blackKeyTop),
            size = Size(key.width - 4f, 2.5f * density)
        )

        drawLine(
            color = Color(0xFF000000),
            start = Offset(key.x + 2f, blackKeyTop + blackKeyHeight - 1f),
            end = Offset(key.x + key.width - 2f, blackKeyTop + blackKeyHeight - 1f),
            strokeWidth = 2f
        )

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
                val textY = blackKeyTop + blackKeyHeight - textLayout.size.height - 3f * density
                drawText(textLayout, topLeft = Offset(textX, textY))
            }
        }
    }

    // Edge line
    val edgeY = if (pianoAtBottom) pianoTop else pianoTop + pianoHeight - 1f
    drawLine(
        color = Color(0xFF333344),
        start = Offset(0f, edgeY),
        end = Offset(size.width, edgeY),
        strokeWidth = 2f
    )
}
