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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import com.perfectpitch.midi.MelodyTrack
import com.perfectpitch.ui.freeplay.NoteEditMode
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
        val currentTimeMs: Long,
        val timeOffsetMs: Long,
        val fallSpeedPxPerSec: Float,
        val pitchTrace: List<PitchFrame> = emptyList(),
        val bpm: Float = 120f,
        val beatsPerBar: Int = 4,
        val activeVoiceIndex: Int = 0,
        val loopStartMs: Long? = null,
        val loopEndMs: Long? = null,
        val selectedNoteId: String? = null,
        val editMode: NoteEditMode = NoteEditMode.NONE
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
        val rect = Rect(
            left = key.x + BAR_GAP_PX,
            top = rawTopY,
            right = key.x + key.width - BAR_GAP_PX,
            bottom = rawBottomY
        )
        result.add(note.id to rect)
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
    continuousPitchLine: Boolean = true,
    rightPaddingPx: Float = 0f,
    onNoteRects: ((List<Pair<String, Rect>>) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density

    // Compute and emit note rects for edit mode hit testing
    if (onNoteRects != null && mode is CanvasMode.FreePlay && canvasSize != IntSize.Zero) {
        val width = canvasSize.width.toFloat()
        val pianoEdgeY = canvasSize.height / 2f
        val pxPerMs = mode.fallSpeedPxPerSec * density / 1000f
        val now = mode.currentTimeMs - mode.timeOffsetMs
        val effectiveViewMaxMidi = if (rightPaddingPx > 0f && width > rightPaddingPx) {
            val contentWidth = width - rightPaddingPx
            val midiPerPx = (viewMaxMidi - viewMinMidi) / contentWidth
            viewMaxMidi + rightPaddingPx * midiPerPx
        } else viewMaxMidi
        val layout = calculatePianoLayout(viewMinMidi, effectiveViewMaxMidi, width)
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

        // Piano edge and far edge Y coordinates
        val pianoEdgeY: Float
        val canvasFarEdgeY: Float
        if (mode is CanvasMode.FreePlay) {
            // FreePlay always shows centered piano: past notes below, future notes above
            pianoEdgeY = height / 2f
            canvasFarEdgeY = height
        } else if (pianoAtBottom) {
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
                val colTop = if (mode is CanvasMode.FreePlay) 0f else if (pianoAtBottom) canvasFarEdgeY else pianoEdgeY
                val colHeight = if (mode is CanvasMode.FreePlay) height else if (pianoAtBottom) pianoEdgeY else canvasFarEdgeY - pianoEdgeY
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
                val lineTop = if (mode is CanvasMode.FreePlay) 0f else if (pianoAtBottom) canvasFarEdgeY else pianoEdgeY
                val lineBottom = if (mode is CanvasMode.FreePlay) height else if (pianoAtBottom) pianoEdgeY else canvasFarEdgeY
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
                drawBarLines(mode, pianoEdgeY, canvasFarEdgeY, pianoAtBottom, density)
                drawFreePlayNotes(mode, layout, pianoEdgeY, canvasFarEdgeY, pianoAtBottom, density, continuousPitchLine)
                if (continuousPitchLine) {
                    val viewTimeMs = mode.currentTimeMs - mode.timeOffsetMs
                    drawContinuousPitchLine(mode.pitchTrace, viewTimeMs, mode.fallSpeedPxPerSec, layout, pianoEdgeY, canvasFarEdgeY, pianoAtBottom, density)
                }
            }
            is CanvasMode.Melody -> {
                drawBarLines(mode, pianoEdgeY, canvasFarEdgeY, pianoAtBottom, density)
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
        if (mode is CanvasMode.FreePlay) {
            val slimHeight = SLIM_PIANO_HEIGHT_DP * density
            drawCenteredPianoKeyboard(
                textMeasurer = textMeasurer,
                layout = layout,
                pianoHeight = slimHeight,
                minMidi = minMidi,
                maxMidi = maxMidi,
                centerY = pianoEdgeY
            )
        } else {
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

// region Bar/beat line rendering

private fun DrawScope.drawBarLines(
    mode: CanvasMode.FreePlay,
    pianoEdgeY: Float,
    canvasFarEdgeY: Float,
    pianoAtBottom: Boolean,
    density: Float
) {
    if (mode.bpm <= 0f) return
    val pxPerMs = mode.fallSpeedPxPerSec * density / 1000f
    if (pxPerMs <= 0f) return

    val now = (mode.currentTimeMs - mode.timeOffsetMs).toDouble()

    val beatDurationMs = 60_000.0 / mode.bpm

    val visibleTop = minOf(pianoEdgeY, canvasFarEdgeY)
    val visibleBottom = maxOf(pianoEdgeY, canvasFarEdgeY)

    val visibleRangeMs = (visibleBottom - visibleTop) / pxPerMs

    // Draw loop region if active
    val loopStartMs = mode.loopStartMs
    val loopEndMs = mode.loopEndMs
    if (loopStartMs != null && loopEndMs != null && loopEndMs > loopStartMs) {
        val loopBandTop = (pianoEdgeY + (now - loopEndMs) * pxPerMs).toFloat().coerceIn(visibleTop, visibleBottom)
        val loopBandBottom = (pianoEdgeY + (now - loopStartMs) * pxPerMs).toFloat().coerceIn(visibleTop, visibleBottom)
        if (loopBandBottom > loopBandTop) {
            drawRect(
                color = Color(0x33FFB74D),
                topLeft = Offset(0f, loopBandTop),
                size = Size(size.width, loopBandBottom - loopBandTop)
            )
            // Loop boundary lines
            if (loopBandTop >= visibleTop && loopBandTop <= visibleBottom) {
                drawLine(Color(0xAAFFB74D), Offset(0f, loopBandTop), Offset(size.width, loopBandTop), strokeWidth = 2f * density)
            }
            if (loopBandBottom >= visibleTop && loopBandBottom <= visibleBottom) {
                drawLine(Color(0xAAFFB74D), Offset(0f, loopBandBottom), Offset(size.width, loopBandBottom), strokeWidth = 2f * density)
            }
        }
    }

    val earliestTimeMs = now - visibleRangeMs * 1.1
    val firstBeatIndex = kotlin.math.floor(earliestTimeMs / beatDurationMs).toLong() - 1L

    var beatIndex = firstBeatIndex
    while (true) {
        val beatTimeMs = beatIndex * beatDurationMs
        val ageMs = now - beatTimeMs
        val y = (pianoEdgeY + ageMs * pxPerMs).toFloat()
        if (y < visibleTop - 2f) break
        if (y <= visibleBottom + 2f) {
            val isBar = beatIndex % mode.beatsPerBar == 0L
            drawLine(
                color = Color.White.copy(alpha = if (isBar) 0.20f else 0.08f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = if (isBar) 1.5f * density else 1f * density
            )
        }
        beatIndex++
        if (beatTimeMs > now + visibleRangeMs * 0.1) break
    }
}

private fun DrawScope.drawBarLines(
    mode: CanvasMode.Melody,
    pianoEdgeY: Float,
    canvasFarEdgeY: Float,
    pianoAtBottom: Boolean,
    density: Float
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

    data class BeatPoint(val seconds: Double, val isBarLine: Boolean)
    val beats = mutableListOf<BeatPoint>()

    while (currentSeconds < latestViewSeconds + 1.0 && currentSeconds <= songDurationSeconds + 1.0) {
        val tempoUs = if (tempoIndex < track.tempoChanges.size) {
            track.tempoChanges[tempoIndex].microsecondsPerBeat
        } else firstTempoUs
        val beatDurationSeconds = tempoUs / 1_000_000.0

        val timeSig = track.timeSignatureAt(currentTick)
        val beatsPerBarHere = timeSig.numerator

        if (currentSeconds >= earliestViewSeconds) {
            val isBar = beatsPerBarHere > 0 && (beatCount % beatsPerBarHere == 0L)
            beats.add(BeatPoint(currentSeconds, isBar))
        }

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

    for (beat in beats) {
        val relativeSeconds = beat.seconds - viewTimeSeconds
        val y = pianoEdgeY - (relativeSeconds * pixelsPerSecond).toFloat()
        if (y < visibleTop - 2f || y > visibleBottom + 2f) continue
        drawLine(
            color = Color.White.copy(alpha = if (beat.isBarLine) 0.20f else 0.08f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = if (beat.isBarLine) 1.5f * density else 1f * density
        )
    }
}

// endregion

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
    val handleRadius = 12f * density

    val allNotes = buildList {
        addAll(mode.notes)
        mode.activeNote?.let { add(it) }
    }

    for (note in allNotes) {
        val key = layout[note.midiNumber] ?: continue

        val noteEndMs = note.endTimeMs ?: mode.currentTimeMs
        val ageOfEndMs = now - noteEndMs
        val ageOfStartMs = now - note.startTimeMs

        val rawTopY: Float
        val rawBottomY: Float
        if (pianoAtBottom) {
            rawBottomY = pianoEdgeY - (ageOfEndMs * pxPerMs)
            rawTopY = pianoEdgeY - (ageOfStartMs * pxPerMs)
        } else {
            rawTopY = pianoEdgeY + ageOfEndMs * pxPerMs
            rawBottomY = pianoEdgeY + ageOfStartMs * pxPerMs
        }

        // Color based on voice
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
        // Dim upcoming (future) notes — they appear above the centered piano
        if (ageOfStartMs < 0) {
            barColor = barColor.copy(alpha = barColor.alpha * 0.45f)
        }

        val isSelected = note.id == mode.selectedNoteId
        val borderAlpha = if (isSelected) 0.9f else 0.25f

        drawNoteBar(
            barX = key.x + BAR_GAP_PX,
            barW = key.width - BAR_GAP_PX * 2f,
            rawTopY = rawTopY,
            rawBottomY = rawBottomY,
            pianoEdgeY = pianoEdgeY,
            canvasFarEdgeY = canvasFarEdgeY,
            pianoAtBottom = pianoAtBottom,
            color = barColor,
            borderAlpha = borderAlpha,
            centsHistory = if (continuousPitchLine) emptyList() else note.centsHistory,
            density = density,
            replayMode = true
        )

        // Draw selection highlight and handles
        if (isSelected && note.endTimeMs != null) {
            val centerX = key.x + key.width / 2f
            val visibleTop = minOf(pianoEdgeY, canvasFarEdgeY)
            val visibleBottom = maxOf(pianoEdgeY, canvasFarEdgeY)
            val clampedTopY = rawTopY.coerceIn(visibleTop, visibleBottom)
            val clampedBottomY = rawBottomY.coerceIn(visibleTop, visibleBottom)

            // End handle (top = more recent time)
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = handleRadius,
                center = Offset(centerX, clampedTopY)
            )
            drawCircle(
                color = barColor,
                radius = handleRadius - 3f * density,
                center = Offset(centerX, clampedTopY)
            )
            // Start handle (bottom = older time)
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = handleRadius,
                center = Offset(centerX, clampedBottomY)
            )
            drawCircle(
                color = barColor,
                radius = handleRadius - 3f * density,
                center = Offset(centerX, clampedBottomY)
            )
        }
    }
}

// endregion

// region Continuous pitch line (unified)

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
        if (rawTopY > visibleBottom + 100f || rawBottomY < visibleTop - 100f) continue

        val key = layout[note.noteNumber] ?: continue
        val barX = key.x + BAR_GAP_PX
        val barW = key.width - BAR_GAP_PX * 2f
        if (barW < 2f) continue

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

        val visibleTop = if (pianoAtBottom) canvasFarEdgeY else pianoEdgeY
        val visibleBottom = if (pianoAtBottom) pianoEdgeY else canvasFarEdgeY
        if (rawTopY > visibleBottom + 100f || rawBottomY < visibleTop - 100f) {
            segStart = segEnd + 1
            continue
        }

        val traceWidth = key.width * 0.6f
        val traceX = key.x + (key.width - traceWidth) / 2f

        val segmentCents = mutableListOf<Float>()
        for (i in segStart..segEnd) {
            segmentCents.add(trace[i].centsOff)
        }

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
    pianoEdgeY: Float,
    canvasFarEdgeY: Float,
    pianoAtBottom: Boolean,
    color: Color,
    borderAlpha: Float,
    centsHistory: List<Float>,
    density: Float,
    replayMode: Boolean = false
) {
    if (barW < 2f) return

    val visibleTop = if (replayMode) 0f else minOf(pianoEdgeY, canvasFarEdgeY)
    val visibleBottom = if (replayMode) size.height else maxOf(pianoEdgeY, canvasFarEdgeY)

    if (rawTopY > visibleBottom || rawBottomY < visibleTop) return

    val clippedTop = rawTopY.coerceIn(visibleTop, visibleBottom)
    val clippedBottom = rawBottomY.coerceIn(visibleTop, visibleBottom)
    val barHeight = clippedBottom - clippedTop

    if (barHeight < 1f) return

    val r = CORNER_RADIUS_DP * density

    val topIsPianoSide = !pianoAtBottom && !replayMode
    val bottomIsPianoSide = pianoAtBottom && !replayMode

    val topClippedAtPiano = topIsPianoSide && rawTopY <= pianoEdgeY
    val topR = if (topClippedAtPiano) 0f else r

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

    drawPath(path, color)
    drawPath(path, Color.White.copy(alpha = borderAlpha), style = Stroke(width = 1f))

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
