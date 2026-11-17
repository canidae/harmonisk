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

private val GullBody = Color(0xFFF8F7F1)
private val GullShade = Color(0xFFDCE3E3)
private val GullMantle = Color(0xFFC3D0D5)
private val GullWing = Color(0xFFC9D4D8)
private val GullWingLight = Color(0xFFE4EAEA)
private val GullWingShade = Color(0xFFB4C3C8)
private val GullTip = Color(0xFF5F6F77)
private val GullBeak = Color(0xFFF2B33E)
private val GullBeakSpot = Color(0xFFDC7B31)
private val GullEye = Color(0xFF273137)
private val GullShadow = Color(0xFF46575A)

@Composable
fun FreeBirdSeagull(modifier: Modifier = Modifier) {
    val phase by rememberFreeBirdPhase(5_600L, fps = 12)
    Canvas(modifier) {
        val unit = size.width
        val bx = unit * BIRD_PERCH_X
        val by = size.height * BIRD_PERCH_Y - unit * 0.0105f
        val angle = phase * 2f * PI.toFloat()
        drawSeagull(unit, bx, by, sin(angle), sin(angle + 1.1f), sin(angle + 2.2f))
    }
}

private fun DrawScope.drawSeagull(
    unit: Float,
    bx: Float,
    by: Float,
    lift: Float,
    headTurn: Float,
    tailSway: Float
) {
    fun x(value: Float) = bx + value * unit
    fun y(value: Float) = by + value * unit
    fun point(px: Float, py: Float) = Offset(x(px), y(py))

    rotate(-1.0f * lift, pivot = point(0.0f, 0.025f)) {
        val farWing = Path().apply {
            moveTo(x(0.0f), y(0.018f))
            cubicTo(x(0.06f), y(0.01f), x(0.15f), y(0.04f), x(0.245f), y(0.08f))
            cubicTo(x(0.2f), y(0.09f), x(0.13f), y(0.085f), x(0.07f), y(0.062f))
            lineTo(x(0.0f), y(0.042f))
            close()
        }
        drawPath(farWing, GullWingShade)
        clipPath(farWing) {
            drawRect(
                GullTip.copy(alpha = 0.85f),
                topLeft = Offset(x(0.205f), y(0.045f)),
                size = Size(0.06f * unit, 0.07f * unit)
            )
            drawLine(
                GullMantle.copy(alpha = 0.45f),
                point(0.215f, 0.06f),
                point(0.235f, 0.088f),
                strokeWidth = unit * 0.003f,
                cap = StrokeCap.Round
            )
            drawLine(
                GullMantle.copy(alpha = 0.35f),
                point(0.228f, 0.062f),
                point(0.243f, 0.085f),
                strokeWidth = unit * 0.0025f,
                cap = StrokeCap.Round
            )
        }
        drawLine(
            GullWingLight.copy(alpha = 0.5f),
            point(0.01f, 0.02f),
            point(0.22f, 0.076f),
            strokeWidth = unit * 0.0035f,
            cap = StrokeCap.Round
        )
        drawPath(farWing, GullShade.copy(alpha = 0.5f), style = Stroke(unit * 0.005f))
    }

    rotate(tailSway * 2.4f, pivot = point(-0.06f, 0.075f)) {
        val tail = Path().apply {
            moveTo(x(-0.072f), y(0.052f))
            cubicTo(x(-0.105f), y(0.058f), x(-0.135f), y(0.066f), x(-0.158f), y(0.078f))
            lineTo(x(-0.11f), y(0.095f))
            lineTo(x(-0.05f), y(0.088f))
            close()
        }
        drawPath(tail, GullWing)
        val tailTip = Path().apply {
            moveTo(x(-0.158f), y(0.078f))
            lineTo(x(-0.132f), y(0.079f))
            lineTo(x(-0.118f), y(0.088f))
            close()
        }
        drawPath(tailTip, GullTip.copy(alpha = 0.5f))
        drawPath(tail, GullShade.copy(alpha = 0.5f), style = Stroke(unit * 0.005f))
    }

    val body = Path().apply {
        moveTo(x(-0.09f), y(0.034f))
        cubicTo(x(-0.096f), y(0.068f), x(-0.06f), y(0.094f), x(0.002f), y(0.098f))
        cubicTo(x(0.064f), y(0.103f), x(0.101f), y(0.071f), x(0.106f), y(0.04f))
        cubicTo(x(0.109f), y(0.006f), x(0.062f), y(-0.006f), x(0.018f), y(-0.002f))
        cubicTo(x(-0.02f), y(0.002f), x(-0.073f), y(0.009f), x(-0.09f), y(0.034f))
        close()
    }
    drawPath(body, GullShadow.copy(alpha = 0.12f), style = Stroke(unit * 0.012f))
    drawPath(body, GullBody)
    val mantle = Path().apply {
        moveTo(x(-0.086f), y(0.041f))
        cubicTo(x(-0.077f), y(0.009f), x(-0.019f), y(-0.001f), x(0.026f), y(0.001f))
        cubicTo(x(0.065f), y(0.002f), x(0.098f), y(0.018f), x(0.104f), y(0.04f))
        cubicTo(x(0.06f), y(0.054f), x(0.0f), y(0.058f), x(-0.04f), y(0.055f))
        close()
    }
    drawPath(mantle, GullMantle.copy(alpha = 0.66f))
    drawOval(
        GullShade.copy(alpha = 0.6f),
        topLeft = point(-0.052f, 0.073f),
        size = Size(0.12f * unit, 0.031f * unit)
    )
    drawPath(body, GullShade.copy(alpha = 0.55f), style = Stroke(unit * 0.005f))

    rotate(2.2f * lift, pivot = point(-0.06f, 0.025f)) {
        val wing = Path().apply {
            moveTo(x(-0.06f), y(0.025f))
            cubicTo(x(-0.095f), y(0.04f), x(-0.14f), y(0.08f), x(-0.19f), y(0.12f))
            cubicTo(x(-0.16f), y(0.118f), x(-0.12f), y(0.092f), x(-0.085f), y(0.065f))
            lineTo(x(-0.06f), y(0.04f))
            close()
        }
        drawPath(wing, GullWingShade)
        val wingLight = Path().apply {
            moveTo(x(-0.06f), y(0.026f))
            cubicTo(x(-0.095f), y(0.043f), x(-0.137f), y(0.081f), x(-0.182f), y(0.117f))
            cubicTo(x(-0.15f), y(0.103f), x(-0.112f), y(0.079f), x(-0.078f), y(0.05f))
            close()
        }
        drawPath(wingLight, GullWingLight.copy(alpha = 0.45f))
        clipPath(wing) {
            drawRect(
                GullTip.copy(alpha = 0.9f),
                topLeft = Offset(x(-0.21f), y(0.085f)),
                size = Size(0.055f * unit, 0.06f * unit)
            )
            drawLine(
                GullWingLight.copy(alpha = 0.35f),
                point(-0.17f, 0.1f),
                point(-0.155f, 0.12f),
                strokeWidth = unit * 0.003f,
                cap = StrokeCap.Round
            )
            drawLine(
                GullWingLight.copy(alpha = 0.28f),
                point(-0.155f, 0.095f),
                point(-0.14f, 0.117f),
                strokeWidth = unit * 0.0025f,
                cap = StrokeCap.Round
            )
        }
        drawPath(wing, GullShadow.copy(alpha = 0.2f), style = Stroke(unit * 0.006f))
    }

    drawOval(
        GullShadow.copy(alpha = 0.1f),
        topLeft = point(-0.078f, -0.005f),
        size = Size(0.156f * unit, 0.026f * unit)
    )
    drawOval(
        GullShadow.copy(alpha = 0.16f),
        topLeft = point(-0.042f, -0.002f),
        size = Size(0.084f * unit, 0.013f * unit)
    )

    rotate(headTurn * 3.4f, pivot = point(0.078f, 0.02f)) {
        val neck = Path().apply {
            moveTo(x(0.046f), y(0.05f))
            cubicTo(x(0.052f), y(0.014f), x(0.073f), y(-0.012f), x(0.095f), y(-0.021f))
            lineTo(x(0.146f), y(0.0f))
            cubicTo(x(0.134f), y(0.034f), x(0.108f), y(0.054f), x(0.072f), y(0.058f))
            close()
        }
        drawPath(neck, GullBody)
        drawCircle(GullBody, unit * 0.033f, point(0.118f, -0.025f))
        val nape = Path().apply {
            moveTo(x(0.087f), y(-0.049f))
            cubicTo(x(0.079f), y(-0.022f), x(0.083f), y(0.006f), x(0.1f), y(0.026f))
            cubicTo(x(0.112f), y(0.008f), x(0.116f), y(-0.014f), x(0.108f), y(-0.043f))
            close()
        }
        drawPath(nape, GullMantle.copy(alpha = 0.5f))
        val beak = Path().apply {
            moveTo(x(0.144f), y(-0.036f))
            lineTo(x(0.212f), y(-0.022f))
            lineTo(x(0.144f), y(-0.01f))
            close()
        }
        drawPath(beak, GullBeak)
        drawCircle(GullBeakSpot, unit * 0.006f, point(0.173f, -0.023f))
        drawCircle(GullEye, unit * 0.0075f, point(0.126f, -0.035f))
        drawCircle(Color.White.copy(alpha = 0.9f), unit * 0.0027f, point(0.128f, -0.0375f))
    }
}
