package net.exent.harmonisk.ui.freebird

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.ui.theme.FingerColors
import kotlin.math.sin

@Composable
fun FreeBirdSingingNotes(
    instrument: Instrument,
    hit: FreeBirdHit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val layout = remember(width, height, instrument) {
            freeBirdStaffLayout(width, height, instrument, density)
        }
        val key = FreeBirdKey
        var now by remember(hit.id) { mutableLongStateOf(hit.createdAt) }
        LaunchedEffect(hit.id) {
            while (true) {
                withFrameNanos { now = SystemClock.elapsedRealtime() }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val t = ((now - hit.createdAt) / 1_100f).coerceIn(0f, 1f)
            if (t >= 1f) return@Canvas
            val birdSize = width * BIRD_SIZE_FRACTION
            val noteY = freeBirdNoteY(layout, hit.midi, key, height)
            val beak = Offset(
                x = width * BIRD_BEAK_X,
                y = noteY - birdSize * 0.12f
            )
            val color = FingerColors[hit.finger]
            repeat(4) { index ->
                val start = index * 0.09f
                val particleProgress = ((t - start) / (1f - start)).coerceIn(0f, 1f)
                if (particleProgress <= 0f) return@repeat
                val alpha = 1f - particleProgress
                val dx = birdSize * (0.5f + particleProgress * (1.15f + index * 0.12f)) +
                    birdSize * index * 0.38f
                val dy = birdSize * (0.2f + particleProgress * (1.0f + index * 0.1f)) +
                    birdSize * index * 0.22f +
                    sin(particleProgress * 8f + index) * birdSize * 0.08f
                val particleSize = birdSize * (0.34f + 0.03f * (index % 2))
                val center = Offset(beak.x + dx, beak.y - dy)
                drawCircle(
                    color = Color.White.copy(alpha = alpha * 0.16f),
                    radius = particleSize * 1.2f,
                    center = center
                )
                drawMusicNote(
                    center = center + Offset(particleSize * 0.08f, particleSize * 0.1f),
                    size = particleSize,
                    color = Color(0xFF17301F),
                    alpha = alpha * 0.45f
                )
                drawMusicNote(
                    center = center,
                    size = particleSize,
                    color = color,
                    alpha = alpha
                )
            }
        }
    }
}

private fun DrawScope.drawMusicNote(center: Offset, size: Float, color: Color, alpha: Float) {
    val paint = color.copy(alpha = alpha)
    rotate(-18f, pivot = center) {
        drawOval(
            color = paint,
            topLeft = Offset(center.x - size * 0.55f, center.y - size * 0.32f),
            size = Size(size * 1.1f, size * 0.64f)
        )
        val stemX = center.x + size * 0.45f
        drawLine(
            color = paint,
            start = Offset(stemX, center.y - size * 0.1f),
            end = Offset(stemX, center.y - size * 1.6f),
            strokeWidth = size * 0.13f,
            cap = StrokeCap.Round
        )
        val flag = Path().apply {
            moveTo(stemX, center.y - size * 1.55f)
            cubicTo(
                stemX + size * 0.5f, center.y - size * 1.3f,
                stemX + size * 0.55f, center.y - size * 0.95f,
                stemX + size * 0.2f, center.y - size * 0.75f
            )
        }
        drawPath(
            path = flag,
            color = paint,
            style = Stroke(width = size * 0.13f, cap = StrokeCap.Round)
        )
    }
}
