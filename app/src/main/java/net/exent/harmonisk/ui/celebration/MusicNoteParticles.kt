package net.exent.harmonisk.ui.celebration

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.random.Random

private val MUSIC_SYMBOLS = listOf("\u266A", "\u266B", "\u266C", "\uD83C\uDFB5", "\uD83C\uDFB6")

private val MUSIC_NOTE_COLORS = listOf(
    Color(0xFFCFBCFF),
    Color(0xFFA5D6A7),
    Color(0xFF80CBC4),
    Color(0xFFFFF59D),
    Color(0xFFFFCC80),
    Color(0xFFFF80AB),
    Color(0xFF80D8FF),
    Color(0xFFCCFF90)
)

private data class MusicParticle(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val rotation: Float,
    val rotationSpeed: Float,
    val alpha: Float,
    val scale: Float,
    val bitmapIndex: Int,
    val color: Color,
    val elapsed: Float,
    val duration: Float
)

@Composable
fun MusicNoteParticles(
    modifier: Modifier = Modifier,
    count: Int,
    triggerKey: Any
) {
    if (count <= 0) return

    val bitmaps = remember { MUSIC_SYMBOLS.map { it.toMusicNoteImageBitmap() } }

    var particles by remember { mutableStateOf(listOf<MusicParticle>()) }

    LaunchedEffect(triggerKey) {
        particles = List(count) {
            MusicParticle(
                x = Random.nextFloat(),
                y = Random.nextFloat() * 0.4f + 0.6f,
                vx = (Random.nextFloat() - 0.5f) * 0.2f,
                vy = -(Random.nextFloat() * 0.3f + 0.2f),
                rotation = Random.nextFloat() * 360f,
                rotationSpeed = (Random.nextFloat() - 0.5f) * 180f,
                alpha = 0f,
                scale = Random.nextFloat() * 0.9f + 0.6f,
                bitmapIndex = Random.nextInt(bitmaps.size),
                color = MUSIC_NOTE_COLORS[Random.nextInt(MUSIC_NOTE_COLORS.size)],
                elapsed = 0f,
                duration = 4f + Random.nextFloat() * 1f
            )
        }
    }

    LaunchedEffect(triggerKey) {
        var lastFrameTime = withFrameMillis { it }
        while (true) {
            val frameTime = withFrameMillis { it }
            val dt = ((frameTime - lastFrameTime) / 1000f).coerceAtMost(0.05f)
            lastFrameTime = frameTime

            particles = particles.mapNotNull { p ->
                val newElapsed = p.elapsed + dt
                if (newElapsed > p.duration) return@mapNotNull null

                val progress = newElapsed / p.duration
                val newAlpha = when {
                    progress < 0.1f -> progress / 0.1f
                    progress > 0.7f -> 1f - (progress - 0.7f) / 0.3f
                    else -> 1f
                }

                MusicParticle(
                    x = p.x + p.vx * dt,
                    y = p.y + p.vy * dt,
                    vx = p.vx,
                    vy = p.vy,
                    rotation = p.rotation + p.rotationSpeed * dt,
                    rotationSpeed = p.rotationSpeed,
                    alpha = newAlpha,
                    scale = p.scale,
                    bitmapIndex = p.bitmapIndex,
                    color = p.color,
                    elapsed = newElapsed,
                    duration = p.duration
                )
            }
            if (particles.isEmpty()) break
        }
        particles = emptyList()
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        particles.forEach { p ->
            val bitmap = bitmaps[p.bitmapIndex]
            val px = p.x * w
            val py = p.y * h
            val halfW = bitmap.width * p.scale / 2f
            val halfH = bitmap.height * p.scale / 2f

            translate(px, py) {
                rotate(p.rotation, pivot = Offset.Zero) {
                    drawImage(
                        image = bitmap,
                        dstOffset = IntOffset((-halfW).toInt(), (-halfH).toInt()),
                        dstSize = IntSize(
                            (bitmap.width * p.scale).toInt(),
                            (bitmap.height * p.scale).toInt()
                        ),
                        alpha = p.alpha,
                        colorFilter = ColorFilter.tint(p.color)
                    )
                }
            }
        }
    }
}

private fun String.toMusicNoteImageBitmap(): ImageBitmap {
    val size = 100f
    val bitmap = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size * 0.8f
        textAlign = Paint.Align.CENTER
        color = android.graphics.Color.WHITE
    }
    val x = canvas.width / 2f
    val y = (canvas.height / 2f) - ((paint.descent() + paint.ascent()) / 2f)
    canvas.drawText(this, x, y, paint)
    return bitmap.asImageBitmap()
}
