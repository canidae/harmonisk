package net.exent.harmonisk.ui.melody

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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.exent.harmonisk.R
import net.exent.harmonisk.data.ScoreHistoryRepository.ScoreEntry
import net.exent.harmonisk.ui.theme.Purple40
import net.exent.harmonisk.ui.utils.scoreColor

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
    val levelUpColor = MaterialTheme.colorScheme.tertiary
    val levelReplayColor = MaterialTheme.colorScheme.outline

    val density = LocalDensity.current
    val resources = LocalResources.current
    val labelSize = if (compact) 9.sp else 11.sp
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

            // Level-change markers, drawn behind the data. Replays of an older level
            // get a lighter, shorter dash and a downward arrow so a step back is
            // distinct from unlocking the next level.
            for (change in levelChanges(scores)) {
                val markerColor = if (change.isReplay) levelReplayColor else levelUpColor
                val dashHeight = if (change.isReplay) 3f else 5f
                val dashGap = if (change.isReplay) 6f else 4f
                val x = points[change.index].x
                var y = chartTop
                while (y < chartTop + chartHeight) {
                    drawLine(
                        color = markerColor.copy(alpha = 0.7f),
                        start = Offset(x, y),
                        end = Offset(x, (y + dashHeight).coerceAtMost(chartTop + chartHeight)),
                        strokeWidth = if (change.isReplay) 1f else 1.5f
                    )
                    y += dashHeight + dashGap
                }

                val t = if (scores.size == 1) 1f else change.index.toFloat() / (scores.size - 1)
                val nearRight = t > 0.75f
                drawContext.canvas.nativeCanvas.drawText(
                    resources.getString(
                        if (change.isReplay) R.string.melody_level_replay else R.string.melody_level_up,
                        change.level
                    ),
                    if (nearRight) x - 3f else x + 3f,
                    chartTop + with(density) { labelSize.toPx() },
                    android.graphics.Paint().apply {
                        color = markerColor.hashCode()
                        this.textSize = with(density) { labelSize.toPx() }
                        textAlign = if (nearRight) android.graphics.Paint.Align.RIGHT
                        else android.graphics.Paint.Align.LEFT
                    }
                )
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

/** A run played at a different level than the run before, where the graph marks a level change. */
data class LevelChange(val index: Int, val level: Int, val previousLevel: Int) {
    val isReplay: Boolean get() = level < previousLevel
}

fun levelChanges(scores: List<ScoreEntry>): List<LevelChange> =
    scores.zipWithNext().mapIndexedNotNull { index, (previous, current) ->
        if (current.level == previous.level) null
        else LevelChange(index + 1, current.level, previous.level)
    }