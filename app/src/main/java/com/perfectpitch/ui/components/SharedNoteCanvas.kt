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
import androidx.compose.ui.platform.LocalDensity
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
        val sessionStartMs: Long = 0L,
        val pianoVisibility: PianoVisibility = PianoVisibility.BOTH
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
    val density = LocalDensity.current.density

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

        // For FreePlay: compute playable-area bottom (before bar 1 = black, no columns/grid)
        val playableBottom: Float = if (mode is CanvasMode.FreePlay && mode.fallSpeedPxPerSec > 0f) {
            val pxPerMs = mode.fallSpeedPxPerSec * density / 1000f
            val now = (mode.currentTimeMs - mode.timeOffsetMs).toDouble()
            val bar1Y = (pianoEdgeY + (now - mode.sessionStartMs) * pxPerMs).toFloat()
            bar1Y.coerceIn(noteAreaTop, noteAreaBottom)
        } else noteAreaBottom

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
                drawFreePlayNotes(mode, layout, pianoEdgeY, noteAreaTop, noteAreaBottom, density, continuousPitchLine)
                if (continuousPitchLine) {
                    val viewTimeMs = mode.currentTimeMs - mode.timeOffsetMs
                    drawContinuousPitchLine(mode.pitchTrace, viewTimeMs, mode.fallSpeedPxPerSec, layout, pianoEdgeY, noteAreaTop, noteAreaBottom, density)
                }
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
                drawBarLines(mode, pianoEdgeY, canvasFarEdgeY, pianoAtBottom, density)
                drawMelodyNotes(mode, layout, pianoEdgeY, canvasFarEdgeY, pianoAtBottom, density)
                drawPitchTrace(mode, layout, pianoEdgeY, canvasFarEdgeY, pianoAtBottom, density, continuousPitchLine)
                if (continuousPitchLine) {
                    val viewTimeMs = ((mode.currentTimeSeconds + mode.songTimeOffsetSeconds) * 1000).toLong()
                    drawContinuousPitchLine(mode.pitchTrace, viewTimeMs, mode.fallSpeedPxPerSec, layout, pianoEdgeY, noteAreaTop, noteAreaBottom, density)
                }
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

    val now = (mode.currentTimeMs - mode.timeOffsetMs).toDouble()
    val beatDurationMs = 60_000.0 / mode.bpm
    val barDurationMs = mode.beatsPerBar * beatDurationMs

    val visibleTop = noteAreaTop
    val visibleBottom = noteAreaBottom

    // Compute how far back (past) we need to start
    val pastRangeMs = (noteAreaBottom - nowY) / pxPerMs

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

    val earliestTimeMs = now - pastRangeMs * 1.1
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
        if (beatTimeMs < mode.sessionStartMs.toDouble()) {
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
                val barNumber = ((beatTimeMs - mode.sessionStartMs.toDouble()) / barDurationMs).toLong() + 1
                val label = textMeasurer.measure(
                    text = buildAnnotatedString { append(barNumber.toString()) },
                    style = barLabelStyle
                )
                val labelY = (y + 2f * density).coerceAtMost(visibleBottom - label.size.height)
                if (labelY >= visibleTop) {
                    drawText(label, topLeft = Offset(3f * density, labelY))
                }
            }
        }
        beatIndex++
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
    noteAreaTop: Float,
    noteAreaBottom: Float,
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

        // Notes fall downward: past (positive age) → below now-line, future (negative age) → above
        val rawTopY = pianoEdgeY + ageOfEndMs * pxPerMs
        val rawBottomY = pianoEdgeY + ageOfStartMs * pxPerMs

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
        // Dim upcoming (future) notes — they appear above the now-line
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
            visibleTop = noteAreaTop,
            visibleBottom = noteAreaBottom,
            color = barColor,
            borderAlpha = borderAlpha,
            centsHistory = if (continuousPitchLine) emptyList() else note.centsHistory,
            density = density
        )

        // Draw selection highlight and handles
        if (isSelected && note.endTimeMs != null) {
            val centerX = key.x + key.width / 2f
            val clampedTopY = rawTopY.coerceIn(noteAreaTop, noteAreaBottom)
            val clampedBottomY = rawBottomY.coerceIn(noteAreaTop, noteAreaBottom)

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
    visibleTop: Float,
    visibleBottom: Float,
    density: Float
) {
    if (trace.isEmpty()) return

    val pxPerMs = fallSpeedPxPerSec * density / 1000f
    if (pxPerMs <= 0f) return

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
            visibleTop = visibleTop,
            visibleBottom = visibleBottom,
            color = noteColor,
            borderAlpha = 0.15f,
            centsHistory = emptyList(),
            density = density,
            wigglyPianoAtBottom = pianoAtBottom
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
            visibleTop = visibleTop,
            visibleBottom = visibleBottom,
            color = baseColor.copy(alpha = 0.75f),
            borderAlpha = 0.25f,
            centsHistory = if (suppressWigglyLine) emptyList() else segmentCents,
            density = density,
            wigglyPianoAtBottom = pianoAtBottom
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

// endregion
