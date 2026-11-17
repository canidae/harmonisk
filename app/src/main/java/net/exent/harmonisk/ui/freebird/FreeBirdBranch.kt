package net.exent.harmonisk.ui.freebird

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate

const val BRANCH_PERCH_X = 0.17f
const val BRANCH_PERCH_Y = 0.842f

private val Bark = Color(0xFF5A3B27)
private val BarkLight = Color(0xFF8A6241)
private val LeafDark = Color(0xFF2C5A3C)
private val Leaf = Color(0xFF3F7C55)

@Composable
fun FreeBirdBranch(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        val main = Path().apply {
            moveTo(-0.04f * width, 0.895f * height)
            cubicTo(
                0.03f * width, 0.874f * height,
                0.09f * width, 0.852f * height,
                BRANCH_PERCH_X * width, BRANCH_PERCH_Y * height
            )
            cubicTo(
                0.21f * width, 0.834f * height,
                0.25f * width, 0.827f * height,
                0.29f * width, 0.835f * height
            )
        }
        drawPath(main, Bark, style = Stroke(width = width * 0.021f, cap = StrokeCap.Round))
        drawPath(
            main,
            BarkLight.copy(alpha = 0.38f),
            style = Stroke(width = width * 0.006f, cap = StrokeCap.Round)
        )

        val fork = Path().apply {
            moveTo(0.21f * width, 0.834f * height)
            cubicTo(0.225f * width, 0.815f * height, 0.235f * width, 0.8f * height, 0.25f * width, 0.79f * height)
        }
        drawPath(fork, Bark, style = Stroke(width = width * 0.010f, cap = StrokeCap.Round))

        val twig = Path().apply {
            moveTo(0.275f * width, 0.832f * height)
            cubicTo(0.29f * width, 0.818f * height, 0.30f * width, 0.808f * height, 0.315f * width, 0.8f * height)
        }
        drawPath(twig, Bark, style = Stroke(width = width * 0.006f, cap = StrokeCap.Round))

        leaf(width * 0.25f, height * 0.787f, width * 0.026f, height * 0.016f, -0.5f, LeafDark)
        leaf(width * 0.272f, height * 0.784f, width * 0.024f, height * 0.014f, 0.25f, Leaf)
        leaf(width * 0.315f, height * 0.798f, width * 0.023f, height * 0.014f, 0.6f, LeafDark)
        leaf(width * 0.332f, height * 0.808f, width * 0.018f, height * 0.011f, -0.3f, Leaf)
        leaf(width * 0.06f, height * 0.835f, width * 0.028f, height * 0.017f, -0.8f, LeafDark)
    }
}

private fun DrawScope.leaf(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    angle: Float,
    color: Color
) {
    rotate(angle, pivot = Offset(x, y)) {
        drawOval(
            color = color,
            topLeft = Offset(x - width / 2f, y - height / 2f),
            size = Size(width, height)
        )
    }
}
