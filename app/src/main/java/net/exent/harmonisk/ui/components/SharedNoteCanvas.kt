package net.exent.harmonisk.ui.components

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
import androidx.compose.ui.graphics.drawscope.clipPath
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
import net.exent.harmonisk.midi.MelodyTrack
import net.exent.harmonisk.music.BowedAlgorithm
import net.exent.harmonisk.music.BowedPosition
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.NoteUtils
import net.exent.harmonisk.ui.record.PianoVisibility
import net.exent.harmonisk.ui.record.PlayedNote
import net.exent.harmonisk.ui.melody.NoteResultState
import net.exent.harmonisk.ui.theme.AmberAccent
import net.exent.harmonisk.ui.theme.FingerColors
import net.exent.harmonisk.ui.theme.Orange40
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

private val CANVAS_BG = Color(0xFF0D0D1A)
private val BLACK_COLUMN_BG = Color(0xFF151520)
private val NOTE_COLOR_UPCOMING = Color(0xBB7C4DFF)
private val OVERLAY_PERFECT = Color(0xFF4CAF50)
private val OVERLAY_GREAT = Color(0xFF81C784)
private val OVERLAY_GOOD = Color(0xFFFFC107)
private val OVERLAY_OK = Orange40
private val OVERLAY_MISS = Color(0xFFF44336)

private const val BAR_GAP_PX = 2f
private const val INDICATOR_HEIGHT_PX = 8f
private const val CORNER_RADIUS_DP = 4f

private const val BOWED_REFERENCE_WIDTH_DP = 411f
private const val BOWED_LINE_ALPHA = 0.55f
private const val BOWED_LINE_WIDTH_DP = 2f
private val FINGER_COLORS = FingerColors.withIndex().associate { it.index to it.value }
private val OUT_OF_RANGE_GREY = Color(0xFF9E9E9E)
private val BLACK_KEY_INDICES = setOf(1, 3, 6, 8, 10)
private const val WHITE_KEYS_PER_OCTAVE = 7f
private const val BLACK_KEY_WIDTH_FRACTION = 0.6f
private val LABEL_OUTLINE_DIRECTIONS = listOf(
    Offset(-1f, -1f), Offset(0f, -1f), Offset(1f, -1f),
    Offset(-1f, 0f), Offset(1f, 0f),
    Offset(-1f, 1f), Offset(0f, 1f), Offset(1f, 1f)
)
private const val LABEL_OUTLINE_DP = 1.25f
private const val LABEL_SIZE_RATIO = 1.2f

private const val BOWED_SLOTS_PER_STRING = 7
private const val BOWED_BLOCK_COUNT = 4
private const val BOWED_SLOT_COUNT = BOWED_BLOCK_COUNT * BOWED_SLOTS_PER_STRING + 1
private const val BOWED_BLOCK_GAP_DP = 2.5f
private val BOWED_BLOCK_GAP_COLOR = Color(0xFF05050C)
internal fun bowedSlotWidth(canvasWidth: Float, gapPx: Float): Float =
    ((canvasWidth - gapPx * (BOWED_BLOCK_COUNT - 1)) / BOWED_SLOT_COUNT).coerceAtLeast(0f)

private fun bowedSlotsInBlock(stringIndex: Int, preferOpenStrings: Boolean): Int =
    if (stringIndex == if (preferOpenStrings) BOWED_BLOCK_COUNT - 1 else 0) BOWED_SLOTS_PER_STRING + 1
    else BOWED_SLOTS_PER_STRING

private fun bowedBlockStartSlot(stringIndex: Int, preferOpenStrings: Boolean): Int =
    if (preferOpenStrings) stringIndex * BOWED_SLOTS_PER_STRING
    else if (stringIndex == 0) 0 else stringIndex * BOWED_SLOTS_PER_STRING + 1

internal fun bowedBlockLeft(
    stringIndex: Int,
    canvasWidth: Float,
    gapPx: Float,
    preferOpenStrings: Boolean = true
): Float {
    val slotWidth = bowedSlotWidth(canvasWidth, gapPx)
    return bowedBlockStartSlot(stringIndex, preferOpenStrings) * slotWidth + stringIndex * gapPx
}

internal fun bowedSlotCenterX(
    midi: Int,
    canvasWidth: Float,
    gapPx: Float,
    lowestMidi: Int,
    preferOpenStrings: Boolean = true
): Float {
    val slot = (midi - lowestMidi).coerceIn(0, BOWED_SLOT_COUNT - 1)
    val block = (BOWED_BLOCK_COUNT - 1 downTo 0)
        .first { slot >= bowedBlockStartSlot(it, preferOpenStrings) }
    val withinBlock = slot - bowedBlockStartSlot(block, preferOpenStrings)
    return bowedBlockLeft(block, canvasWidth, gapPx, preferOpenStrings) +
        (withinBlock + 0.5f) * bowedSlotWidth(canvasWidth, gapPx)
}

private fun bowedPixelsPerSecond(fallSpeedPxPerSec: Float, canvasSpan: Float): Float =
    fallSpeedPxPerSec * canvasSpan / BOWED_REFERENCE_WIDTH_DP

val VOICE_COLORS = listOf(
    Color(0xFF4FC3F7),
    Color(0xFFFFB74D),
    Color(0xFFCE93D8),
    Color(0xFFF48FB1),
    Color(0xFF81C784),
    Color(0xFFFF8A65),
    Color(0xFF80CBC4),
    Color(0xFFFFF176),
    Color(0xFF80DEEA),
    Color(0xFFBCAAA4),
)

sealed interface CanvasMode {
    data class Record(
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
        val noteCentsPositions: Map<Int, List<Float>> = emptyMap(),
        val notePlayProgress: Map<Int, Float> = emptyMap(),
        val noteFirstDetectionStart: Map<Int, Float> = emptyMap(),
        val currentTimeSeconds: Double,
        val songTimeOffsetSeconds: Double,
        val fallSpeedPxPerSec: Float,
        val detectedMidiNote: Int?,
        val detectedCentsOff: Float?,
        val hideNoteBars: Boolean = false,
        val instrument: Instrument = Instrument.VIOLIN,
        val bowedPositions: List<BowedPosition?> = emptyList(),
        val preferOpenStrings: Boolean = true
    ) : CanvasMode
}

internal fun computeRecordNoteRects(
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
    val localDensity = LocalDensity.current
    val density = localDensity.density
    val fontScale = localDensity.fontScale
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    if (onNoteRects != null && mode is CanvasMode.Record && canvasSize != IntSize.Zero) {
        val w = canvasSize.width.toFloat()
        val pianoEdgeY = canvasSize.height / 2f
        val pxPerMs = mode.fallSpeedPxPerSec * density / 1000f
        val now = mode.songPositionMs
        val effMax = if (rightPaddingPx > 0f && w > rightPaddingPx) {
            val midiPerPx = (viewMaxMidi - viewMinMidi) / (w - rightPaddingPx)
            viewMaxMidi + rightPaddingPx * midiPerPx
        } else viewMaxMidi
        val layout = calculatePianoLayout(viewMinMidi, effMax, w)
        val rects = computeRecordNoteRects(mode.notes, now, pianoEdgeY, pxPerMs, layout)
        SideEffect { onNoteRects(rects) }
    }

    Canvas(modifier = modifier.fillMaxSize().onSizeChanged { canvasSize = it }) {
        val width = size.width
        val height = size.height
        val pianoHeight = PIANO_HEIGHT_DP * density

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

        val pianoVisibility = if (mode is CanvasMode.Record) mode.pianoVisibility else PianoVisibility.BOTH
        val showTopPiano = pianoVisibility == PianoVisibility.BOTH || pianoVisibility == PianoVisibility.UPPER_ONLY
        val showBottomPiano = pianoVisibility == PianoVisibility.BOTH || pianoVisibility == PianoVisibility.LOWER_ONLY
        val bowedMode = mode is CanvasMode.Melody

        val pianoEdgeY: Float
        val canvasFarEdgeY: Float
        val noteAreaTop: Float
        val noteAreaBottom: Float
        if (mode is CanvasMode.Record) {
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

        drawRect(CANVAS_BG)

        val playableBottom: Float = when {
            mode is CanvasMode.Record && mode.fallSpeedPxPerSec > 0f -> {
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

        if (!bowedMode) {
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
        }

        if (!bowedMode && playableBottom > noteAreaTop) {
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

        when (mode) {
            is CanvasMode.Record -> {
                drawBarLines(mode, pianoEdgeY, noteAreaTop, noteAreaBottom, density, textMeasurer)
                drawRecordNotes(mode, layout, pianoEdgeY, noteAreaTop, noteAreaBottom, density)
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
                if (bowedMode) {
                    val keyboardAreaPx = PIANO_HEIGHT_DP * density
                    val nowLineY = size.height - keyboardAreaPx
                    drawBowedBoard(mode, density, fontScale, textMeasurer, nowLineY)
                    drawBowedKeyboard(mode, density, textMeasurer, nowLineY)
                    clipRect(left = 0f, top = 0f, right = width, bottom = nowLineY) {
                        drawBowedBarLines(mode, density, textMeasurer, nowLineY)
                        drawBowedMelodyNotes(mode, density, textMeasurer, nowLineY)
                    }
                    drawLine(
                        color = Color.White.copy(alpha = BOWED_LINE_ALPHA),
                        start = Offset(0f, nowLineY),
                        end = Offset(size.width, nowLineY),
                        strokeWidth = BOWED_LINE_WIDTH_DP * density
                    )
                    drawBowedPitchIndicator(mode, density, nowLineY)
                } else {
                    drawBarLines(mode, pianoEdgeY, canvasFarEdgeY, density, textMeasurer, playableBottom)
                    drawMelodyNotes(mode, layout, pianoEdgeY, canvasFarEdgeY, pianoAtBottom, density)
                    drawPitchIndicator(mode, layout, pianoEdgeY, pianoAtBottom, density)
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
}


@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawBarLines(
    mode: CanvasMode.Record,
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

    val bar1Y = (nowY + now * pxPerMs).toFloat()
    val clipBottom = bar1Y.coerceIn(visibleTop, visibleBottom)
    if (clipBottom <= visibleTop) return

    clipRect(left = 0f, top = visibleTop, right = size.width, bottom = clipBottom) {

    val loopStartMs = mode.loopStartMs
    val loopEndMs = mode.loopEndMs

    val earliestTimeMs = now - (noteAreaBottom - nowY) / pxPerMs * 1.1
    val firstBeatIndex = kotlin.math.floor(earliestTimeMs / beatDurationMs).toLong() - 1L

    val barLabelStyle = TextStyle(
        color = Color.White.copy(alpha = 0.35f),
        fontSize = (9f * density / 2.75f).sp,
        fontWeight = FontWeight.Medium
    )

    var beatIndex = firstBeatIndex
    var safetyCounter = 0
    val loopStartBeatIndex = loopStartMs?.let { (it / beatDurationMs).roundToLong() }
    val loopEndBeatIndex   = loopEndMs?.let   { (it / beatDurationMs).roundToLong() }
    val loopBarLineStyle = TextStyle(
        color = AmberAccent.copy(alpha = 0.85f),
        fontSize = (9f * density / 2.75f).sp,
        fontWeight = FontWeight.Bold
    )
    while (safetyCounter++ < 2000) {
        val beatTimeMs = beatIndex * beatDurationMs
        if (beatIndex < 0) {
            beatIndex++
            continue
        }
        val ageMs = now - beatTimeMs
        val y = (nowY + ageMs * pxPerMs).toFloat()
        if (y < visibleTop - 2f) break
        val isBar = beatIndex % mode.beatsPerBar == 0L
        val isLoopMarker = isBar && (beatIndex == loopStartBeatIndex || beatIndex == loopEndBeatIndex)
        if (y <= visibleBottom + 2f) {
            drawLine(
                color = if (isLoopMarker) AmberAccent.copy(alpha = 0.80f)
                        else Color.White.copy(alpha = if (isBar) 0.20f else 0.08f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = if (isLoopMarker) 2f * density else if (isBar) 1.5f * density else 1f * density
            )
            if (isBar) {
                val barNumber = (beatTimeMs / barDurationMs).toLong() + 1
                val label = textMeasurer.measure(
                    text = buildAnnotatedString { append(barNumber.toString()) },
                    style = if (isLoopMarker) loopBarLineStyle else barLabelStyle
                )
                val labelY = (y - label.size.height - 2f * density).coerceAtLeast(visibleTop)
                if (labelY + label.size.height <= visibleBottom) {
                    drawText(label, topLeft = Offset(3f * density, labelY))
                }
            }
        }
        beatIndex++
    }
    }
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawBarLines(
    mode: CanvasMode.Melody,
    pianoEdgeY: Float,
    canvasFarEdgeY: Float,
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



private fun DrawScope.drawRecordNotes(
    mode: CanvasMode.Record,
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

    for (note in allNotes) {
        val key = layout[note.midiNumber] ?: continue

        val noteEndMs = note.endTimeMs ?: mode.songPositionMs
        val ageOfEndMs = now - noteEndMs
        val ageOfStartMs = now - note.startTimeMs

        val rawTopY = pianoEdgeY + ageOfEndMs * pxPerMs
        val rawBottomY = pianoEdgeY + ageOfStartMs * pxPerMs

        val baseVoiceColor = VOICE_COLORS[note.trackOrigin % VOICE_COLORS.size]
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
            borderAlpha = if (isSelected) 0.0f else 0.25f,
            centsHistory = note.centsHistory,
            density = density,
            wigglyPianoAtBottom = true
        )

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
                    drawPath(selPath, Color.White.copy(alpha = 0.30f), style = Stroke(width = 5f * density))
                    drawPath(selPath, Color.White.copy(alpha = 0.90f), style = Stroke(width = 1.5f * density))
                }
            }
        }
    }

    val selectedNote = allNotes.firstOrNull { it.id == mode.selectedNoteId }
    if (selectedNote != null && selectedNote.endTimeMs != null) {
        val key = layout[selectedNote.midiNumber]
        if (key != null) {
            val noteEndMs = selectedNote.endTimeMs
            val rawTopY    = (pianoEdgeY + (now - noteEndMs)             * pxPerMs).coerceIn(noteAreaTop, noteAreaBottom)
            val rawBottomY = (pianoEdgeY + (now - selectedNote.startTimeMs) * pxPerMs).coerceIn(noteAreaTop, noteAreaBottom)
            val barX = key.x + BAR_GAP_PX
            val barW = key.width - BAR_GAP_PX * 2f
            val baseVoiceColor = VOICE_COLORS[selectedNote.trackOrigin % VOICE_COLORS.size]
            var barColor = if (selectedNote.voiceIndex == mode.activeVoiceIndex || selectedNote.isActive) baseVoiceColor
                           else baseVoiceColor.copy(alpha = 0.6f)
            if (key.isBlack) barColor = barColor.copy(red = barColor.red * 0.75f, green = barColor.green * 0.75f, blue = barColor.blue * 0.75f)

            val endHandleX = barX + barW
            drawCircle(color = Color.White.copy(alpha = 0.9f), radius = handleRadius,                center = Offset(endHandleX, rawTopY))
            drawCircle(color = barColor,                        radius = handleRadius - 3f * density, center = Offset(endHandleX, rawTopY))
            val startHandleX = barX
            drawCircle(color = Color.White.copy(alpha = 0.9f), radius = handleRadius,                center = Offset(startHandleX, rawBottomY))
            drawCircle(color = barColor,                        radius = handleRadius - 3f * density, center = Offset(startHandleX, rawBottomY))
        }
    }
}



private fun DrawScope.drawMelodyNotes(
    mode: CanvasMode.Melody,
    layout: Map<Int, KeyLayout>,
    pianoEdgeY: Float,
    canvasFarEdgeY: Float,
    pianoAtBottom: Boolean,
    density: Float
) {
    if (mode.hideNoteBars) return

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

        val visuallyActive = viewTimeSeconds >= noteStartTime && viewTimeSeconds < noteEndTime
        val noteColor = NOTE_COLOR_UPCOMING

        drawNoteBar(
            barX = barX,
            barW = barW,
            rawTopY = rawTopY,
            rawBottomY = rawBottomY,
            visibleTop = visibleTop,
            visibleBottom = visibleBottom,
            color = noteColor,
            borderAlpha = 0.15f,
            activeGlow = visuallyActive,
            centsHistory = mode.noteCentsHistory[index] ?: emptyList(),
            centsPositions = mode.noteCentsPositions[index] ?: emptyList(),
            playProgress = mode.notePlayProgress[index] ?: 0f,
            detectionStart = mode.noteFirstDetectionStart[index] ?: 0f,
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

    val indicatorColor = getIndicatorColor(detectedMidi, mode.detectedCentsOff, mode.melodyTrack, mode.currentNoteIndex)

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



@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawBowedBoard(
    mode: CanvasMode.Melody,
    density: Float,
    fontScale: Float,
    textMeasurer: TextMeasurer,
    nowLineY: Float
) {
    val width = size.width
    val height = size.height
    val gapPx = BOWED_BLOCK_GAP_DP * density
    val slotWidth = bowedSlotWidth(width, gapPx)
    val preferOpenStrings = mode.preferOpenStrings

    for (i in 0 until BOWED_BLOCK_COUNT) {
        val left = bowedBlockLeft(i, width, gapPx, preferOpenStrings)
        if (i % 2 == 0) {
            drawRect(
                color = Color(0x1AFFFFFF),
                topLeft = Offset(left, 0f),
                size = Size(slotWidth * bowedSlotsInBlock(i, preferOpenStrings), height)
            )
        }
        if (i > 0) {
            drawRect(
                color = BOWED_BLOCK_GAP_COLOR,
                topLeft = Offset(left - gapPx, 0f),
                size = Size(gapPx, height)
            )
            drawLine(
                color = Color.White.copy(alpha = BOWED_LINE_ALPHA),
                start = Offset(left - gapPx / 2f, 0f),
                end = Offset(left - gapPx / 2f, height),
                strokeWidth = BOWED_LINE_WIDTH_DP * density
            )
        }
    }

    val labels = mode.instrument.stringLabels
    val letterPx = minOf(slotWidth * BOWED_SLOTS_PER_STRING, nowLineY / 3f)
    val letterStyle = TextStyle(
        color = Color.White.copy(alpha = 0.10f),
        fontSize = (letterPx / (density * fontScale)).sp,
        fontWeight = FontWeight.Bold
    )
    for (i in labels.indices) {
        val centerX = bowedBlockLeft(i, width, gapPx, preferOpenStrings) +
            slotWidth * bowedSlotsInBlock(i, preferOpenStrings) / 2f
        val textLayout = textMeasurer.measure(
            text = buildAnnotatedString { append(labels[i]) },
            style = letterStyle
        )
        drawText(
            textLayout,
            topLeft = Offset(
                centerX - textLayout.size.width / 2f,
                nowLineY / 2f - textLayout.size.height / 2f
            )
        )
    }
}

private fun bowedKeyboardLayout(
    width: Float,
    gapPx: Float,
    lowestMidi: Int,
    preferOpenStrings: Boolean
): Map<Int, KeyLayout> {
    val slotWidth = bowedSlotWidth(width, gapPx)
    val centers = FloatArray(BOWED_SLOT_COUNT) {
        bowedSlotCenterX(lowestMidi + it, width, gapPx, lowestMidi, preferOpenStrings)
    }
    val black = BooleanArray(BOWED_SLOT_COUNT) { (lowestMidi + it) % 12 in BLACK_KEY_INDICES }
    val whiteSlots = (0 until BOWED_SLOT_COUNT).filter { !black[it] }
    val averageWhiteKeyWidth = slotWidth * 12f / WHITE_KEYS_PER_OCTAVE
    val blackWidth = averageWhiteKeyWidth * BLACK_KEY_WIDTH_FRACTION

    return buildMap {
        whiteSlots.forEachIndexed { index, slot ->
            val left = if (index == 0) centers[slot] - slotWidth / 2f
            else (centers[whiteSlots[index - 1]] + centers[slot]) / 2f
            val right = if (index == whiteSlots.lastIndex) width
            else (centers[slot] + centers[whiteSlots[index + 1]]) / 2f
            put(lowestMidi + slot, KeyLayout(x = left, width = right - left, isBlack = false))
        }
        for (slot in 0 until BOWED_SLOT_COUNT) {
            if (!black[slot]) continue
            put(
                lowestMidi + slot,
                KeyLayout(x = centers[slot] - blackWidth / 2f, width = blackWidth, isBlack = true)
            )
        }
    }
}

private fun DrawScope.drawBowedKeyboard(
    mode: CanvasMode.Melody,
    density: Float,
    textMeasurer: TextMeasurer,
    nowLineY: Float
) {
    val gapPx = BOWED_BLOCK_GAP_DP * density
    if (bowedSlotWidth(size.width, gapPx) <= 0f) return

    val lowestMidi = mode.instrument.tuning.first()
    val highestMidi = lowestMidi + BOWED_SLOT_COUNT - 1

    drawPianoKeyboard(
        textMeasurer = textMeasurer,
        layout = bowedKeyboardLayout(size.width, gapPx, lowestMidi, mode.preferOpenStrings),
        pianoHeight = size.height - nowLineY,
        minMidi = lowestMidi,
        maxMidi = highestMidi,
        pianoAtBottom = true,
        mirrored = false
    )
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawBowedBarLines(
    mode: CanvasMode.Melody,
    density: Float,
    textMeasurer: TextMeasurer,
    nowLineY: Float
) {
    val track = mode.melodyTrack
    val pixelsPerSecond = bowedPixelsPerSecond(mode.fallSpeedPxPerSec, nowLineY)
    if (pixelsPerSecond <= 0f) return

    val viewTimeSeconds = mode.currentTimeSeconds + mode.songTimeOffsetSeconds
    val visibleRangeSeconds = nowLineY / pixelsPerSecond

    val firstTempoUs = track.tempoChanges.firstOrNull()?.microsecondsPerBeat ?: 500_000L
    val songDurationSeconds = track.durationSeconds

    val earliestViewSeconds = viewTimeSeconds - 0.1
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
            if (nextBeatTick >= nextTempoTick) tempoIndex++
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
        val y = (nowLineY - relativeSeconds * pixelsPerSecond).toFloat()
        if (y < -2f || y > nowLineY + 2f) continue
        drawLine(
            color = Color.White.copy(alpha = if (beat.isBarLine) 0.18f else 0.07f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = if (beat.isBarLine) 1.5f * density else 1f * density
        )
        if (beat.isBarLine && beat.barNumber > 0L) {
            val label = textMeasurer.measure(
                text = buildAnnotatedString { append(beat.barNumber.toString()) },
                style = barLabelStyle
            )
            val labelY = (y - label.size.height - 2f * density).coerceAtLeast(0f)
            if (labelY + label.size.height <= nowLineY) {
                drawText(label, topLeft = Offset(3f * density, labelY))
            }
        }
    }
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawBowedMelodyNotes(
    mode: CanvasMode.Melody,
    density: Float,
    textMeasurer: TextMeasurer,
    nowLineY: Float
) {
    if (mode.hideNoteBars) return

    val width = size.width
    val pixelsPerSecond = bowedPixelsPerSecond(mode.fallSpeedPxPerSec, nowLineY)
    val viewTimeSeconds = mode.currentTimeSeconds + mode.songTimeOffsetSeconds
    val gapPx = BOWED_BLOCK_GAP_DP * density
    val slotWidth = bowedSlotWidth(width, gapPx)
    val positions = mode.bowedPositions
    val minGapPx = 4f * density
    val barThickness = (slotWidth * 0.9f).coerceAtLeast(4f * density)
    val labelStyle = TextStyle(
        color = Color.Black,
        fontSize = (barThickness * LABEL_SIZE_RATIO / density).sp,
        fontWeight = FontWeight.Bold
    )
    val labels = (1..4).associateWith { finger ->
        val text = buildAnnotatedString { append(finger.toString()) }
        textMeasurer.measure(text, labelStyle) to
            textMeasurer.measure(text, labelStyle.copy(color = Color.White))
    }

    mode.melodyTrack.notes.forEachIndexed { index, note ->
        val noteStartTime = mode.melodyTrack.noteStartSeconds(note)
        val noteDuration = mode.melodyTrack.noteDurationSeconds(note)
        val noteEndTime = noteStartTime + noteDuration

        val relativeStart = noteStartTime - viewTimeSeconds
        val relativeEnd = noteEndTime - viewTimeSeconds

        var rawBottomY = (nowLineY - relativeStart * pixelsPerSecond).toFloat()
        var rawTopY = (nowLineY - relativeEnd * pixelsPerSecond).toFloat()

        if (index > 0 && mode.melodyTrack.notes[index - 1].noteNumber == note.noteNumber) {
            rawBottomY -= minGapPx / 2f
        }
        if (index < mode.melodyTrack.notes.size - 1 && mode.melodyTrack.notes[index + 1].noteNumber == note.noteNumber) {
            rawTopY += minGapPx / 2f
        }

        if (rawTopY > nowLineY || rawBottomY < 0f) return@forEachIndexed

        val lowestMidi = mode.instrument.tuning.first()
        val pos = positions.getOrNull(index)
            ?.takeIf { note.noteNumber in lowestMidi..mode.instrument.highestFirstPositionMidi }

        var barLen = rawBottomY - rawTopY
        if (barLen < 2f) return@forEachIndexed

        val minBarLen = maxOf(barThickness, nowLineY * 0.035f)
        var barTop = rawTopY
        if (barLen < minBarLen) {
            val pad = (minBarLen - barLen) / 2f
            barTop -= pad
            barLen = minBarLen
        }

        val centerX = bowedSlotCenterX(note.noteNumber, width, gapPx, lowestMidi, mode.preferOpenStrings)
        val barLeft = centerX - barThickness / 2f

        val r = CORNER_RADIUS_DP * density
        val visuallyActive = viewTimeSeconds >= noteStartTime && viewTimeSeconds < noteEndTime
        val color = FINGER_COLORS[pos?.finger] ?: OUT_OF_RANGE_GREY
        val deviation = if (pos == null) 0
        else BowedAlgorithm.fingerOffsetDeviation(
            note.noteNumber,
            mode.instrument.tuning[pos.stringIndex],
            pos.finger,
            mode.instrument
        ) ?: 0
        val stripeColor = if (pos == null) null else when (deviation) {
            in 1..4 -> FINGER_COLORS[pos.finger + 1]
            in -4..-1 -> FINGER_COLORS[pos.finger - 1]
            else -> null
        }

        drawRoundRect(
            color = color,
            topLeft = Offset(barLeft, barTop),
            size = Size(barThickness, barLen),
            cornerRadius = CornerRadius(r, r)
        )

        if (stripeColor != null) {
            drawDiagonalStripes(
                topLeft = Offset(barLeft, barTop),
                barSize = Size(barThickness, barLen),
                cornerRadius = r,
                color = stripeColor,
                rising = deviation > 0,
                density = density
            )
        }

        if (visuallyActive) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.30f),
                topLeft = Offset(barLeft - 2f * density, barTop - 2f * density),
                size = Size(barThickness + 4f * density, barLen + 4f * density),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = 4f * density, cap = StrokeCap.Round)
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.95f),
                topLeft = Offset(barLeft, barTop),
                size = Size(barThickness, barLen),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = 1.5f * density, cap = StrokeCap.Round)
            )
        } else {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.25f),
                topLeft = Offset(barLeft, barTop),
                size = Size(barThickness, barLen),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = 1f * density)
            )
        }

        val centsHistory = mode.noteCentsHistory[index] ?: emptyList()
        val centsPositions = mode.noteCentsPositions[index] ?: emptyList()
        val playProgress = mode.notePlayProgress[index] ?: 0f
        val detectionStart = mode.noteFirstDetectionStart[index] ?: 0f
        if (centsHistory.isNotEmpty() && centsPositions.size == centsHistory.size && playProgress > 0f) {
            drawVerticalPitchLine(
                barLeft = barLeft,
                barThickness = barThickness,
                barTop = barTop,
                barBottom = barTop + barLen,
                centsHistory = centsHistory,
                centsPositions = centsPositions,
                playProgress = playProgress,
                detectionStart = detectionStart,
                density = density
            )
        }

        val (textLayout, outlineLayout) = labels[pos?.finger] ?: return@forEachIndexed
        val barVisibleTop = barTop.coerceAtLeast(0f)
        val barVisibleBottom = (barTop + barLen).coerceAtMost(nowLineY)
        val textHeight = textLayout.size.height
        if (barVisibleBottom - barVisibleTop < textHeight) return@forEachIndexed
        val labelTop = (barTop + (barLen - textHeight) / 2f)
            .coerceIn(barVisibleTop, barVisibleBottom - textHeight)
        val labelLeft = centerX - textLayout.size.width / 2f
        for (direction in LABEL_OUTLINE_DIRECTIONS) {
            drawText(
                outlineLayout,
                topLeft = Offset(
                    labelLeft + direction.x * LABEL_OUTLINE_DP * density,
                    labelTop + direction.y * LABEL_OUTLINE_DP * density
                )
            )
        }
        drawText(textLayout, topLeft = Offset(labelLeft, labelTop))
    }
}

private fun DrawScope.drawBowedPitchIndicator(
    mode: CanvasMode.Melody,
    density: Float,
    nowLineY: Float
) {
    val detectedMidi = mode.detectedMidiNote ?: return
    val track = mode.melodyTrack
    val expectedIndex = mode.currentNoteIndex
    if (expectedIndex !in track.notes.indices) return

    val gapPx = BOWED_BLOCK_GAP_DP * density
    val slotWidth = bowedSlotWidth(size.width, gapPx)
    val markerX = bowedSlotCenterX(
        detectedMidi,
        size.width,
        gapPx,
        mode.instrument.tuning.first(),
        mode.preferOpenStrings
    )

    val indicatorColor = getIndicatorColor(detectedMidi, mode.detectedCentsOff, track, expectedIndex)

    val indicatorW = (slotWidth * 0.8f).coerceAtLeast(6f * density)
    val indicatorH = 6f * density
    drawRoundRect(
        color = indicatorColor,
        topLeft = Offset(markerX - indicatorW / 2f, nowLineY - indicatorH),
        size = Size(indicatorW, indicatorH),
        cornerRadius = CornerRadius(2f * density, 2f * density)
    )
}

private fun DrawScope.drawVerticalPitchLine(
    barLeft: Float,
    barThickness: Float,
    barTop: Float,
    barBottom: Float,
    centsHistory: List<Float>,
    centsPositions: List<Float>,
    playProgress: Float,
    detectionStart: Float,
    density: Float
) {
    val numSamples = centsHistory.size
    if (numSamples < 1 || numSamples != centsPositions.size) return

    val start = detectionStart.coerceIn(0f, 1f)
    val end = playProgress.coerceIn(start + 0.001f, 1f)
    val lineLength = (barBottom - barTop) * (end - start)
    if (lineLength < 1f) return

    val centerX = barLeft + barThickness / 2f
    val maxDeviationPx = barThickness / 2f - 2f
    val gapThreshold = 0.2f

    val path = Path()
    for (i in centsHistory.indices) {
        val t = centsPositions[i].coerceIn(0f, 1f)
        val normalized = (centsHistory[i] / NoteUtils.MAX_CENTS_OFF).coerceIn(-1f, 1f)
        val x = centerX + normalized * maxDeviationPx
        val y = barBottom - t * (barBottom - barTop)
        if (i == 0) {
            path.moveTo(x, y)
        } else if (t - centsPositions[i - 1] > gapThreshold) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }

    clipRect(left = barLeft, top = barTop, right = barLeft + barThickness, bottom = barBottom) {
        drawGlowPath(path, density)
    }
}



private fun DrawScope.drawDiagonalStripes(
    topLeft: Offset,
    barSize: Size,
    cornerRadius: Float,
    color: Color,
    rising: Boolean,
    density: Float
) {
    val stripeWidth = (barSize.width * 0.22f).coerceAtLeast(1.5f * density)
    val step = stripeWidth * 3.6f
    val shape = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(topLeft, barSize),
                cornerRadius = CornerRadius(cornerRadius, cornerRadius)
            )
        )
    }
    clipPath(shape) {
        val diagonal = barSize.height
        val bottom = topLeft.y + barSize.height
        var x = topLeft.x - diagonal
        while (x < topLeft.x + barSize.width) {
            drawLine(
                color = color,
                start = Offset(x, if (rising) bottom else topLeft.y),
                end = Offset(x + diagonal, if (rising) topLeft.y else bottom),
                strokeWidth = stripeWidth
            )
            x += step
        }
    }
}

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
    centsPositions: List<Float> = emptyList(),
    playProgress: Float = 1f,
    detectionStart: Float = 0f,
    density: Float,
    wigglyPianoAtBottom: Boolean = false,
    activeGlow: Boolean = false
) {
    if (barW < 2f) return

    if (rawTopY > visibleBottom || rawBottomY < visibleTop) return

    val clippedTop = rawTopY.coerceIn(visibleTop, visibleBottom)
    val clippedBottom = rawBottomY.coerceIn(visibleTop, visibleBottom)
    val barHeight = clippedBottom - clippedTop

    if (barHeight < 1f) return

    val r = CORNER_RADIUS_DP * density

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

    if (activeGlow) {
        drawPath(path, Color.White.copy(alpha = 0.30f), style = Stroke(width = 4f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, Color.White.copy(alpha = 0.95f), style = Stroke(width = 1.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    if (centsHistory.isNotEmpty() && barHeight > 6f && barW > 8f) {
        drawWigglyPitchLine(
            barX = barX,
            barW = barW,
            rawTopY = rawTopY,
            rawBottomY = rawBottomY,
            clippedTop = clippedTop,
            clippedBottom = clippedBottom,
            centsHistory = centsHistory,
            centsPositions = centsPositions,
            playProgress = playProgress,
            detectionStart = detectionStart,
            pianoAtBottom = wigglyPianoAtBottom,
            density = density
        )
    }
}



private fun DrawScope.drawWigglyPitchLine(
    barX: Float,
    barW: Float,
    rawTopY: Float,
    rawBottomY: Float,
    clippedTop: Float,
    clippedBottom: Float,
    centsHistory: List<Float>,
    centsPositions: List<Float>,
    playProgress: Float,
    detectionStart: Float,
    pianoAtBottom: Boolean,
    density: Float
) {
    val numSamples = centsHistory.size
    if (numSamples < 1 || playProgress <= 0f) return
    if (numSamples != centsPositions.size) return

    val fullHeight = rawBottomY - rawTopY
    if (fullHeight < 1f) return

    val start = detectionStart.coerceIn(0f, 1f)
    val end = playProgress.coerceIn(start + 0.001f, 1f)
    val lineHeight = fullHeight * (end - start)
    if (lineHeight < 1f) return

    val path = Path()
    val centerX = barX + barW / 2f
    val maxDeviationPx = barW / 2f - 2f
    val gapThreshold = 0.2f

    for (i in centsHistory.indices) {
        val t = centsPositions[i].coerceIn(0f, 1f)
        val normalized = (centsHistory[i] / NoteUtils.MAX_CENTS_OFF).coerceIn(-1f, 1f)
        val x = centerX + normalized * maxDeviationPx
        val y = if (pianoAtBottom) rawBottomY - t * fullHeight else rawTopY + t * fullHeight
        if (i == 0) {
            path.moveTo(x, y)
        } else if (t - centsPositions[i - 1] > gapThreshold) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }

    clipRect(left = barX, top = clippedTop, right = barX + barW, bottom = clippedBottom) {
        drawGlowPath(path, density)
    }
}



private fun DrawScope.drawGlowPath(path: Path, density: Float) {
    drawPath(path, Color.White.copy(alpha = 0.15f), style = Stroke(width = 4f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, Color.White.copy(alpha = 0.7f),  style = Stroke(width = 1.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun getIndicatorColor(
    detectedMidi: Int,
    centsOff: Float?,
    melodyTrack: MelodyTrack,
    currentNoteIndex: Int
): Color {
    if (currentNoteIndex !in melodyTrack.notes.indices) return Color.White.copy(alpha = 0.5f)
    val expectedNote = melodyTrack.notes[currentNoteIndex]

    return if (detectedMidi == expectedNote.noteNumber) {
        val absCents = abs(centsOff ?: NoteUtils.MAX_CENTS_OFF)
        when {
            absCents <= 5f -> OVERLAY_PERFECT
            absCents <= 10f -> OVERLAY_GREAT
            absCents <= 30f -> OVERLAY_GOOD
            absCents <= NoteUtils.MAX_CENTS_OFF -> OVERLAY_OK
            else -> OVERLAY_MISS
        }
    } else {
        OVERLAY_MISS.copy(alpha = 0.6f)
    }
}

