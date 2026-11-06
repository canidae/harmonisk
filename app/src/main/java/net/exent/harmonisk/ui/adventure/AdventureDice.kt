package net.exent.harmonisk.ui.adventure

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun times(scale: Float) = Vec3(x * scale, y * scale, z * scale)
}

private data class DiceFace(val value: Int, val normal: Vec3, val u: Vec3, val v: Vec3)

private val DICE_FACES = listOf(
    DiceFace(1, Vec3(0f, 0f, 1f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f)),
    DiceFace(6, Vec3(0f, 0f, -1f), Vec3(-1f, 0f, 0f), Vec3(0f, 1f, 0f)),
    DiceFace(3, Vec3(1f, 0f, 0f), Vec3(0f, 0f, -1f), Vec3(0f, 1f, 0f)),
    DiceFace(4, Vec3(-1f, 0f, 0f), Vec3(0f, 0f, 1f), Vec3(0f, 1f, 0f)),
    DiceFace(2, Vec3(0f, 1f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 0f, -1f)),
    DiceFace(5, Vec3(0f, -1f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 0f, 1f))
)

private val PIPS = mapOf(
    1 to listOf(0f to 0f),
    2 to listOf(-0.36f to 0.36f, 0.36f to -0.36f),
    3 to listOf(-0.36f to 0.36f, 0f to 0f, 0.36f to -0.36f),
    4 to listOf(-0.36f to -0.36f, -0.36f to 0.36f, 0.36f to -0.36f, 0.36f to 0.36f),
    5 to listOf(
        -0.36f to -0.36f, -0.36f to 0.36f, 0f to 0f, 0.36f to -0.36f, 0.36f to 0.36f
    ),
    6 to listOf(
        -0.36f to -0.36f, -0.36f to 0f, -0.36f to 0.36f,
        0.36f to -0.36f, 0.36f to 0f, 0.36f to 0.36f
    )
)

private val DIE_LIGHT = Vec3(-0.35f, 0.55f, 0.75f)
private const val PIP_RADIUS = 0.15f
private const val PIP_SEGMENTS = 18
private val DIE_BASE = Color(0xFFF6EEDF)
private val DIE_SHADE = Color(0xFF8A7A66)
private val PIP_DARK = Color(0xFF5B1818)
private val PIP_LIGHT = Color(0xFFC05252)
private val PIP_RIM = Color(0xFF3E0C0C).copy(alpha = 0.75f)
private val EDGE_COLOR = Color(0xFF5B4A3A).copy(alpha = 0.35f)

private fun rotateX(v: Vec3, angleDegrees: Float): Vec3 {
    val a = Math.toRadians(angleDegrees.toDouble())
    val c = cos(a).toFloat()
    val s = sin(a).toFloat()
    return Vec3(v.x, v.y * c - v.z * s, v.y * s + v.z * c)
}

private fun rotateY(v: Vec3, angleDegrees: Float): Vec3 {
    val a = Math.toRadians(angleDegrees.toDouble())
    val c = cos(a).toFloat()
    val s = sin(a).toFloat()
    return Vec3(v.x * c + v.z * s, v.y, -v.x * s + v.z * c)
}

private fun rotate(v: Vec3, angleX: Float, angleY: Float): Vec3 =
    rotateY(rotateX(v, angleX), angleY)

private fun targetAngles(value: Int): Pair<Float, Float> = when (value) {
    2 -> 90f to 0f
    3 -> 0f to -90f
    4 -> 0f to 90f
    5 -> -90f to 0f
    6 -> 0f to 180f
    else -> 0f to 0f
}

private data class ProjectedFace(
    val value: Int,
    val corners: List<Offset>,
    val pips: List<Path>,
    val normal: Vec3
)

@Composable
fun Dice3D(value: Int?, progress: Float, modifier: Modifier = Modifier) {
    val rolled = value ?: 1
    val (targetX, targetY) = targetAngles(rolled)
    val turnsX = if (rolled % 2 == 0) 1f else 2f
    val turnsY = if (rolled % 2 == 0) 2f else 1f
    val angleX = (targetX + 360f * turnsX) * progress
    val angleY = (targetY + 360f * turnsY) * progress
    Canvas(modifier) { drawDie(angleX, angleY) }
}

private fun DrawScope.drawDie(angleX: Float, angleY: Float) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val scale = size.minDimension * 0.32f
    val camera = 5.5f

    fun project(p: Vec3): Offset {
        val f = camera / (camera - p.z)
        return Offset(center.x + p.x * f * scale, center.y - p.y * f * scale)
    }

    fun pipPath(pipCenter: Vec3, u: Vec3, v: Vec3): Path {
        val path = Path()
        for (i in 0 until PIP_SEGMENTS) {
            val angle = 2.0 * PI * i / PIP_SEGMENTS
            val point = pipCenter +
                u * (PIP_RADIUS * cos(angle).toFloat()) +
                v * (PIP_RADIUS * sin(angle).toFloat())
            val projected = project(point)
            if (i == 0) path.moveTo(projected.x, projected.y)
            else path.lineTo(projected.x, projected.y)
        }
        path.close()
        return path
    }

    val faces = DICE_FACES.map { face ->
        val normal = rotate(face.normal, angleX, angleY)
        val u = rotate(face.u, angleX, angleY)
        val v = rotate(face.v, angleX, angleY)
        val faceCenter = normal
        val corners = listOf(
            project(faceCenter + u * -1f + v * -1f),
            project(faceCenter + u * 1f + v * -1f),
            project(faceCenter + u * 1f + v * 1f),
            project(faceCenter + u * -1f + v * 1f)
        )
        val pips = PIPS.getValue(face.value).map { (px, py) ->
            pipPath(faceCenter + u * px + v * py, u, v)
        }
        ProjectedFace(face.value, corners, pips, normal)
    }
    faces.filter { it.normal.z > 0.02f }.sortedBy { it.normal.z }.forEach { face ->
        val light = (0.55f + 0.45f * (face.normal.x * DIE_LIGHT.x +
            face.normal.y * DIE_LIGHT.y + face.normal.z * DIE_LIGHT.z)).coerceIn(0.55f, 1f)
        val color = lerp(DIE_SHADE, DIE_BASE, light)
        val path = Path().apply {
            moveTo(face.corners[0].x, face.corners[0].y)
            for (i in 1..3) lineTo(face.corners[i].x, face.corners[i].y)
            close()
        }
        drawPath(path, color)
        drawPath(path, EDGE_COLOR, style = Stroke(width = 1.dp.toPx()))
        face.pips.forEach { pip ->
            val bounds = pip.getBounds()
            val pipRadius = min(bounds.width, bounds.height) / 2f
            drawPath(pip, PIP_DARK)
            clipPath(pip) {
                drawCircle(
                    color = PIP_LIGHT,
                    radius = pipRadius * 0.86f,
                    center = bounds.center + Offset(pipRadius * 0.12f, pipRadius * 0.12f),
                    style = Stroke(width = pipRadius * 0.22f)
                )
            }
            drawPath(
                pip,
                PIP_RIM,
                style = Stroke(width = (pipRadius * 0.18f).coerceAtLeast(0.7.dp.toPx()))
            )
        }
    }
}
