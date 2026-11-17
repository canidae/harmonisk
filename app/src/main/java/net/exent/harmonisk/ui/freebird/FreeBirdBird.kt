package net.exent.harmonisk.ui.freebird

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.PitchResult
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

const val BIRD_SIZE_FRACTION = 0.13f

private val Body = Color(0xFFF0A25A)
private val BodyLight = Color(0xFFFFCB92)
private val Belly = Color(0xFFFFE3BC)
private val Wing = Color(0xFFD97B3E)
private val WingDark = Color(0xFFB85E2C)
private val Beak = Color(0xFFF4C542)
private val Eye = Color(0xFF2A1A12)

@Composable
fun FreeBirdBird(
    instrument: Instrument,
    pitch: PitchResult,
    level: Int = 1,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val layout = remember(width, height, instrument, level) {
            freeBirdStaffLayout(width, height, instrument, level, density)
        }
        val birdSize = width * BIRD_SIZE_FRACTION
        val perchY = height * BRANCH_PERCH_Y - width * 0.0105f - birdSize * 0.36f
        val flying = !pitch.isSilent
        val targetY = if (flying) freeBirdPitchY(layout, pitch, height) else perchY
        val y = animateFloatAsState(
            targetValue = targetY,
            animationSpec = tween(if (flying) 120 else 420, easing = FastOutSlowInEasing),
            label = "freebird_y"
        )

        val transition = rememberInfiniteTransition(label = "freebird_flap")
        val flap = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(260, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "freebird_flap_phase"
        )
        val wingAmount by animateFloatAsState(
            targetValue = if (flying) 1f else 0.35f,
            animationSpec = tween(180),
            label = "freebird_wing_amount"
        )
        val birdX = width * BRANCH_PERCH_X
        val birdSizeDp = with(density) { birdSize.toDp() }
        val perch = perchY
        Canvas(
            Modifier
                .size(birdSizeDp)
                .graphicsLayer {
                    val currentY = y.value
                    translationX = birdX - birdSize / 2f
                    translationY = currentY - birdSize / 2f
                    val perchedNow = !flying && abs(currentY - perch) < birdSize * 0.24f
                    rotationZ = if (perchedNow) 0f else ((targetY - currentY) / birdSize).coerceIn(-1f, 1f) * 28f
                }
        ) {
            val perchedNow = !flying && abs(y.value - perch) < birdSize * 0.24f
            val divingNow = !flying && !perchedNow
            val wingAngle = when {
                perchedNow -> 0f
                divingNow -> -26f
                else -> sin(flap.value * 2f * PI.toFloat()) * 42f * wingAmount
            }
            drawBird(birdSize, perchedNow, wingAngle)
        }
    }
}

private fun DrawScope.drawBird(size: Float, perched: Boolean, wingAngle: Float) {
    fun point(x: Float, y: Float) = Offset(x * size, y * size)

    val tail = Path().apply {
        moveTo(0.21f * size, 0.52f * size)
        lineTo(0.02f * size, 0.43f * size)
        lineTo(0.07f * size, 0.55f * size)
        lineTo(0.02f * size, 0.64f * size)
        close()
    }
    drawPath(tail, WingDark)

    if (!perched) {
        rotate(wingAngle + 14f, pivot = point(0.50f, 0.43f)) {
            drawPath(wingPath(size), WingDark.copy(alpha = 0.72f))
        }
    }

    if (perched) {
        drawLine(
            color = Beak.copy(alpha = 0.9f),
            start = point(0.43f, 0.65f),
            end = point(0.42f, 0.86f),
            strokeWidth = size * 0.018f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Beak.copy(alpha = 0.9f),
            start = point(0.57f, 0.65f),
            end = point(0.58f, 0.86f),
            strokeWidth = size * 0.018f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Beak,
            start = point(0.40f, 0.86f),
            end = point(0.48f, 0.86f),
            strokeWidth = size * 0.014f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Beak,
            start = point(0.58f, 0.86f),
            end = point(0.66f, 0.86f),
            strokeWidth = size * 0.014f,
            cap = StrokeCap.Round
        )
    }

    val body = Path().apply {
        moveTo(0.18f * size, 0.54f * size)
        cubicTo(0.22f * size, 0.35f * size, 0.44f * size, 0.27f * size, 0.62f * size, 0.36f * size)
        cubicTo(0.77f * size, 0.43f * size, 0.74f * size, 0.60f * size, 0.60f * size, 0.68f * size)
        cubicTo(0.44f * size, 0.78f * size, 0.24f * size, 0.70f * size, 0.18f * size, 0.54f * size)
        close()
    }
    drawPath(body, Body)
    drawOval(
        color = Belly.copy(alpha = 0.9f),
        topLeft = Offset(0.34f * size, 0.50f * size),
        size = Size(0.30f * size, 0.18f * size)
    )
    drawPath(body, BodyLight.copy(alpha = 0.25f), style = Stroke(width = size * 0.012f))

    drawCircle(Body, 0.15f * size, point(0.64f, 0.39f))
    drawCircle(Belly.copy(alpha = 0.55f), 0.075f * size, point(0.64f, 0.44f))

    val beak = Path().apply {
        moveTo(0.76f * size, 0.37f * size)
        lineTo(0.94f * size, 0.43f * size)
        lineTo(0.76f * size, 0.47f * size)
        close()
    }
    drawPath(beak, Beak)
    drawCircle(Eye, 0.026f * size, point(0.70f, 0.36f))
    drawCircle(Color.White.copy(alpha = 0.85f), 0.008f * size, point(0.708f, 0.354f))

    if (perched) {
        val folded = Path().apply {
            moveTo(0.52f * size, 0.44f * size)
            cubicTo(0.45f * size, 0.50f * size, 0.31f * size, 0.60f * size, 0.21f * size, 0.55f * size)
            cubicTo(0.31f * size, 0.48f * size, 0.43f * size, 0.43f * size, 0.52f * size, 0.44f * size)
            close()
        }
        drawPath(folded, Wing)
        drawPath(folded, WingDark.copy(alpha = 0.35f), style = Stroke(width = size * 0.008f))
    } else {
        rotate(wingAngle, pivot = point(0.50f, 0.43f)) {
            drawPath(wingPath(size), Wing)
            drawPath(wingPath(size), WingDark.copy(alpha = 0.28f), style = Stroke(width = size * 0.008f))
        }
        drawLine(
            color = WingDark.copy(alpha = 0.7f),
            start = point(0.40f, 0.68f),
            end = point(0.45f, 0.74f),
            strokeWidth = size * 0.012f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = WingDark.copy(alpha = 0.7f),
            start = point(0.55f, 0.68f),
            end = point(0.52f, 0.74f),
            strokeWidth = size * 0.012f,
            cap = StrokeCap.Round
        )
    }
}

private fun wingPath(size: Float): Path = Path().apply {
    moveTo(0.50f * size, 0.44f * size)
    cubicTo(0.40f * size, 0.16f * size, 0.20f * size, 0.12f * size, 0.13f * size, 0.30f * size)
    cubicTo(0.26f * size, 0.36f * size, 0.40f * size, 0.42f * size, 0.50f * size, 0.44f * size)
    close()
}
