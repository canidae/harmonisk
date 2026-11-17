package net.exent.harmonisk.ui.freebird

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.NoteUtils
import net.exent.harmonisk.music.PitchResult
import net.exent.harmonisk.ui.theme.FingerColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private val GoodShimmer = Color(0xFFE8FFF2)
private val EdgeShimmer = Color(0xFFFFE6A3)

@Composable
fun FreeBirdLetters(
    instrument: Instrument,
    letters: List<FreeBirdLetter>,
    level: Int,
    pitch: PitchResult,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val layout = remember(width, height, instrument, level) {
            freeBirdStaffLayout(width, height, instrument, level, density)
        }
        val textMeasurer = rememberTextMeasurer()
        val transition = rememberInfiniteTransition(label = "freebird_letters")
        val pulse by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "freebird_letters_pulse"
        )
        Canvas(Modifier.fillMaxSize()) {
            val cycle = sin(pulse * 2f * PI.toFloat())
            val next = letters.filterNot { it.judged }.minByOrNull { it.x }
            if (next != null) {
                val nextY = freeBirdNoteY(layout, next.midi, height)
                drawLine(
                    color = FingerColors[next.finger].copy(alpha = 0.13f),
                    start = Offset(width * BRANCH_PERCH_X, nextY),
                    end = Offset(width * next.x, nextY),
                    strokeWidth = with(density) { 2.dp.toPx() }
                )
            }

            letters.forEach { letter ->
                val x = width * letter.x
                if (x < -width * 0.1f || x > width * 1.15f) return@forEach
                val y = freeBirdNoteY(layout, letter.midi, height)
                val color = FingerColors[letter.finger]
                val note = NoteUtils.resolveMidiNote(letter.midi)
                val label = note.noteName + note.octave
                val style = TextStyle(
                    fontSize = with(density) { (width * 0.055f).toSp() },
                    fontWeight = FontWeight.Black,
                    color = color
                )
                val shadowStyle = TextStyle(
                    fontSize = with(density) { (width * 0.055f).toSp() },
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF17301F).copy(alpha = 0.55f)
                )
                val text = textMeasurer.measure(AnnotatedString(label), style)
                val shadow = textMeasurer.measure(AnnotatedString(label), shadowStyle)
                val center = Offset(x, y)
                val textWidth = text.size.width.toFloat()
                val textHeight = text.size.height.toFloat()

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            color.copy(alpha = 0.16f * (0.8f + 0.2f * cycle)),
                            Color.Transparent
                        ),
                        center = center,
                        radius = textHeight * 0.95f
                    ),
                    radius = textHeight * 0.95f,
                    center = center
                )

                val targetCents = if (pitch.isSilent) 0f else centsFromTarget(pitch, letter.midi)
                if (!letter.judged && !pitch.isSilent && abs(targetCents) <= 150f) {
                    val inTune = abs(targetCents) <= 25f
                    val shimmer = 0.8f + 0.2f * sin((pulse + letter.id * 0.07f) * 2f * PI.toFloat())
                    val shimmerColor = if (inTune) GoodShimmer else EdgeShimmer
                    if (inTune) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    shimmerColor.copy(alpha = 0.70f * shimmer),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = textHeight * 1.3f
                            ),
                            radius = textHeight * 1.3f,
                            center = center
                        )
                        val drift = sin((pulse + letter.id * 0.07f) * 2f * PI.toFloat()) * textHeight * 0.025f
                        sparkle(Offset(x - textWidth * 0.34f, y - textHeight * 0.32f + drift), textHeight * 0.10f, shimmerColor, 1f * shimmer)
                        sparkle(Offset(x + textWidth * 0.34f, y - textHeight * 0.32f + drift), textHeight * 0.10f, shimmerColor, 1f * shimmer)
                        sparkle(Offset(x - textWidth * 0.34f, y + textHeight * 0.3f - drift), textHeight * 0.085f, shimmerColor, 0.9f * shimmer)
                        sparkle(Offset(x + textWidth * 0.34f, y + textHeight * 0.3f - drift), textHeight * 0.085f, shimmerColor, 0.9f * shimmer)
                    } else {
                        val below = targetCents < 0f
                        val edgeY = y + if (below) textHeight * 0.56f else -textHeight * 0.56f
                        drawLine(
                            color = shimmerColor.copy(alpha = 0.52f * shimmer),
                            start = Offset(x - textWidth * 0.34f, edgeY),
                            end = Offset(x + textWidth * 0.34f, edgeY),
                            strokeWidth = textHeight * 0.16f,
                            cap = StrokeCap.Round
                        )
                        repeat(3) { index ->
                            sparkle(
                                Offset(x + (index - 1) * textWidth * 0.23f, edgeY),
                                textHeight * 0.085f,
                                shimmerColor,
                                1f * shimmer
                            )
                        }
                    }
                }

                drawText(
                    shadow,
                    topLeft = Offset(
                        x - textWidth / 2f + with(density) { 1.5.dp.toPx() },
                        y - textHeight / 2f + with(density) { 2.dp.toPx() }
                    )
                )
                drawText(
                    text,
                    topLeft = Offset(x - textWidth / 2f, y - textHeight / 2f)
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
