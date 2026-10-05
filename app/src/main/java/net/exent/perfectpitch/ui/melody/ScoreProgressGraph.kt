package net.exent.perfectpitch.ui.melody

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.exent.perfectpitch.R
import net.exent.perfectpitch.data.ScoreHistoryRepository.ScoreEntry
import net.exent.perfectpitch.ui.theme.Purple40
import net.exent.perfectpitch.ui.utils.scoreColor

@Composable
fun ScoreProgressGraph(
    scores: List<ScoreEntry>,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    if (scores.isEmpty()) return

    val lineColor = Purple40
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val levelLineColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)

    val density = LocalDensity.current
    val labelSize = if (compact) 9.sp else 11.sp
    val labelHeight = with(density) { labelSize.toDp() }
    val bottomPadding = if (compact) 16.dp else 24.dp
    val leftPadding = if (compact) 24.dp else 32.dp

    Column(modifier = modifier) {
        if (!compact) {
            Text(
                stringResource(R.string.score_progress),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        val scoreMin = 0f
        val scoreMax = 100f

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 100.dp else 150.dp)
                .padding(start = leftPadding, end = 8.dp, bottom = bottomPadding)
        ) {
            val chartLeft = 0f
            val chartTop = 0f
            val chartWidth = size.width
            val chartHeight = size.height

            // Grid lines and Y-axis labels
            val gridSteps = 4
            for (i in 0..gridSteps) {
                val y = chartTop + chartHeight * (1f - i.toFloat() / gridSteps)
                val scoreVal = scoreMin + (scoreMax - scoreMin) * i / gridSteps

                drawLine(
                    color = gridColor,
                    start = Offset(chartLeft, y),
                    end = Offset(chartWidth, y),
                    strokeWidth = 1f
                )

                drawContext.canvas.nativeCanvas.drawText(
                    "%.0f".format(scoreVal),
                    -8f,
                    y + with(density) { 4.sp.toPx() },
                    android.graphics.Paint().apply {
                        color = textColor.hashCode()
                        this.textSize = with(density) { labelSize.toPx() }
                        textAlign = android.graphics.Paint.Align.RIGHT
                    }
                )
            }

            // Plot points
            val points = scores.mapIndexed { index, entry ->
                val t = if (scores.size == 1) 1f else index.toFloat() / (scores.size - 1)
                val x = chartLeft + chartWidth * t
                val y = chartTop + chartHeight * (1f - (entry.overallScore - scoreMin) / (scoreMax - scoreMin))
                Offset(x, y.coerceIn(chartTop, chartTop + chartHeight))
            }

            // Level-change markers, drawn behind the data
            for (i in levelChangeIndices(scores)) {
                val x = points[i].x
                var y = chartTop
                while (y < chartTop + chartHeight) {
                    drawLine(
                        color = levelLineColor,
                        start = Offset(x, y),
                        end = Offset(x, (y + 5f).coerceAtMost(chartTop + chartHeight)),
                        strokeWidth = 1.5f
                    )
                    y += 9f
                }

                if (!compact) {
                    val t = if (scores.size == 1) 1f else i.toFloat() / (scores.size - 1)
                    val nearRight = t > 0.75f
                    drawContext.canvas.nativeCanvas.drawText(
                        "Lv.${scores[i].level}",
                        if (nearRight) x - 3f else x + 3f,
                        chartTop + with(density) { labelSize.toPx() },
                        android.graphics.Paint().apply {
                            color = textColor.hashCode()
                            this.textSize = with(density) { labelSize.toPx() }
                            textAlign = if (nearRight) android.graphics.Paint.Align.RIGHT
                            else android.graphics.Paint.Align.LEFT
                        }
                    )
                }
            }

            // Draw connecting line
            if (points.size >= 2) {
                val path = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) {
                        lineTo(points[i].x, points[i].y)
                    }
                }
                drawPath(
                    path = path,
                    color = lineColor,
                    style = Stroke(
                        width = if (compact) 2f else 3f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }

            // Draw dots
            val dotRadius = if (compact) 5f else 6f
            for (i in points.indices) {
                val dotColor = scoreColor(scores[i].overallScore)
                drawCircle(dotColor, dotRadius, points[i])
                drawCircle(Color.White, dotRadius * 0.5f, points[i])
            }

            // X-axis labels
            if (!compact) {
                val step = when {
                    scores.size <= 6 -> 1
                    scores.size <= 12 -> 2
                    else -> (scores.size / 6)
                }
                for (i in scores.indices step step) {
                    val t = if (scores.size == 1) 1f else i.toFloat() / (scores.size - 1)
                    val x = chartLeft + chartWidth * t
                    drawContext.canvas.nativeCanvas.drawText(
                        "#${i + 1}",
                        x,
                        chartTop + chartHeight + with(density) { 14.sp.toPx() },
                        android.graphics.Paint().apply {
                            color = textColor.hashCode()
                            this.textSize = with(density) { labelSize.toPx() }
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                    )
                }
            }
        }
    }
}

/** Runs played at a different level than the run before, where the graph marks a level change. */
fun levelChangeIndices(scores: List<ScoreEntry>): List<Int> =
    scores.indices.filter { it > 0 && scores[it].level != scores[it - 1].level }