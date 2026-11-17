package net.exent.harmonisk.ui.freebird

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.exent.harmonisk.R
import net.exent.harmonisk.music.BowedAlgorithm
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.PitchResult
import kotlin.math.max
import kotlin.math.min

private const val TREBLE_CLEF = "\uD834\uDD1E"
private const val BASS_CLEF = "\uD834\uDD22"
private const val MIDDLE_C_DIATONIC = 35
private const val STAFF_MARGIN_STEPS = 2
private const val STAFF_HALF_STEPS = 10
private const val CLEF_UNITS_PER_EM = 2048f
private const val G_CLEF_SPIRAL_Y = 385f
private const val F_CLEF_DOT_MID_Y = 1320f
private const val F_CLEF_DOT_X = 1406f
private const val F_CLEF_DOT_RADIUS = 112f
private const val F_CLEF_DOT_OFFSET_Y = 226f
private const val G_CLEF_WIDTH_SCALE = 0.66f
private const val F_CLEF_WIDTH_SCALE = 0.55f

private val ClefFont = FontFamily(Font(R.font.freebird_clefs))

private val StaffInk = Color(0xFFDCE0D9).copy(alpha = 0.52f)
private val StaffShadow = Color(0xFF18291F).copy(alpha = 0.12f)
private val NOTE_DEGREE = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
private val PITCH_STEP = floatArrayOf(0f, 0.5f, 1f, 1.5f, 2f, 3f, 3.5f, 4f, 4.5f, 5f, 5.5f, 6f)

data class FreeBirdStaffLayout(
    val step: Float,
    val middleC: Float,
    val clefX: Float,
    val staffEnd: Float
)

fun freeBirdStaffLayout(
    width: Float,
    height: Float,
    instrument: Instrument,
    level: Int,
    density: Density
): FreeBirdStaffLayout {
    val range = levelRange(instrument, level)
    val topSteps = max(STAFF_HALF_STEPS.toFloat(), (diatonic(range.last) - MIDDLE_C_DIATONIC + STAFF_MARGIN_STEPS).toFloat())
    val bottomSteps = max(STAFF_HALF_STEPS.toFloat(), (MIDDLE_C_DIATONIC - diatonic(range.first) + STAFF_MARGIN_STEPS).toFloat())
    val available = height * 0.52f
    val step = min(with(density) { 26.dp.toPx() }, available / (topSteps + bottomSteps))
    val origin = height * 0.16f + (available - (topSteps + bottomSteps) * step) / 2f
    return FreeBirdStaffLayout(
        step = step,
        middleC = origin + topSteps * step,
        clefX = with(density) { 12.dp.toPx() },
        staffEnd = width - with(density) { 14.dp.toPx() }
    )
}

fun freeBirdNoteY(layout: FreeBirdStaffLayout, midi: Int, height: Float): Float {
    val notePosition = notePosition(midi, 0f)
    return (layout.middleC - (notePosition - MIDDLE_C_DIATONIC) * layout.step)
        .coerceIn(height * 0.10f, height * 0.78f)
}

fun freeBirdPitchY(layout: FreeBirdStaffLayout, pitch: PitchResult, height: Float): Float {
    val position = notePosition(pitch.midiNoteNumber, pitch.centsOff)
    return (layout.middleC - (position - MIDDLE_C_DIATONIC) * layout.step)
        .coerceIn(height * 0.10f, height * 0.78f)
}

private fun notePosition(midi: Int, cents: Float): Float {
    val pitchClass = ((midi % 12) + 12) % 12
    return (midi / 12) * 7 + PITCH_STEP[pitchClass] + cents / 100f * 0.5f
}

@Composable
fun FreeBirdStaff(
    instrument: Instrument,
    level: Int = 1,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier) {
        val layout = freeBirdStaffLayout(size.width, size.height, instrument, level, density)
        val step = layout.step
        val middleC = layout.middleC
        val staffStart = 0f
        val staffEnd = layout.staffEnd
        val lineWidth = with(density) { 1.5.dp.toPx() }
        val shadowWidth = with(density) { 2.6.dp.toPx() }
        val lineGap = step * 2f

        fun line(y: Float) {
            drawLine(
                color = StaffShadow,
                start = Offset(staffStart, y + with(density) { 1.2.dp.toPx() }),
                end = Offset(staffEnd, y + with(density) { 1.2.dp.toPx() }),
                strokeWidth = shadowWidth
            )
            drawLine(
                color = StaffInk,
                start = Offset(staffStart, y),
                end = Offset(staffEnd, y),
                strokeWidth = lineWidth
            )
        }

        repeat(5) { index ->
            line(middleC - STAFF_HALF_STEPS * step + index * lineGap)
        }
        repeat(5) { index ->
            line(middleC + 2 * step + index * lineGap)
        }

        drawLine(
            color = StaffInk.copy(alpha = 0.22f),
            start = Offset(staffStart, middleC),
            end = Offset(staffEnd, middleC),
            strokeWidth = lineWidth
        )

        val treblePx = step * 7.5f
        val bassPx = step * 9f
        val trebleSize = (treblePx / density.density / density.fontScale).sp
        val bassSize = (bassPx / density.density / density.fontScale).sp
        val trebleStyle = TextStyle(fontFamily = ClefFont, fontSize = trebleSize, color = StaffInk)
        val trebleShadowStyle = TextStyle(fontFamily = ClefFont, fontSize = trebleSize, color = StaffShadow)
        val bassStyle = TextStyle(fontFamily = ClefFont, fontSize = bassSize, color = StaffInk)
        val bassShadowStyle = TextStyle(fontFamily = ClefFont, fontSize = bassSize, color = StaffShadow)
        val treble = textMeasurer.measure(AnnotatedString(TREBLE_CLEF), trebleStyle)
        val trebleShadow = textMeasurer.measure(AnnotatedString(TREBLE_CLEF), trebleShadowStyle)
        val bass = textMeasurer.measure(AnnotatedString(BASS_CLEF), bassStyle)
        val bassShadow = textMeasurer.measure(AnnotatedString(BASS_CLEF), bassShadowStyle)
        val clefX = layout.clefX
        val shadowOffset = with(density) { 1.2.dp.toPx() }
        val trebleTop = middleC - 4 * step + G_CLEF_SPIRAL_Y * treblePx / CLEF_UNITS_PER_EM - treble.firstBaseline
        val bassTop = middleC + 4 * step + F_CLEF_DOT_MID_Y * bassPx / CLEF_UNITS_PER_EM - bass.firstBaseline
        val trebleTarget = middleC - 4 * step
        val bassTarget = middleC + 4 * step
        withTransform({
            scale(G_CLEF_WIDTH_SCALE, 1f, pivot = Offset(clefX, trebleTarget))
        }) {
            drawText(trebleShadow, topLeft = Offset(clefX, trebleTop + shadowOffset))
            drawText(treble, topLeft = Offset(clefX, trebleTop))
        }
        withTransform({
            scale(F_CLEF_WIDTH_SCALE, 1f, pivot = Offset(clefX, bassTarget))
        }) {
            drawText(bassShadow, topLeft = Offset(clefX, bassTop + shadowOffset))
            drawText(bass, topLeft = Offset(clefX, bassTop))
        }
        val bassScale = bassPx / CLEF_UNITS_PER_EM
        val dotRadius = F_CLEF_DOT_RADIUS * bassScale * 0.95f
        val dotOffset = F_CLEF_DOT_OFFSET_Y * bassScale
        val dotX = clefX + F_CLEF_DOT_X * bassScale * F_CLEF_WIDTH_SCALE
        drawCircle(StaffShadow, dotRadius, Offset(dotX, bassTarget - dotOffset + shadowOffset))
        drawCircle(StaffInk, dotRadius, Offset(dotX, bassTarget - dotOffset))
        drawCircle(StaffShadow, dotRadius, Offset(dotX, bassTarget + dotOffset + shadowOffset))
        drawCircle(StaffInk, dotRadius, Offset(dotX, bassTarget + dotOffset))
    }
}

fun levelRange(instrument: Instrument, level: Int): IntRange {
    val fingers = if (level >= FREEBIRD_MAX_LEVEL) 0..4 else listOf((level - 1).coerceIn(0, 4))
    val midis = fingers.flatMap { finger ->
        val offset = BowedAlgorithm.defaultFingerOffset(instrument, finger) ?: return@flatMap emptyList()
        instrument.tuning.map { it + offset }
    }
    return midis.min()..midis.max()
}

fun diatonic(midi: Int): Int = (midi / 12) * 7 + NOTE_DEGREE[midi % 12]
