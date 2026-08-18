package net.exent.perfectpitch.ui.components

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
import net.exent.perfectpitch.midi.MelodyTrack
import net.exent.perfectpitch.music.ViolinAlgorithm
import net.exent.perfectpitch.music.ViolinPosition
import net.exent.perfectpitch.music.ViolinString
import net.exent.perfectpitch.ui.freeplay.PianoVisibility
import net.exent.perfectpitch.ui.freeplay.PlayedNote
import net.exent.perfectpitch.ui.melody.NoteResultState
import net.exent.perfectpitch.ui.theme.AmberAccent
import net.exent.perfectpitch.ui.theme.DarkBg
import net.exent.perfectpitch.ui.theme.Orange40
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

// Violin fingering board
private const val VIOLIN_LANE_COUNT = 4
private val VIOLIN_STRING_LABELS = listOf("G", "D", "A", "E")
// Reference portrait canvas width (dp) the horizontal time scale is normalized to
private const val VIOLIN_REFERENCE_WIDTH_DP = 411f
private val FINGER_COLORS = mapOf(
    0 to Color(0xFF2196F3), // open string (blue)
    1 to Color(0xFF4CAF50), // index
    2 to Color(0xFFFFEB3B), // middle
    3 to Color(0xFFFF9800), // ring
    4 to Color(0xFFF44336)  // pinky
)
private val OUT_OF_RANGE_GREY = Color(0xFF9E9E9E)

// Horizontal shift per semitone of deviation from a finger's default placement
private const val VIOLIN_FINGER_OFFSET_FRACTION = 0.07f

/** Time scale for the violin board, normalized to the canvas span so the
 *  visible time window stays consistent across portrait and landscape. */
private fun violinPixelsPerSecond(fallSpeedPxPerSec: Float, canvasSpan: Float): Float =
    fallSpeedPxPerSec * canvasSpan / VIOLIN_REFERENCE_WIDTH_DP

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
        val noteCentsPositions: Map<Int, List<Float>> = emptyMap(),
        val notePlayProgress: Map<Int, Float> = emptyMap(),
        val noteFirstDetectionStart: Map<Int, Float> = emptyMap(),
        val currentTimeSeconds: Double,
        val songTimeOffsetSeconds: Double,
        val fallSpeedPxPerSec: Float,
        val detectedMidiNote: Int?,
        val detectedCentsOff: Float?,
        val hideNoteBars: Boolean = false,
        val violinMode: Boolean = false,
        val violinPositions: List<ViolinPosition?> = emptyList()
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
        val violinMode = mode is CanvasMode.Melody && mode.violinMode

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
        if (!violinMode) {
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

        // Draw subtle grid lines at white key boundaries (clipped to playable area)
        if (!violinMode && playableBottom > noteAreaTop) {
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
                if (violinMode) {
                    drawViolinBoard(mode, density, textMeasurer)
                    drawViolinBarLines(mode, density, textMeasurer)
                    drawViolinMelodyNotes(mode, density, textMeasurer)
                    drawViolinPitchIndicator(mode, density)
                } else {
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

    // Draw loop bar line markers (start and end lines in distinct color)
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
    // Pre-compute loop bar-line indices (0-based) for fast comparison
    val loopStartBeatIndex = loopStartMs?.let { (it / beatDurationMs).roundToLong() }
    val loopEndBeatIndex   = loopEndMs?.let   { (it / beatDurationMs).roundToLong() }
    val loopBarLineStyle = TextStyle(
        color = AmberAccent.copy(alpha = 0.85f),
        fontSize = (9f * density / 2.75f).sp,
        fontWeight = FontWeight.Bold
    )
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
            borderAlpha = if (isSelected) 0.0f else 0.25f,  // selection glow replaces border
            centsHistory = note.centsHistory,
            density = density,
            wigglyPianoAtBottom = true  // FreePlay notes scroll down: sample 0 (start) is at rawBottomY
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
            val baseVoiceColor = VOICE_COLORS[selectedNote.trackOrigin % VOICE_COLORS.size]
            var barColor = if (selectedNote.voiceIndex == mode.activeVoiceIndex || selectedNote.isActive) baseVoiceColor
                           else baseVoiceColor.copy(alpha = 0.6f)
            if (key.isBlack) barColor = barColor.copy(red = barColor.red * 0.75f, green = barColor.green * 0.75f, blue = barColor.blue * 0.75f)

            // End handle: centered on top-right corner
            val endHandleX = barX + barW
            drawCircle(color = Color.White.copy(alpha = 0.9f), radius = handleRadius,                center = Offset(endHandleX, rawTopY))
            drawCircle(color = barColor,                        radius = handleRadius - 3f * density, center = Offset(endHandleX, rawTopY))
            // Start handle: centered on bottom-left corner
            val startHandleX = barX
            drawCircle(color = Color.White.copy(alpha = 0.9f), radius = handleRadius,                center = Offset(startHandleX, rawBottomY))
            drawCircle(color = barColor,                        radius = handleRadius - 3f * density, center = Offset(startHandleX, rawBottomY))
        }
    }
}

// endregion

// region Melody rendering

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

// endregion

// region Violin board rendering

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawViolinBoard(
    mode: CanvasMode.Melody,
    density: Float,
    textMeasurer: TextMeasurer
) {
    val width = size.width
    val height = size.height
    val bandWidth = width / VIOLIN_LANE_COUNT

    // Alternating subtle band backgrounds (band 0 = G on the left, up to E on the right)
    for (i in 0 until VIOLIN_LANE_COUNT) {
        if (i % 2 == 0) {
            val left = i * bandWidth
            drawRect(color = Color(0x0DFFFFFF), topLeft = Offset(left, 0f), size = Size(bandWidth, height))
        }
    }

    // Vertical string lines (one per band) and band boundaries
    for (i in 0 until VIOLIN_LANE_COUNT) {
        val centerX = (i + 0.5f) * bandWidth
        drawLine(
            color = Color(0x2EFFFFFF),
            start = Offset(centerX, 0f),
            end = Offset(centerX, height),
            strokeWidth = 1.5f * density
        )
    }
    for (i in 1 until VIOLIN_LANE_COUNT) {
        val x = i * bandWidth
        drawLine(
            color = Color(0x1AFFFFFF),
            start = Offset(x, 0f),
            end = Offset(x, height),
            strokeWidth = 1f * density
        )
    }

    // String labels at the bottom edge of each band, on a dark pill for readability
    val labelStyle = TextStyle(
        color = Color.White.copy(alpha = 0.7f),
        fontSize = (14f * density / 2.75f).sp,
        fontWeight = FontWeight.Bold
    )
    for (i in 0 until VIOLIN_LANE_COUNT) {
        val centerX = (i + 0.5f) * bandWidth
        val textLayout = textMeasurer.measure(
            text = buildAnnotatedString { append(VIOLIN_STRING_LABELS[i]) },
            style = labelStyle
        )
        val pad = 4f * density
        val boxW = textLayout.size.width + pad * 2f
        val boxH = textLayout.size.height + pad * 1.6f
        val boxLeft = centerX - boxW / 2f
        val boxTop = height - boxH - 2f * density
        drawRoundRect(
            color = CANVAS_BG.copy(alpha = 0.8f),
            topLeft = Offset(boxLeft, boxTop),
            size = Size(boxW, boxH),
            cornerRadius = CornerRadius(3f * density, 3f * density)
        )
        drawText(
            textLayout,
            topLeft = Offset(boxLeft + (boxW - textLayout.size.width) / 2f, boxTop + pad)
        )
    }
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawViolinBarLines(
    mode: CanvasMode.Melody,
    density: Float,
    textMeasurer: TextMeasurer
) {
    val track = mode.melodyTrack
    val pixelsPerSecond = violinPixelsPerSecond(mode.fallSpeedPxPerSec, size.height)
    if (pixelsPerSecond <= 0f) return

    val viewTimeSeconds = mode.currentTimeSeconds + mode.songTimeOffsetSeconds
    val visibleRangeSeconds = size.height / pixelsPerSecond

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
        val y = (size.height - relativeSeconds * pixelsPerSecond).toFloat()
        if (y < -2f || y > size.height + 2f) continue
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
            if (labelY + label.size.height <= size.height) {
                drawText(label, topLeft = Offset(3f * density, labelY))
            }
        }
    }
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawViolinMelodyNotes(
    mode: CanvasMode.Melody,
    density: Float,
    textMeasurer: TextMeasurer
) {
    if (mode.hideNoteBars) return

    val width = size.width
    val height = size.height
    val pixelsPerSecond = violinPixelsPerSecond(mode.fallSpeedPxPerSec, height)
    val viewTimeSeconds = mode.currentTimeSeconds + mode.songTimeOffsetSeconds
    val bandWidth = width / VIOLIN_LANE_COUNT
    val positions = mode.violinPositions
    val minGapPx = 4f * density

    mode.melodyTrack.notes.forEachIndexed { index, note ->
        val noteStartTime = mode.melodyTrack.noteStartSeconds(note)
        val noteDuration = mode.melodyTrack.noteDurationSeconds(note)
        val noteEndTime = noteStartTime + noteDuration

        val relativeStart = noteStartTime - viewTimeSeconds
        val relativeEnd = noteEndTime - viewTimeSeconds

        // Notes fall toward the bottom now line: future times sit above it
        var rawBottomY = (size.height - relativeStart * pixelsPerSecond).toFloat()
        var rawTopY = (size.height - relativeEnd * pixelsPerSecond).toFloat()

        if (index > 0 && mode.melodyTrack.notes[index - 1].noteNumber == note.noteNumber) {
            rawBottomY -= minGapPx / 2f
        }
        if (index < mode.melodyTrack.notes.size - 1 && mode.melodyTrack.notes[index + 1].noteNumber == note.noteNumber) {
            rawTopY += minGapPx / 2f
        }

        if (rawTopY > size.height || rawBottomY < 0f) return@forEachIndexed

        val pos = positions.getOrNull(index)
        val string = pos?.string ?: ViolinAlgorithm.nearestString(note.noteNumber)
        val bandIndex = string.ordinal
        val bandLeft = bandIndex * bandWidth

        var barLen = rawBottomY - rawTopY
        if (barLen < 2f) return@forEachIndexed

        val barThickness = (bandWidth * 0.13f).coerceAtLeast(14f * density)
        // Ensure a minimum visible length that adapts to the canvas
        val minBarLen = maxOf(barThickness, height * 0.035f)
        var barTop = rawTopY
        if (barLen < minBarLen) {
            val pad = (minBarLen - barLen) / 2f
            barTop -= pad
            barLen = minBarLen
        }

        // String line; flat (low finger) placements sit to the left of the string
        val stringLineX = bandLeft + bandWidth / 2f
        val deviation = if (pos == null) 0f else
            (ViolinAlgorithm.fingerOffsetDeviation(note.noteNumber, string, pos.finger) ?: 0f).toFloat()
        val centerX = stringLineX + deviation * bandWidth * VIOLIN_FINGER_OFFSET_FRACTION

        val barLeft = centerX - barThickness / 2f

        val r = CORNER_RADIUS_DP * density
        val visuallyActive = viewTimeSeconds >= noteStartTime && viewTimeSeconds < noteEndTime
        val color = if (pos == null) OUT_OF_RANGE_GREY else FINGER_COLORS[pos.finger] ?: OUT_OF_RANGE_GREY

        drawRoundRect(
            color = color,
            topLeft = Offset(barLeft, barTop),
            size = Size(barThickness, barLen),
            cornerRadius = CornerRadius(r, r)
        )

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

        // Wiggly pitch line along the vertical bar
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

        // Finger number label (no number for open strings)
        val finger = pos?.finger
        if (finger != null && finger in 1..4) {
            val textStyle = TextStyle(
                color = Color.Black.copy(alpha = 0.85f),
                fontSize = (barThickness * 0.6f / density).sp,
                fontWeight = FontWeight.Bold
            )
            val textLayout = textMeasurer.measure(
                text = buildAnnotatedString { append(finger.toString()) },
                style = textStyle
            )
            drawText(
                textLayout,
                topLeft = Offset(
                    centerX - textLayout.size.width / 2f,
                    barTop + (barLen - textLayout.size.height) / 2f
                )
            )
        }
    }
}

private fun DrawScope.drawViolinPitchIndicator(
    mode: CanvasMode.Melody,
    density: Float
) {
    val detectedMidi = mode.detectedMidiNote ?: return
    val track = mode.melodyTrack
    val expectedIndex = mode.currentNoteIndex
    if (expectedIndex !in track.notes.indices) return
    val expectedNote = track.notes[expectedIndex]

    val pos = mode.violinPositions.getOrNull(expectedIndex)
    val string = pos?.string ?: ViolinAlgorithm.nearestString(expectedNote.noteNumber)
    val bandIndex = string.ordinal
    val bandWidth = size.width / VIOLIN_LANE_COUNT
    val bandLeft = bandIndex * bandWidth

    val stringLineX = bandLeft + bandWidth / 2f
    val deviation = if (pos == null) 0f else
        (ViolinAlgorithm.fingerOffsetDeviation(expectedNote.noteNumber, string, pos.finger) ?: 0f).toFloat()
    val centerX = stringLineX + deviation * bandWidth * VIOLIN_FINGER_OFFSET_FRACTION

    val indicatorColor = getIndicatorColor(detectedMidi, mode.detectedCentsOff, track, expectedIndex)

    val indicatorW = (bandWidth * 0.1f).coerceAtLeast(10f * density)
    val indicatorH = 6f * density
    drawRoundRect(
        color = indicatorColor,
        topLeft = Offset(centerX - indicatorW / 2f, size.height - indicatorH),
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
    val gapThreshold = 0.05f

    val path = Path()
    for (i in centsHistory.indices) {
        val t = centsPositions[i].coerceIn(0f, 1f)
        val normalized = (centsHistory[i] / 50f).coerceIn(-1f, 1f)
        val x = centerX + normalized * maxDeviationPx
        val y = barTop + t * (barBottom - barTop)
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

// endregion

// region Wiggly pitch line

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
    val gapThreshold = 0.05f

    for (i in centsHistory.indices) {
        val t = centsPositions[i].coerceIn(0f, 1f)
        val normalized = (centsHistory[i] / 50f).coerceIn(-1f, 1f)
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
    currentNoteIndex: Int
): Color {
    if (currentNoteIndex !in melodyTrack.notes.indices) return Color.White.copy(alpha = 0.5f)
    val expectedNote = melodyTrack.notes[currentNoteIndex]

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
