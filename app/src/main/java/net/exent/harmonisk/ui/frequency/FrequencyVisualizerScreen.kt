package net.exent.harmonisk.ui.frequency

import android.graphics.Paint
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.exent.harmonisk.R
import net.exent.harmonisk.audio.FrequencyTraceFrame
import net.exent.harmonisk.audio.RecordingExporter
import net.exent.harmonisk.music.NoteUtils
import net.exent.harmonisk.ui.adventure.AdventureBackdrop
import net.exent.harmonisk.ui.components.BrandTopBar
import net.exent.harmonisk.ui.components.KeepScreenOn
import net.exent.harmonisk.ui.components.RequireMicPermission
import java.util.Locale
import kotlin.math.ln

private const val MIN_FREQUENCY = 40f
private const val MAX_FREQUENCY = 2500f
private val GRAPH_LEFT = 38.dp
private val GRAPH_RIGHT = 8.dp
private val GRAPH_TOP = 8.dp
private val GRAPH_BOTTOM = 24.dp

@Composable
fun FrequencyVisualizerScreen(
    onBack: () -> Unit,
    viewModel: FrequencyVisualizerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    KeepScreenOn()

    LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        Toast.makeText(
            context,
            if (error == FrequencyVisualizerError.EXPORT_FAILED) {
                R.string.frequency_visualizer_save_failed
            } else {
                R.string.frequency_visualizer_error
            },
            Toast.LENGTH_SHORT
        ).show()
        viewModel.clearError()
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stop() }
    }

    AdventureBackdrop(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
                BrandTopBar(
                    title = stringResource(R.string.frequency_visualizer_title),
                    onBack = onBack
                )
            }
        ) { padding ->
            RequireMicPermission {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .navigationBarsPadding()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        PitchGraph(
                            frames = state.frames,
                            elapsedMs = state.elapsedMs,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = statusText(state),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.lastFrequency > 0f) {
                            Text(
                                text = stringResource(
                                    R.string.frequency_visualizer_last,
                                    String.format(Locale.US, "%.1f", state.lastFrequency),
                                    String.format(Locale.US, "%.2f", state.lastConfidence)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = viewModel::start,
                            enabled = !state.recording && !state.finalizing,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.start))
                        }
                        OutlinedButton(
                            onClick = viewModel::stop,
                            enabled = state.recording,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.stop))
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val file = viewModel.exportFile() ?: return@OutlinedButton
                            scope.launch {
                                val saved = RecordingExporter.download(
                                    context,
                                    file,
                                    RecordingExporter.fileName("Harmonisk-frequency", "zip")
                                )
                                Toast.makeText(
                                    context,
                                    if (saved) {
                                        R.string.frequency_visualizer_saved
                                    } else {
                                        R.string.frequency_visualizer_save_failed
                                    },
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        enabled = state.exportReady,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.frequency_visualizer_download))
                    }
                }
            }
        }
    }
}

@Composable
private fun statusText(state: FrequencyVisualizerState): String = when {
    state.finalizing -> stringResource(R.string.frequency_visualizer_preparing)
    state.recording -> stringResource(
        R.string.frequency_visualizer_recording,
        (state.elapsedMs / 1000L).toInt()
    )
    state.exportReady -> stringResource(R.string.frequency_visualizer_ready)
    else -> stringResource(R.string.frequency_visualizer_idle)
}

@Composable
private fun PitchGraph(
    frames: List<FrequencyTraceFrame>,
    elapsedMs: Long,
    modifier: Modifier
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val labelSize = 10.sp
    val labelSizePx = with(density) { labelSize.toPx() }
    val hasPitch = frames.any { it.frequencyHz > 0f }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val left = GRAPH_LEFT.toPx()
            val right = GRAPH_RIGHT.toPx()
            val top = GRAPH_TOP.toPx()
            val bottom = GRAPH_BOTTOM.toPx()
            val chartWidth = size.width - left - right
            val chartHeight = size.height - top - bottom
            if (chartWidth <= 0f || chartHeight <= 0f) return@Canvas

            val spanMs = maxOf(elapsedMs, frames.lastOrNull()?.timeMs ?: 0L, 1000L)
            fun xFor(timeMs: Long): Float =
                left + chartWidth * timeMs.toFloat() / spanMs.toFloat()

            fun yFor(frequency: Float): Float {
                val ratio = (ln(frequency / MIN_FREQUENCY) / ln(MAX_FREQUENCY / MIN_FREQUENCY))
                    .coerceIn(0f, 1f)
                return top + chartHeight * (1f - ratio)
            }

            val notePaint = Paint().apply {
                color = textColor.toArgb()
                textSize = labelSizePx
                textAlign = Paint.Align.RIGHT
                isAntiAlias = true
            }
            for (midi in 36..96 step 12) {
                val frequency = NoteUtils.midiToFrequency(midi)
                if (frequency !in MIN_FREQUENCY..MAX_FREQUENCY) continue
                val y = yFor(frequency)
                drawLine(
                    color = gridColor,
                    start = Offset(left, y),
                    end = Offset(left + chartWidth, y),
                    strokeWidth = 1f
                )
                drawContext.canvas.nativeCanvas.drawText(
                    NoteUtils.resolveMidiNote(midi).let { "${it.noteName}${it.octave}" },
                    left - 6f,
                    y + labelSizePx / 3f,
                    notePaint
                )
            }

            val timePaint = Paint().apply {
                color = textColor.toArgb()
                textSize = labelSizePx
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            val stepMs = when {
                spanMs <= 5_000L -> 1_000L
                spanMs <= 20_000L -> 5_000L
                spanMs <= 60_000L -> 10_000L
                else -> ((spanMs / 5 + 999) / 1000) * 1000
            }
            var time = 0L
            while (time <= spanMs) {
                val x = xFor(time)
                drawLine(
                    color = gridColor,
                    start = Offset(x, top),
                    end = Offset(x, top + chartHeight),
                    strokeWidth = 1f
                )
                if (time == spanMs || spanMs - time > stepMs / 4) {
                    drawContext.canvas.nativeCanvas.drawText(
                        "${time / 1000}s",
                        x,
                        top + chartHeight + labelSizePx + 6f,
                        timePaint
                    )
                }
                time += stepMs
            }
            val lastGrid = (spanMs / stepMs) * stepMs
            if (spanMs != lastGrid) {
                val endPaint = Paint().apply {
                    color = textColor.toArgb()
                    textSize = labelSizePx
                    textAlign = Paint.Align.RIGHT
                    isAntiAlias = true
                }
                drawContext.canvas.nativeCanvas.drawText(
                    String.format(Locale.US, "%.1fs", spanMs / 1000f),
                    left + chartWidth,
                    top + chartHeight + labelSizePx + 6f,
                    endPaint
                )
            }

            val linePath = Path()
            var drawing = false
            for (frame in frames) {
                if (frame.frequencyHz <= 0f) {
                    drawing = false
                    continue
                }
                val x = xFor(frame.timeMs)
                val y = yFor(frame.frequencyHz)
                if (drawing) {
                    linePath.lineTo(x, y)
                } else {
                    linePath.moveTo(x, y)
                    drawing = true
                }
            }
            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            frames.lastOrNull { it.frequencyHz > 0f }?.let {
                drawCircle(
                    color = lineColor,
                    radius = 4.dp.toPx(),
                    center = Offset(xFor(it.timeMs), yFor(it.frequencyHz))
                )
            }
        }

        if (!hasPitch) {
            Text(
                text = stringResource(R.string.frequency_visualizer_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
