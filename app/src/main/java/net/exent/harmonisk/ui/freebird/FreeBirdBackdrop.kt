package net.exent.harmonisk.ui.freebird

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val SkyTop = Color(0xFF3D9BC7)
private val SkyMid = Color(0xFFA8DCEB)
private val SkyHorizon = Color(0xFFFFE3A8)
private val SunCore = Color(0xFFFFFBE8)
private val SunGlow = Color(0xFFFFD47A)
private val FarHillTop = Color(0xFFA9CDBE)
private val FarHillBottom = Color(0xFF8AB6A4)
private val MidHillTop = Color(0xFF79B08D)
private val MidHillBottom = Color(0xFF5E9874)
private val BackForest = Color(0xFF4C8A63)
private val FrontForest = Color(0xFF356B4A)
private val Ground = Color(0xFF245538)
private val GroundDark = Color(0xFF0E291B)
private val Cloud = Color(0xFFFFFDF5)

private data class TreeSpec(val x: Float, val height: Float, val broadleaf: Boolean)

private class Ridge(val peaks: FloatArray, val valleys: FloatArray, val positions: FloatArray)

private val FarRidge = Ridge(
    peaks = floatArrayOf(0.92f, 0.66f, 1.02f, 0.78f, 0.88f, 0.7f),
    valleys = floatArrayOf(0.42f, 0.55f, 0.46f, 0.62f, 0.40f, 0.5f),
    positions = floatArrayOf(0.38f, 0.55f, 0.31f, 0.63f, 0.45f, 0.58f)
)

private val MidRidge = Ridge(
    peaks = floatArrayOf(0.74f, 1.02f, 0.6f, 0.92f, 0.68f, 0.84f, 0.52f),
    valleys = floatArrayOf(0.34f, 0.44f, 0.38f, 0.5f, 0.3f, 0.42f, 0.36f),
    positions = floatArrayOf(0.42f, 0.34f, 0.61f, 0.47f, 0.56f, 0.29f, 0.5f)
)

private val BackTrees = listOf(
    TreeSpec(0.02f, 1.05f, false), TreeSpec(0.09f, 0.72f, true), TreeSpec(0.15f, 1.22f, false),
    TreeSpec(0.23f, 0.86f, false), TreeSpec(0.30f, 0.64f, true), TreeSpec(0.38f, 1.10f, false),
    TreeSpec(0.46f, 0.80f, false), TreeSpec(0.54f, 1.18f, false), TreeSpec(0.61f, 0.70f, true),
    TreeSpec(0.69f, 0.95f, false), TreeSpec(0.77f, 1.28f, false), TreeSpec(0.84f, 0.78f, true),
    TreeSpec(0.91f, 1.02f, false), TreeSpec(0.97f, 0.68f, false)
)

private val FrontTrees = listOf(
    TreeSpec(0.05f, 0.85f, false), TreeSpec(0.13f, 1.12f, false), TreeSpec(0.27f, 0.92f, true),
    TreeSpec(0.35f, 1.25f, false), TreeSpec(0.44f, 0.78f, false), TreeSpec(0.58f, 1.05f, false),
    TreeSpec(0.66f, 0.88f, true), TreeSpec(0.74f, 1.30f, false), TreeSpec(0.88f, 0.96f, false)
)

@Composable
fun FreeBirdBackdrop(modifier: Modifier = Modifier, speed: Float = 1f) {
    val transition = rememberInfiniteTransition(label = "freebird_backdrop")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween((120_000 / speed.coerceAtLeast(0.01f)).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "freebird_scroll"
    )
    Canvas(modifier) {
        drawSky()
        drawSun()
        drawClouds(time)
        drawHills(
            baseY = size.height * 0.60f,
            amplitude = size.height * 0.155f,
            ridge = FarRidge,
            phase = time,
            top = FarHillTop,
            bottom = FarHillBottom
        )
        drawHills(
            baseY = size.height * 0.695f,
            amplitude = size.height * 0.105f,
            ridge = MidRidge,
            phase = time * 2f,
            top = MidHillTop,
            bottom = MidHillBottom
        )
        drawForest(time)
        drawGround(time)
        drawBirds(time)
        drawVignette()
    }
}

private fun DrawScope.drawSky() {
    drawRect(
        brush = Brush.verticalGradient(
            0f to SkyTop,
            0.5f to SkyMid,
            0.78f to SkyHorizon,
            1f to SkyHorizon
        ),
        size = size
    )
}

private fun DrawScope.drawSun() {
    val center = Offset(size.width * 0.76f, size.height * 0.17f)
    val radius = min(size.width, size.height) * 0.052f
    val glowRadius = radius * 5.2f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                SunGlow.copy(alpha = 0.48f),
                SunGlow.copy(alpha = 0.20f),
                SunGlow.copy(alpha = 0.06f),
                Color.Transparent
            ),
            center = center,
            radius = glowRadius
        ),
        radius = glowRadius,
        center = center
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(SunCore, Color(0xFFFFEDB0), SunGlow),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

private fun DrawScope.drawClouds(time: Float) {
    val clouds = listOf(
        Triple(0.04f, 0.15f, 1.05f),
        Triple(0.42f, 0.08f, 0.72f),
        Triple(0.68f, 0.27f, 0.80f),
        Triple(0.20f, 0.34f, 0.56f)
    )
    clouds.forEachIndexed { index, (baseX, baseY, scale) ->
        val travel = (baseX + time * (index + 2) + 0.15f) % 1f
        val x = travel * size.width
        val y = baseY * size.height
        val w = size.width * 0.26f * scale
        val h = w * 0.26f
        drawCloudPuff(Offset(x, y), w * 0.55f, 0.26f)
        drawCloudPuff(Offset(x + w * 0.30f, y - h * 0.35f), w * 0.42f, 0.34f)
        drawCloudPuff(Offset(x - w * 0.32f, y + h * 0.18f), w * 0.36f, 0.22f)
        drawCloudPuff(Offset(x + w * 0.05f, y + h * 0.30f), w * 0.44f, 0.18f)
    }
}

private fun DrawScope.drawCloudPuff(center: Offset, radius: Float, alpha: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Cloud.copy(alpha = alpha),
                Cloud.copy(alpha = alpha * 0.55f),
                Color.Transparent
            ),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

private fun DrawScope.drawHills(
    baseY: Float,
    amplitude: Float,
    ridge: Ridge,
    phase: Float,
    top: Color,
    bottom: Color
) {
    val fill = Path().apply {
        moveTo(0f, size.height)
        lineTo(0f, mountainY(0f, baseY, amplitude, ridge, phase))
    }
    val line = Path().apply {
        moveTo(0f, mountainY(0f, baseY, amplitude, ridge, phase))
    }
    var x = 0f
    while (x <= size.width) {
        val y = mountainY(x, baseY, amplitude, ridge, phase)
        fill.lineTo(x, y)
        line.lineTo(x, y)
        x += 4f
    }
    fill.lineTo(size.width, size.height)
    fill.close()
    drawPath(
        path = fill,
        brush = Brush.verticalGradient(
            0f to top,
            1f to bottom,
            startY = baseY - amplitude * 1.2f,
            endY = baseY + size.height * 0.10f
        )
    )
    drawPath(
        path = line,
        color = Color.White.copy(alpha = 0.10f),
        style = Stroke(width = 1.4f)
    )
}

private fun DrawScope.mountainY(
    x: Float,
    baseY: Float,
    amplitude: Float,
    ridge: Ridge,
    phase: Float
): Float {
    val width = size.width
    val u = ((x / width + phase) % 1f + 1f) % 1f
    val count = ridge.peaks.size
    val scaled = u * count
    val index = scaled.toInt() % count
    val t = scaled - scaled.toInt()
    val peakPosition = ridge.positions[index]
    val height = if (t < peakPosition) {
        ridge.valleys[index] + (ridge.peaks[index] - ridge.valleys[index]) * (t / peakPosition)
    } else {
        ridge.peaks[index] + (ridge.valleys[(index + 1) % count] - ridge.peaks[index]) *
            ((t - peakPosition) / (1f - peakPosition))
    }
    val detail = (
        0.016 * sin((u * 19.0 + 0.4) * 2.0 * PI) +
            0.010 * sin((u * 37.0 + 2.1) * 2.0 * PI) +
            0.006 * sin((u * 61.0 + 3.7) * 2.0 * PI)
        ).toFloat()
    return baseY - amplitude * (height + detail).coerceIn(0f, 1.1f)
}

private fun DrawScope.drawForest(time: Float) {
    drawTreeRow(BackTrees, (time * 2f % 1f) * size.width, size.height * 0.775f, 0.042f, BackForest, 0.92f)
    drawTreeRow(FrontTrees, (time * 3f % 1f) * size.width, size.height * 0.81f, 0.052f, FrontForest, 1f)
}

private fun DrawScope.drawTreeRow(
    trees: List<TreeSpec>,
    scroll: Float,
    baseY: Float,
    scale: Float,
    color: Color,
    alpha: Float
) {
    val treeColor = color.copy(alpha = alpha)
    for (tile in -1..1) {
        trees.forEach { tree ->
            val x = (tree.x + tile) * size.width - scroll
            if (x < -size.width * 0.08f || x > size.width * 1.08f) return@forEach
            val height = size.height * scale * tree.height
            val width = height * 0.58f
            if (tree.broadleaf) {
                drawBroadleaf(Offset(x, baseY), width * 0.8f, height, treeColor)
            } else {
                drawPine(Offset(x, baseY), width, height, treeColor)
            }
        }
    }
}

private fun DrawScope.drawPine(base: Offset, width: Float, height: Float, color: Color) {
    repeat(3) { index ->
        val tierBase = base.y - height * (0.06f + index * 0.26f)
        val tierApex = base.y - height * (0.5f + index * 0.26f)
        val halfWidth = width * (0.5f - index * 0.13f)
        val tier = Path().apply {
            moveTo(base.x - halfWidth, tierBase)
            lineTo(base.x + halfWidth, tierBase)
            lineTo(base.x, tierApex)
            close()
        }
        drawPath(tier, color)
    }
    drawRect(
        color = color,
        topLeft = Offset(base.x - width * 0.055f, base.y - height * 0.08f),
        size = Size(width * 0.11f, height * 0.10f)
    )
}

private fun DrawScope.drawBroadleaf(base: Offset, width: Float, height: Float, color: Color) {
    drawLine(
        color = color,
        start = Offset(base.x, base.y),
        end = Offset(base.x, base.y - height * 0.55f),
        strokeWidth = width * 0.10f
    )
    drawCircle(color, width * 0.34f, Offset(base.x - width * 0.20f, base.y - height * 0.58f))
    drawCircle(color, width * 0.30f, Offset(base.x + width * 0.22f, base.y - height * 0.62f))
    drawCircle(color, width * 0.38f, Offset(base.x, base.y - height * 0.78f))
}

private fun DrawScope.drawGround(time: Float) {
    val width = size.width
    val height = size.height
    val baseY = height * 0.895f
    val scroll = (time * 4f % 1f) * width
    val path = Path().apply {
        moveTo(0f, height)
        lineTo(0f, baseY)
        var x = 0f
        while (x <= width) {
            val angle = 2.0 * PI * (x + scroll) / width
            val y = baseY -
                height * 0.016f * sin(angle).toFloat() -
                height * 0.010f * sin(angle * 2.0 + 1.1).toFloat()
            lineTo(x, y)
            x += width / 96f
        }
        lineTo(width, height)
        close()
    }
    drawPath(
        path = path,
        brush = Brush.verticalGradient(
            0f to Ground,
            ((baseY + height * 0.05f) / height).coerceIn(0f, 1f) to GroundDark,
            1f to Color(0xFF081C12)
        )
    )
}

private fun DrawScope.drawBirds(time: Float) {
    val width = size.width
    val height = size.height
    repeat(3) { index ->
        val x = ((time * 2f + index * 0.31f + 0.1f) % 1f) * width
        val y = height * (0.24f + index * 0.045f) +
            sin((time * 2.0 + index) * PI).toFloat() * height * 0.008f
        val span = width * (0.016f + index * 0.002f)
        val flap = sin((time * 12f + index * 2f) * PI).toFloat() * span * 0.35f
        drawLine(
            color = Color(0xFF335A66).copy(alpha = 0.50f),
            start = Offset(x - span, y - flap),
            end = Offset(x, y),
            strokeWidth = 2.2f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFF335A66).copy(alpha = 0.50f),
            start = Offset(x, y),
            end = Offset(x + span, y - flap),
            strokeWidth = 2.2f,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawVignette() {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color(0xFF08130C).copy(alpha = 0.22f)),
            center = Offset(size.width / 2f, size.height * 0.42f),
            radius = max(size.width, size.height) * 0.72f
        ),
        size = size
    )
}
