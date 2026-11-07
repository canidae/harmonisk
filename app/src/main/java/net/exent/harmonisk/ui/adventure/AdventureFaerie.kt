package net.exent.harmonisk.ui.adventure

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.exent.harmonisk.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun FaerieOverlay(
    faerie: AdventureFaerie?,
    player: AdventurePlayer?,
    mapCenter: Offset,
    playerAnchor: (String) -> Offset,
    origin: Offset,
    onFinished: () -> Unit = {}
) {
    if (faerie == null || player == null || !mapCenter.isSpecified) return
    val fanfare = remember { FaerieFanfarePlayer() }
    val enter = remember { Animatable(0f) }
    val pop = remember { Animatable(0f) }
    val fly = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    DisposableEffect(Unit) {
        onDispose { fanfare.release() }
    }
    LaunchedEffect(faerie) {
        enter.snapTo(0f)
        pop.snapTo(0f)
        fly.snapTo(0f)
        exit.snapTo(0f)
        fanfare.play()
        enter.animateTo(
            1f,
            tween(AdventureTiming.FAERIE_ENTER_MS.toInt(), easing = FastOutSlowInEasing)
        )
        pop.animateTo(
            1f,
            tween(AdventureTiming.FAERIE_POP_MS.toInt(), easing = FastOutSlowInEasing)
        )
        delay(AdventureTiming.FAERIE_HOLD_MS)
        fly.animateTo(
            1f,
            tween(AdventureTiming.FAERIE_FLY_MS.toInt(), easing = FastOutSlowInEasing)
        )
        exit.animateTo(
            1f,
            tween(AdventureTiming.FAERIE_EXIT_MS.toInt(), easing = FastOutSlowInEasing)
        )
        onFinished()
    }
    val transition = rememberInfiniteTransition(label = "faerie")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "faerie_time"
    )
    val description = stringResource(R.string.adventure_faerie_blessing, player.name, faerie.points)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = description }
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val unit = with(density) { 1.dp.toPx() }
        val center = mapCenter - origin
        val hover = Offset(center.x, center.y - 26 * unit)
        val enterStart = Offset(-120 * unit, hover.y + 170 * unit)
        val enterControl = Offset(widthPx * 0.3f, hover.y - 190 * unit)
        val exitEnd = Offset(widthPx + 130 * unit, hover.y - 200 * unit)
        val exitControl = Offset(widthPx * 0.62f, hover.y - 260 * unit)
        val flap = sin(time * 2f * PI).toFloat()
        val bob = sin(time * 4f * PI).toFloat()
        val settled = (enter.value - 0.85f).coerceIn(0f, 1f) / 0.15f
        val faeriePosition = if (exit.value > 0f) {
            quadratic(hover, exitControl, exitEnd, exit.value)
        } else {
            quadratic(enterStart, enterControl, hover, enter.value) +
                Offset(0f, bob * 4 * unit * settled)
        }
        val faerieScale = (0.6f + 0.4f * enter.value) * (1f - 0.15f * exit.value)
        val anchor = playerAnchor(faerie.playerId)
        val target = if (anchor.isSpecified) anchor - origin else hover
        val bubbleStart = faeriePosition + Offset(50 * unit, -4 * unit)
        val flyProgress = fly.value
        val bubblePosition = Offset(
            bubbleStart.x + (target.x - bubbleStart.x) * flyProgress,
            bubbleStart.y + (target.y - bubbleStart.y) * flyProgress -
                sin(flyProgress * PI).toFloat() * 70 * unit
        )
        Canvas(Modifier.fillMaxSize()) {
            if (exit.value > 0f) {
                drawTrail(hover, exitControl, exitEnd, exit.value, time)
            } else {
                drawTrail(enterStart, enterControl, hover, enter.value, time)
            }
            if (exit.value == 0f) {
                drawOrbitSparkles(hover, time)
            }
            drawFaerie(faeriePosition, faerieScale, flap, time)
            if (flyProgress > 0.7f) {
                drawArrivalBurst(target, (flyProgress - 0.7f) / 0.3f)
            }
        }
        if (pop.value > 0f && flyProgress < 1f) {
            val bubbleScale = (0.55f + 0.45f * pop.value) * (1f - 0.3f * flyProgress)
            val bubbleAlpha = pop.value * (1f - flyProgress * flyProgress)
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (bubblePosition.x - 48.dp.toPx()).roundToInt(),
                            (bubblePosition.y - 48.dp.toPx()).roundToInt()
                        )
                    }
                    .graphicsLayer {
                        scaleX = bubbleScale
                        scaleY = bubbleScale
                        alpha = bubbleAlpha
                    }
                    .size(96.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val radius = size.minDimension / 2f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                StorybookGold.copy(alpha = 0.3f),
                                StorybookGold.copy(alpha = 0.1f),
                                Color.Transparent
                            ),
                            radius = radius
                        ),
                        radius = radius
                    )
                    drawCircle(
                        color = StorybookGold.copy(alpha = 0.35f),
                        radius = radius * 0.72f,
                        style = Stroke(width = 1.2.dp.toPx())
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "+${faerie.points}",
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Black,
                        color = StorybookGold,
                        textAlign = TextAlign.Center,
                        style = TextStyle(
                            shadow = Shadow(Color(0xFF2B1B00).copy(alpha = 0.8f), blurRadius = 14f)
                        )
                    )
                    Text(
                        text = player.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 88.dp)
                    )
                }
            }
        }
    }
}

private fun quadratic(start: Offset, control: Offset, end: Offset, t: Float): Offset {
    val u = 1f - t
    return Offset(
        u * u * start.x + 2f * u * t * control.x + t * t * end.x,
        u * u * start.y + 2f * u * t * control.y + t * t * end.y
    )
}

private fun DrawScope.drawFaerie(center: Offset, scale: Float, flap: Float, time: Float) {
    val u = 1.3.dp.toPx() * scale
    val glow = Brush.radialGradient(
        colors = listOf(
            StorybookGold.copy(alpha = 0.38f),
            StorybookGold.copy(alpha = 0.14f),
            Color.Transparent
        ),
        center = center,
        radius = 58f * u
    )
    drawCircle(brush = glow, radius = 58f * u, center = center)
    val wingBrush = Brush.linearGradient(
        colors = listOf(
            Color(0xFFFFFDF5).copy(alpha = 0.92f),
            Color(0xFFBDEFFF).copy(alpha = 0.6f),
            Color(0xFFC9A7FF).copy(alpha = 0.36f)
        ),
        start = center - Offset(52f * u, 32f * u),
        end = center + Offset(24f * u, 32f * u)
    )
    val upper = -26f + flap * 18f
    drawWing(center, Offset(center.x - 58f * u, center.y - 32f * u), Size(54f * u, 36f * u), upper, wingBrush, u, 1f)
    drawWing(center, Offset(center.x + 4f * u, center.y - 32f * u), Size(54f * u, 36f * u), -upper, wingBrush, u, 1f)
    drawWing(center, Offset(center.x - 48f * u, center.y - 2f * u), Size(44f * u, 28f * u), upper * 0.6f + 14f, wingBrush, u, 0.85f)
    drawWing(center, Offset(center.x + 4f * u, center.y - 2f * u), Size(44f * u, 28f * u), -upper * 0.6f - 14f, wingBrush, u, 0.85f)
    val dress = Path().apply {
        moveTo(center.x, center.y - 13f * u)
        cubicTo(
            center.x - 6f * u, center.y - 8f * u,
            center.x - 6f * u, center.y - 1f * u,
            center.x - 10f * u, center.y + 13f * u
        )
        quadraticTo(
            center.x, center.y + 20f * u,
            center.x + 10f * u, center.y + 13f * u
        )
        cubicTo(
            center.x + 6f * u, center.y - 1f * u,
            center.x + 6f * u, center.y - 8f * u,
            center.x, center.y - 13f * u
        )
        close()
    }
    drawPath(
        path = dress,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFFFFF3C4), StorybookGold, Color(0xFFE09A2D)),
            startY = center.y - 13f * u,
            endY = center.y + 20f * u
        )
    )
    drawCircle(Color.White.copy(alpha = 0.45f), radius = 2.2f * u, center = center + Offset(0f, -7f * u))
    val head = center + Offset(0f, -20f * u)
    drawCircle(Color(0xFFFFE6C4), radius = 6.5f * u, center = head)
    drawArc(
        color = Color(0xFFE3A94F),
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = head - Offset(7f * u, 7f * u),
        size = Size(14f * u, 14f * u)
    )
    val twinkle = 0.75f + 0.25f * sin(time * 6f * PI).toFloat()
    drawSparkle(head + Offset(0f, -8f * u), 3.2f * u * twinkle, StorybookGold)
    val hand = center + Offset(7f * u, -3f * u)
    val wandEnd = center + Offset(27f * u, -19f * u)
    drawLine(
        color = StorybookGold,
        start = hand,
        end = wandEnd,
        strokeWidth = 1.8f * u,
        cap = StrokeCap.Round
    )
    drawCircle(
        color = Color(0xFFFFF3C4).copy(alpha = 0.28f),
        radius = 8f * u * twinkle,
        center = wandEnd
    )
    drawSparkle(wandEnd, 6.5f * u * twinkle, Color(0xFFFFF3C4))
    drawCircle(Color.White.copy(alpha = 0.9f), radius = 1.7f * u, center = wandEnd)
    drawLine(
        color = Color(0xFFFFE7B0),
        start = center + Offset(-3f * u, 17f * u),
        end = center + Offset(-4f * u, 23f * u),
        strokeWidth = 1.4f * u,
        cap = StrokeCap.Round
    )
    drawLine(
        color = Color(0xFFFFE7B0),
        start = center + Offset(3f * u, 17f * u),
        end = center + Offset(4f * u, 23f * u),
        strokeWidth = 1.4f * u,
        cap = StrokeCap.Round
    )
}

private fun DrawScope.drawWing(
    pivot: Offset,
    topLeft: Offset,
    size: Size,
    angle: Float,
    brush: Brush,
    unit: Float,
    alpha: Float
) {
    rotate(angle, pivot = pivot) {
        drawOval(brush = brush, topLeft = topLeft, size = size, alpha = alpha)
        drawOval(
            color = Color.White.copy(alpha = 0.4f),
            topLeft = topLeft,
            size = size,
            alpha = alpha,
            style = Stroke(width = 1f * unit)
        )
        drawLine(
            color = Color.White.copy(alpha = 0.3f),
            start = pivot,
            end = Offset(topLeft.x + size.width * 0.25f, topLeft.y + size.height * 0.3f),
            strokeWidth = 1f * unit,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White.copy(alpha = 0.22f),
            start = pivot,
            end = Offset(topLeft.x + size.width * 0.5f, topLeft.y + size.height * 0.55f),
            strokeWidth = 1f * unit,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawSparkle(center: Offset, radius: Float, color: Color) {
    if (radius <= 0f) return
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        lineTo(center.x + radius * 0.22f, center.y - radius * 0.22f)
        lineTo(center.x + radius, center.y)
        lineTo(center.x + radius * 0.22f, center.y + radius * 0.22f)
        lineTo(center.x, center.y + radius)
        lineTo(center.x - radius * 0.22f, center.y + radius * 0.22f)
        lineTo(center.x - radius, center.y)
        lineTo(center.x - radius * 0.22f, center.y - radius * 0.22f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawTrail(
    start: Offset,
    control: Offset,
    end: Offset,
    progress: Float,
    time: Float
) {
    repeat(6) { index ->
        val at = progress - (index + 1) * 0.055f
        if (at <= 0f) return@repeat
        val base = quadratic(start, control, end, at)
        val sway = sin((at * 9f + time * 4f + index) * PI).toFloat()
        val position = base + Offset(sway * 10.dp.toPx(), -index * 2.dp.toPx())
        val radius = (5.5f - index * 0.6f).coerceAtLeast(1.6f).dp.toPx()
        drawSparkle(
            position,
            radius,
            StorybookGold.copy(alpha = (0.85f - index * 0.13f).coerceAtLeast(0f))
        )
    }
}

private fun DrawScope.drawOrbitSparkles(center: Offset, time: Float) {
    repeat(7) { index ->
        val phase = (time + index / 7f) % 1f
        val angle = phase * 2f * PI + index * 2.4f
        val radius = (54f + sin(phase * 4f * PI + index).toFloat() * 9f).dp.toPx()
        val position = center + Offset(
            (cos(angle) * radius).toFloat(),
            (sin(angle) * radius * 0.62f).toFloat()
        )
        drawSparkle(
            position,
            (2.5f + index % 3).dp.toPx() * (0.8f + 0.2f * sin(phase * 6f * PI).toFloat()),
            Color.White.copy(alpha = 0.7f * (1f - phase))
        )
    }
}

private fun DrawScope.drawArrivalBurst(center: Offset, progress: Float) {
    val eased = progress.coerceIn(0f, 1f)
    drawCircle(
        color = StorybookGold.copy(alpha = (1f - eased) * 0.6f),
        radius = (10f + 34f * eased).dp.toPx(),
        center = center,
        style = Stroke(width = 2.dp.toPx())
    )
    repeat(8) { index ->
        val angle = index * PI / 4f
        val distance = (12f + 34f * eased).dp.toPx()
        val position = center + Offset(
            (cos(angle) * distance).toFloat(),
            (sin(angle) * distance).toFloat()
        )
        drawSparkle(
            position,
            (4f * (1f - eased)).dp.toPx(),
            StorybookGold.copy(alpha = (1f - eased) * 0.8f)
        )
    }
}
