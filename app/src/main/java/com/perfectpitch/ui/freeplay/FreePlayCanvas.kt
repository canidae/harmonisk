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
import androidx.compose.ui.text.rememberTextMeasurer
import com.perfectpitch.ui.components.*
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ceil

private val GREEN_IN_TUNE = Color(0xFF4CAF50)
private val YELLOW_CLOSE = Color(0xFFFFC107)
private val RED_OFF = Color(0xFFF44336)
private val BAR_BORDER_COLOR = Color(0x40FFFFFF)
private val BLACK_COLUMN_BG = Color(0xFF151520)
private val CANVAS_BG = Color(0xFF0D0D1A)

private const val BAR_GAP_PX = 2f
private const val CENTS_THRESHOLD_PERFECT = 10f
private const val CENTS_THRESHOLD_CLOSE = 25f

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
