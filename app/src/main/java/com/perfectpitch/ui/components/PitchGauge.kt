package com.perfectpitch.ui.components

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.perfectpitch.ui.utils.centsColor
import kotlin.math.min

/** Semi-circular pitch gauge showing cents offset from -50 to +50. */
@Composable
fun PitchGauge(
    centsOff: Float,
    noteName: String,
    octave: Int,
    isSilent: Boolean,
    modifier: Modifier = Modifier,
    maxCents: Float = 50f
) {
    val clampedCents = centsOff.coerceIn(-maxCents, maxCents)
    val normalizedPosition by animateFloatAsState(
        targetValue = (clampedCents + maxCents) / (2 * maxCents),
        animationSpec = tween(durationMillis = 100),
        label = "gauge_position"
    )

    val gaugeColor by animateColorAsState(
        targetValue = centsColor(centsOff),
        animationSpec = tween(durationMillis = 200),
        label = "gauge_color"
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

            drawArc(
                color = Color.Gray.copy(alpha = 0.3f),
                startAngle = 180f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(arcLeft, arcTop),
                size = Size(arcWidth, arcHeight),
                style = Stroke(width = 12f, cap = StrokeCap.Round)
            )

            val indicatorSweep = 60f
            drawArc(
                color = gaugeColor.copy(alpha = 0.4f),
                startAngle = 180f + 90f - indicatorSweep / 2,
                sweepAngle = indicatorSweep, useCenter = false,
                topLeft = Offset(arcLeft, arcTop),
                size = Size(arcWidth, arcHeight),
                style = Stroke(width = 12f, cap = StrokeCap.Round)
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
            drawCircle(color = gaugeColor, radius = 8f, center = Offset(centerX, centerY))
        }

        if (isSilent) {
            Text(
                text = "—",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = noteName,
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "$octave",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}
