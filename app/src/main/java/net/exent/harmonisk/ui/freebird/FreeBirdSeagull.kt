package net.exent.harmonisk.ui.freebird

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

private val GullBody = Color(0xFFFBFAF4)
private val GullBodyShade = Color(0xFFE2E9E9)
private val GullMantle = Color(0xFFC5D1D6)
private val GullWing = Color(0xFFCBD6DA)
private val GullWingLight = Color(0xFFEAF0F0)
private val GullWingShade = Color(0xFFAEBEC4)
private val GullTip = Color(0xFF444A4F)
private val GullBeak = Color(0xFFF4A93F)
private val GullBeakLight = Color(0xFFFFC971)
private val GullBeakTip = Color(0xFFDE7C36)
private val GullEyeBlue = Color(0xFF3C70D4)
private val GullEye = Color(0xFF1F2428)
private val GullFoot = Color(0xFFEC9A42)
private val GullFootShade = Color(0xFFC87428)
private val GullLine = Color(0xFF5D6C71)

@Composable
fun FreeBirdSeagull(modifier: Modifier = Modifier) {
    val phase by rememberFreeBirdPhase(3_200L, fps = 30)
    Canvas(modifier) {
        val unit = size.width
        val bx = unit * BIRD_PERCH_X
        val by = size.height * BIRD_PERCH_Y - unit * 0.0105f
        val angle = phase * 2f * PI.toFloat()
        drawSeagull(unit, bx, by, sin(angle), sin(angle + 3.4f))
    }
}

private fun DrawScope.drawSeagull(
    unit: Float,
    bx: Float,
    by: Float,
    flap: Float,
    tailSway: Float
) {
    fun x(value: Float) = bx + value * unit
    fun y(value: Float) = by + value * unit
    fun point(px: Float, py: Float) = Offset(x(px), y(py))
    val outline = Stroke(unit * 0.0026f)

    rotate(flap * 2.4f + 10f, pivot = point(0.080f, 0.010f)) {
        val farWing = Path().apply {
            moveTo(x(0.070f), y(0.020f))
            cubicTo(x(0.096f), y(-0.024f), x(0.150f), y(-0.062f), x(0.222f), y(-0.072f))
            cubicTo(x(0.248f), y(-0.075f), x(0.262f), y(-0.068f), x(0.264f), y(-0.058f))
            lineTo(x(0.250f), y(-0.048f))
            lineTo(x(0.242f), y(-0.058f))
            lineTo(x(0.236f), y(-0.042f))
            lineTo(x(0.226f), y(-0.052f))
            lineTo(x(0.218f), y(-0.034f))
            lineTo(x(0.206f), y(-0.044f))
            lineTo(x(0.196f), y(-0.024f))
            lineTo(x(0.182f), y(-0.034f))
            lineTo(x(0.170f), y(-0.012f))
            cubicTo(x(0.140f), y(0.010f), x(0.104f), y(0.026f), x(0.070f), y(0.020f))
            close()
        }
        drawPath(farWing, GullWing)
        clipPath(farWing) {
            drawPath(
                Path().apply {
                    moveTo(x(0.070f), y(0.020f))
                    cubicTo(x(0.100f), y(-0.032f), x(0.164f), y(-0.070f), x(0.246f), y(-0.070f))
                    cubicTo(x(0.258f), y(-0.068f), x(0.262f), y(-0.058f), x(0.254f), y(-0.050f))
                },
                GullWingLight,
                style = Stroke(unit * 0.010f)
            )
            val wrist = point(0.198f, -0.042f)
            listOf(
                0.266f to -0.050f,
                0.264f to -0.060f,
                0.255f to -0.070f,
                0.240f to -0.078f
            ).forEach { (tipX, tipY) ->
                feather(wrist, point(tipX, tipY), unit * 0.012f, GullTip, 0.2f)
            }
            drawLine(
                GullWingShade.copy(alpha = 0.6f),
                point(0.168f, -0.016f),
                point(0.198f, -0.046f),
                strokeWidth = unit * 0.0022f,
                cap = StrokeCap.Round
            )
        }
        drawPath(farWing, GullLine.copy(alpha = 0.32f), style = outline)
    }

    rotate(tailSway * 1.6f, pivot = point(-0.060f, 0.042f)) {
        val root = point(-0.058f, 0.042f)
        listOf(
            -0.150f to 0.032f,
            -0.150f to 0.050f,
            -0.136f to 0.066f
        ).forEach { (tipX, tipY) ->
            val tip = point(tipX, tipY)
            drawPath(featherPath(root, tip, unit * 0.013f, 0f, 1f), GullWingLight)
            drawPath(featherPath(root, tip, unit * 0.013f, 0.72f, 1f), GullMantle)
            drawPath(
                featherPath(root, tip, unit * 0.013f, 0f, 1f),
                GullLine.copy(alpha = 0.25f),
                style = Stroke(unit * 0.0016f)
            )
        }
    }

    val legs = listOf(
        Triple(0.014f, 0.060f, -0.010f),
        Triple(0.000f, 0.062f, -0.034f)
    )
    legs.forEachIndexed { index, (startX, startY, ankleX) ->
        val ankle = point(ankleX, 0.080f + index * 0.001f)
        drawLine(
            GullFootShade,
            point(startX, startY),
            ankle,
            strokeWidth = unit * 0.0055f,
            cap = StrokeCap.Round
        )
        foot(unit, ankle, -26f - index * 4f, if (index == 0) GullFootShade else GullFoot)
    }

    val gull = Path().apply {
        moveTo(x(-0.068f), y(0.030f))
        cubicTo(x(-0.062f), y(0.004f), x(-0.022f), y(-0.007f), x(0.016f), y(-0.005f))
        cubicTo(x(0.050f), y(-0.003f), x(0.078f), y(0.002f), x(0.090f), y(0.010f))
        cubicTo(x(0.092f), y(-0.008f), x(0.098f), y(-0.030f), x(0.112f), y(-0.044f))
        cubicTo(x(0.124f), y(-0.056f), x(0.146f), y(-0.054f), x(0.158f), y(-0.040f))
        cubicTo(x(0.168f), y(-0.030f), x(0.170f), y(-0.016f), x(0.163f), y(-0.005f))
        cubicTo(x(0.156f), y(0.010f), x(0.144f), y(0.022f), x(0.130f), y(0.032f))
        cubicTo(x(0.118f), y(0.042f), x(0.106f), y(0.050f), x(0.096f), y(0.058f))
        cubicTo(x(0.076f), y(0.066f), x(0.052f), y(0.068f), x(0.034f), y(0.068f))
        cubicTo(x(-0.016f), y(0.072f), x(-0.058f), y(0.062f), x(-0.066f), y(0.048f))
        close()
    }
    drawPath(gull, GullBody)
    clipPath(gull) {
        val mantle = Path().apply {
            moveTo(x(-0.070f), y(0.044f))
            cubicTo(x(-0.062f), y(0.010f), x(-0.024f), y(-0.006f), x(0.018f), y(-0.004f))
            cubicTo(x(0.050f), y(-0.002f), x(0.078f), y(0.002f), x(0.090f), y(0.010f))
            cubicTo(x(0.056f), y(0.026f), x(-0.004f), y(0.034f), x(-0.070f), y(0.044f))
            close()
        }
        drawPath(mantle, GullMantle)
        drawOval(
            GullBodyShade.copy(alpha = 0.35f),
            topLeft = point(-0.020f, 0.054f),
            size = Size(unit * 0.100f, unit * 0.018f)
        )
    }
    drawPath(gull, GullLine.copy(alpha = 0.4f), style = outline)

    rotate(flap * 4.4f - 35f, pivot = point(0.058f, 0.014f)) {
        val nearWing = Path().apply {
            moveTo(x(0.072f), y(0.022f))
            cubicTo(x(0.040f), y(-0.012f), x(-0.004f), y(-0.042f), x(-0.064f), y(-0.068f))
            cubicTo(x(-0.098f), y(-0.082f), x(-0.132f), y(-0.090f), x(-0.154f), y(-0.084f))
            cubicTo(x(-0.163f), y(-0.081f), x(-0.166f), y(-0.074f), x(-0.162f), y(-0.068f))
            lineTo(x(-0.150f), y(-0.062f))
            lineTo(x(-0.146f), y(-0.070f))
            lineTo(x(-0.140f), y(-0.050f))
            lineTo(x(-0.128f), y(-0.060f))
            lineTo(x(-0.120f), y(-0.038f))
            lineTo(x(-0.108f), y(-0.048f))
            lineTo(x(-0.096f), y(-0.024f))
            lineTo(x(-0.084f), y(-0.036f))
            lineTo(x(-0.070f), y(-0.010f))
            lineTo(x(-0.056f), y(-0.022f))
            lineTo(x(-0.040f), y(0.004f))
            lineTo(x(-0.024f), y(-0.008f))
            lineTo(x(-0.008f), y(0.018f))
            cubicTo(x(0.014f), y(0.030f), x(0.044f), y(0.036f), x(0.068f), y(0.032f))
            close()
        }
        drawPath(nearWing, GullWing)
        clipPath(nearWing) {
            drawPath(
                Path().apply {
                    moveTo(x(0.072f), y(0.022f))
                    cubicTo(x(0.040f), y(-0.012f), x(-0.004f), y(-0.042f), x(-0.064f), y(-0.068f))
                    cubicTo(x(-0.098f), y(-0.082f), x(-0.132f), y(-0.090f), x(-0.154f), y(-0.084f))
                },
                GullWingLight,
                style = Stroke(unit * 0.011f)
            )
            val wrist = point(-0.078f, -0.058f)
            listOf(
                -0.172f to -0.050f,
                -0.169f to -0.066f,
                -0.157f to -0.082f,
                -0.138f to -0.095f,
                -0.114f to -0.103f
            ).forEach { (tipX, tipY) ->
                feather(wrist, point(tipX, tipY), unit * 0.013f, GullTip, 0.22f)
            }
            drawLine(
                GullWingShade.copy(alpha = 0.65f),
                point(-0.064f, -0.062f),
                point(-0.036f, -0.020f),
                strokeWidth = unit * 0.0022f,
                cap = StrokeCap.Round
            )
            drawLine(
                GullWingShade.copy(alpha = 0.5f),
                point(-0.088f, -0.054f),
                point(-0.064f, -0.006f),
                strokeWidth = unit * 0.002f,
                cap = StrokeCap.Round
            )
        }
        drawPath(nearWing, GullLine.copy(alpha = 0.35f), style = outline)
    }

    val beak = Path().apply {
        moveTo(x(0.156f), y(-0.026f))
        cubicTo(x(0.178f), y(-0.032f), x(0.203f), y(-0.028f), x(0.219f), y(-0.017f))
        cubicTo(x(0.225f), y(-0.012f), x(0.227f), y(-0.005f), x(0.223f), y(-0.001f))
        cubicTo(x(0.215f), y(0.005f), x(0.197f), y(0.007f), x(0.179f), y(0.005f))
        cubicTo(x(0.166f), y(0.003f), x(0.157f), y(0.000f), x(0.156f), y(-0.004f))
        close()
    }
    drawPath(beak, GullBeak)
    drawPath(
        Path().apply {
            moveTo(x(0.158f), y(-0.024f))
            cubicTo(x(0.178f), y(-0.029f), x(0.200f), y(-0.026f), x(0.214f), y(-0.016f))
        },
        GullBeakLight.copy(alpha = 0.5f),
        style = Stroke(unit * 0.0035f)
    )
    drawPath(
        Path().apply {
            moveTo(x(0.160f), y(-0.001f))
            cubicTo(x(0.180f), y(0.001f), x(0.203f), y(0.000f), x(0.222f), y(-0.003f))
        },
        GullBeakTip.copy(alpha = 0.45f),
        style = Stroke(unit * 0.0016f)
    )
    drawCircle(GullBeakTip.copy(alpha = 0.7f), unit * 0.0026f, point(0.171f, -0.016f))

    drawOval(
        GullEyeBlue,
        topLeft = point(0.130f, -0.040f),
        size = Size(unit * 0.018f, unit * 0.021f)
    )
    drawCircle(GullEye, unit * 0.0066f, point(0.1405f, -0.0285f))
    drawCircle(Color.White.copy(alpha = 0.95f), unit * 0.0028f, point(0.1435f, -0.0330f))
}

private fun DrawScope.foot(unit: Float, ankle: Offset, angle: Float, color: Color) {
    rotate(angle, pivot = ankle) {
        val length = unit * 0.034f
        val web = Path().apply {
            moveTo(ankle.x, ankle.y)
            lineTo(ankle.x - length, ankle.y - length * 0.04f)
            quadraticTo(ankle.x - length * 0.72f, ankle.y + length * 0.03f, ankle.x - length * 0.94f, ankle.y + length * 0.16f)
            quadraticTo(ankle.x - length * 0.62f, ankle.y + length * 0.20f, ankle.x - length * 0.66f, ankle.y + length * 0.34f)
            quadraticTo(ankle.x - length * 0.24f, ankle.y + length * 0.16f, ankle.x, ankle.y)
            close()
        }
        drawPath(web, color)
        drawPath(web, GullFootShade.copy(alpha = 0.7f), style = Stroke(unit * 0.0016f))
    }
}

private fun featherPath(
    base: Offset,
    tip: Offset,
    halfWidth: Float,
    from: Float,
    to: Float
): Path {
    val dx = tip.x - base.x
    val dy = tip.y - base.y
    val length = sqrt(dx * dx + dy * dy)
    val nx = -dy / length
    val ny = dx / length
    val x0 = base.x + dx * from
    val y0 = base.y + dy * from
    val x1 = base.x + dx * to
    val y1 = base.y + dy * to
    val w0 = halfWidth * (1f - 0.45f * from)
    val w1 = halfWidth * (1f - 0.92f * to).coerceAtLeast(0.06f)
    val mx = (x0 + x1) / 2f
    val my = (y0 + y1) / 2f
    val bulge = (w0 + w1) * 0.7f
    return Path().apply {
        moveTo(x0 + nx * w0, y0 + ny * w0)
        quadraticTo(mx + nx * bulge, my + ny * bulge, x1 + nx * w1, y1 + ny * w1)
        lineTo(x1 - nx * w1, y1 - ny * w1)
        quadraticTo(mx - nx * bulge, my - ny * bulge, x0 - nx * w0, y0 - ny * w0)
        close()
    }
}

private fun DrawScope.feather(
    base: Offset,
    tip: Offset,
    halfWidth: Float,
    color: Color,
    from: Float
) {
    drawPath(featherPath(base, tip, halfWidth, from, 1f), color)
}
