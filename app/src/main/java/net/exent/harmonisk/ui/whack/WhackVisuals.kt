package net.exent.harmonisk.ui.whack

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

private val PressSteel = Color(0xFFC3D0D6)
private val PressSteelDark = Color(0xFF6C7F88)
private val PressPlateDark = Color(0xFF4F6169)
private val PressAccent = Color(0xFF2E8C82)

@Composable
fun WhackPressIcon(modifier: Modifier, bodyFraction: Float = 0.56f) {
    Canvas(modifier) { drawWhackPress(bodyFraction) }
}

fun DrawScope.drawWhackPress(bodyFraction: Float = 0.56f) {
    val w = size.width
    val h = size.height
    val outline = h * 0.035f
    val bodyWidth = w * bodyFraction
    val left = w / 2 - bodyWidth / 2
    val top = h * 0.05f
    val bottom = h * 0.95f
    val radius = bodyWidth * 0.14f
    val faceHeight = h * 0.16f

    drawRoundRect(
        color = PressSteelDark,
        topLeft = Offset(left, top),
        size = Size(bodyWidth, bottom - top),
        cornerRadius = CornerRadius(radius)
    )
    drawRoundRect(
        color = PressSteel,
        topLeft = Offset(left + outline, top + outline),
        size = Size(bodyWidth - outline * 2, bottom - top - outline * 2),
        cornerRadius = CornerRadius(radius)
    )
    drawOval(
        color = PressSteelDark,
        topLeft = Offset(left, h * 0.005f),
        size = Size(bodyWidth, faceHeight)
    )
    drawOval(
        color = PressSteel,
        topLeft = Offset(left + outline, h * 0.005f + outline),
        size = Size(bodyWidth - outline * 2, faceHeight - outline * 2)
    )
    drawRoundRect(
        color = PressAccent,
        topLeft = Offset(left + outline, h * 0.54f),
        size = Size(bodyWidth - outline * 2, h * 0.10f),
        cornerRadius = CornerRadius(h * 0.035f)
    )
    drawOval(
        color = PressPlateDark,
        topLeft = Offset(left, h - faceHeight - h * 0.005f),
        size = Size(bodyWidth, faceHeight)
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.28f),
        topLeft = Offset(left + bodyWidth * 0.18f, top + h * 0.09f),
        size = Size(bodyWidth * 0.10f, h * 0.52f),
        cornerRadius = CornerRadius(h * 0.03f)
    )
}
