package net.exent.harmonisk.ui.freebird

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import net.exent.harmonisk.midi.KeySignature
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.NoteUtils
import net.exent.harmonisk.music.PitchResult
import net.exent.harmonisk.ui.theme.FingerColors
import kotlin.math.abs
import kotlin.math.sin

private val GoodShimmer = Color(0xFFE8FFF2)
private val EdgeShimmer = Color(0xFFFFE6A3)
private val NoteInk = Color(0xFF152820)

@Composable
fun FreeBirdLetters(
    instrument: Instrument,
    letters: List<FreeBirdLetter>,
    pitch: PitchResult,
    modifier: Modifier = Modifier
) {
    if (letters.isEmpty()) return
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val layout = remember(width, height, instrument) {
            freeBirdStaffLayout(width, height, instrument, density)
        }
        val key = FreeBirdKey
        val textMeasurer = rememberTextMeasurer()
        val fontSize = with(density) { (width * 0.042f).toSp() }
        val headRadius = width * FREEBIRD_NOTE_RADIUS
        val textCache = remember(width, density, textMeasurer) {
            mutableMapOf<Pair<String, Color>, TextLayoutResult>()
        }
        Canvas(Modifier.fillMaxSize()) {
            val next = letters.filterNot { it.judged }.minByOrNull { it.x }
            if (next != null) {
                val nextY = freeBirdNoteY(layout, next.midi, key, height)
                drawLine(
                    color = FingerColors[next.finger].copy(alpha = 0.13f),
                    start = Offset(width * BIRD_PERCH_X, nextY),
                    end = Offset(width * next.x, nextY),
                    strokeWidth = with(density) { 2.dp.toPx() }
                )
            }

            letters.forEach { letter ->
                val x = width * letter.x
                if (x < -width * 0.1f || x > width * 1.15f) return@forEach
                val spelling = freeBirdSpelling(letter.midi, key)
                val y = freeBirdNoteY(layout, letter.midi, key, height)
                val color = FingerColors[letter.finger]
                val ink = if (color.luminance() > 0.2f) NoteInk else Color.White
                val name = NoteUtils.resolveMidiNote(spelling.naturalMidi).noteName
                val text = textCache.getOrPut(name to ink) {
                    textMeasurer.measure(
                        AnnotatedString(name),
                        TextStyle(fontSize = fontSize, fontWeight = FontWeight.Black, color = ink)
                    )
                }
                val center = Offset(x, y)

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            color.copy(alpha = 0.16f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = headRadius * 2.4f
                    ),
                    radius = headRadius * 2.4f,
                    center = center
                )

                val targetCents = if (pitch.isSilent) 0f else centsFromTarget(pitch, letter.midi)
                if (!letter.judged && !pitch.isSilent && abs(targetCents) <= 150f) {
                    val inTune = abs(targetCents) <= 25f
                    val shimmer = 0.8f + 0.2f * sin(letter.x * 30f + letter.id * 0.07f)
                    val shimmerColor = if (inTune) GoodShimmer else EdgeShimmer
                    if (inTune) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    shimmerColor.copy(alpha = 0.70f * shimmer),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = headRadius * 3f
                            ),
                            radius = headRadius * 3f,
                            center = center
                        )
                        val drift = sin(letter.x * 30f + letter.id * 0.07f) * headRadius * 0.06f
                        sparkle(Offset(x - headRadius * 1.05f, y - headRadius * 0.85f + drift), headRadius * 0.26f, shimmerColor, shimmer)
                        sparkle(Offset(x + headRadius * 1.05f, y - headRadius * 0.85f + drift), headRadius * 0.26f, shimmerColor, shimmer)
                        sparkle(Offset(x - headRadius * 1.05f, y + headRadius * 0.85f - drift), headRadius * 0.22f, shimmerColor, 0.9f * shimmer)
                        sparkle(Offset(x + headRadius * 1.05f, y + headRadius * 0.85f - drift), headRadius * 0.22f, shimmerColor, 0.9f * shimmer)
                    } else {
                        val below = targetCents < 0f
                        val edgeY = y + if (below) headRadius * 1.6f else -headRadius * 1.6f
                        drawLine(
                            color = shimmerColor.copy(alpha = 0.52f * shimmer),
                            start = Offset(x - headRadius * 0.9f, edgeY),
                            end = Offset(x + headRadius * 0.9f, edgeY),
                            strokeWidth = headRadius * 0.22f,
                            cap = StrokeCap.Round
                        )
                        repeat(3) { index ->
                            sparkle(
                                Offset(x + (index - 1) * headRadius * 0.62f, edgeY),
                                headRadius * 0.22f,
                                shimmerColor,
                                shimmer
                            )
                        }
                    }
                }

                val stemX = x + headRadius * 0.78f
                val stemTop = y - headRadius * 3.4f
                val stemWidth = headRadius * 0.2f
                drawLine(
                    color = NoteInk.copy(alpha = 0.25f),
                    start = Offset(stemX + with(density) { 1.dp.toPx() }, y + with(density) { 1.dp.toPx() }),
                    end = Offset(stemX + with(density) { 1.dp.toPx() }, stemTop + with(density) { 1.dp.toPx() }),
                    strokeWidth = stemWidth,
                    cap = StrokeCap.Round
                )
                drawLine(color, Offset(stemX, y), Offset(stemX, stemTop), strokeWidth = stemWidth, cap = StrokeCap.Round)
                drawCircle(color, headRadius, center)
                drawCircle(NoteInk.copy(alpha = 0.3f), headRadius, center, style = Stroke(headRadius * 0.08f))
                val letterAlteration = KeySignature.keyAlteration(
                    ((spelling.naturalMidi % 12) + 12) % 12,
                    key.fifths
                )
                val accidental = spelling.accidental != letterAlteration
                if (accidental) {
                    drawAccidentalSign(
                        Offset(x + headRadius * 0.45f, y),
                        headRadius * 1.25f,
                        spelling.accidental.coerceIn(-1, 1),
                        ink
                    )
                }
                drawText(
                    text,
                    topLeft = Offset(
                        x - text.size.width / 2f - if (accidental) headRadius * 0.3f else 0f,
                        y - text.size.height / 2f
                    )
                )
            }
        }
    }
}

private fun DrawScope.sparkle(center: Offset, radius: Float, color: Color, alpha: Float) {
    val paint = color.copy(alpha = alpha)
    val thickness = (radius * 0.35f).coerceAtLeast(1f)
    drawLine(paint, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), thickness, StrokeCap.Round)
    drawLine(paint, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), thickness, StrokeCap.Round)
}
