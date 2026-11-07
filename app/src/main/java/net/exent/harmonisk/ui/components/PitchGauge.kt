package net.exent.harmonisk.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import net.exent.harmonisk.music.NoteUtils
import net.exent.harmonisk.ui.theme.TunerAmber
import net.exent.harmonisk.ui.theme.TunerGreen
import net.exent.harmonisk.ui.theme.Yellow80
import net.exent.harmonisk.ui.utils.centsColor
import kotlin.math.abs
import kotlin.math.min

/** Semi-circular pitch gauge showing cents offset from -50 to +50. */
@Composable
fun PitchGauge(
    centsOff: Float,
    noteName: String,
    octave: Int,
    isSilent: Boolean,
    isInTune: Boolean = false,
    modifier: Modifier = Modifier,
    maxCents: Float = NoteUtils.MAX_CENTS_OFF,
    inTuneCents: Float = NoteUtils.IN_TUNE_CENTS
) {
    val clampedCents = centsOff.coerceIn(-maxCents, maxCents)
    // Fine smoothing lives in PitchStabilizer; this tween only interpolates between frames, so
    // shortening it costs needle steadiness without making the reading any more accurate.
    val normalizedPosition by animateFloatAsState(
        targetValue = (clampedCents + maxCents) / (2 * maxCents),
        animationSpec = tween(durationMillis = 100),
        label = "gauge_position"
    )

    val gaugeColor by animateColorAsState(
        targetValue = when {
            isSilent -> Color.Gray.copy(alpha = 0.5f)
            isInTune -> TunerGreen
            abs(centsOff) <= NoteUtils.PERFECT_CENTS -> Yellow80
            else -> centsColor(centsOff)
        },
        animationSpec = tween(durationMillis = 200),
        label = "gauge_color"
    )
    val noteColor by animateColorAsState(
        targetValue = if (isInTune) TunerGreen else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(durationMillis = 200),
        label = "note_color"
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (isInTune) 0.30f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "gauge_glow"
    )
    val bandAlpha by animateFloatAsState(
        targetValue = if (isInTune) 0.2f else 0.9f,
        animationSpec = tween(durationMillis = 250),
        label = "gauge_band"
    )
    val pivotRadius by animateFloatAsState(
        targetValue = if (isInTune) 10f else 7f,
        animationSpec = tween(durationMillis = 200),
        label = "gauge_pivot"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            val arcWidth = size.width * 0.8f
            val arcHeight = size.height * 1.5f
            val arcLeft = (size.width - arcWidth) / 2
            val arcTop = size.height * 0.1f

            if (glowAlpha > 0f) {
                drawArc(
                    color = TunerGreen.copy(alpha = glowAlpha),
                    startAngle = 180f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(arcLeft, arcTop),
                    size = Size(arcWidth, arcHeight),
                    style = Stroke(width = 26f, cap = StrokeCap.Round)
                )
            }

            drawArc(
                color = Color.Gray.copy(alpha = 0.3f),
                startAngle = 180f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(arcLeft, arcTop),
                size = Size(arcWidth, arcHeight),
                style = Stroke(width = 12f, cap = StrokeCap.Round)
            )

            val indicatorSweep = 60f
            drawArc(
                color = TunerAmber.copy(alpha = bandAlpha),
                startAngle = 180f + 90f - indicatorSweep / 2,
                sweepAngle = indicatorSweep, useCenter = false,
                topLeft = Offset(arcLeft, arcTop),
                size = Size(arcWidth, arcHeight),
                style = Stroke(width = 12f, cap = StrokeCap.Round)
            )

            // The in-tune window: marking it on the arc shows how close the needle must be.
            val inTuneSweep = (inTuneCents / maxCents) * 90f
            drawArc(
                color = TunerGreen,
                startAngle = 180f + 90f - inTuneSweep,
                sweepAngle = inTuneSweep * 2f, useCenter = false,
                topLeft = Offset(arcLeft, arcTop),
                size = Size(arcWidth, arcHeight),
                style = Stroke(width = 16f, cap = StrokeCap.Round)
            )

            val needleAngle = 180f + normalizedPosition * 180f
            val needleRadians = Math.toRadians(needleAngle.toDouble())
            val needleLength = min(arcWidth / 2 * 0.7f, arcHeight / 2 * 0.9f)
            val centerX = size.width / 2
            val centerY = arcTop + arcHeight / 2

            drawLine(
                color = gaugeColor,
                start = Offset(centerX, centerY),
                end = Offset(
                    centerX + (needleLength * kotlin.math.cos(needleRadians)).toFloat(),
                    centerY + (needleLength * kotlin.math.sin(needleRadians)).toFloat()
                ),
                strokeWidth = 4f,
                cap = StrokeCap.Round
            )
            drawCircle(color = gaugeColor, radius = pivotRadius, center = Offset(centerX, centerY))
        }

        Box(
            modifier = Modifier.height(48.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isSilent) {
                Text(
                    text = "—",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = noteName,
                        style = MaterialTheme.typography.headlineLarge,
                        color = noteColor
                    )
                    Text(
                        text = "$octave",
                        style = MaterialTheme.typography.titleMedium,
                        color = noteColor.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
