package net.exent.harmonisk.ui.adventure

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.exent.harmonisk.ui.theme.AdventureGold
import kotlin.math.PI
import kotlin.math.sin

val ADVENTURE_PLAYER_COLORS = listOf(
    Color(0xFF9B7BFF),
    Color(0xFFFF8E72),
    Color(0xFF48D1CC),
    AdventureGold
)

private val GROUND_TOP = Color(0xFF24432F)
private val GROUND_BOTTOM = Color(0xFF0C1711)
val StorybookGold = Color(0xFFFFD166)
val StorybookMint = Color(0xFF8DE8C4)

@Composable
fun AdventureBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(0f to GROUND_TOP, 0.55f to Color(0xFF172A1E), 1f to GROUND_BOTTOM)
        )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawOval(
                color = Color(0xFF3B6B42).copy(alpha = 0.22f),
                topLeft = Offset(-size.width * 0.15f, size.height * 0.1f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.8f, size.height * 0.5f)
            )
            drawOval(
                color = Color(0xFF6B9B52).copy(alpha = 0.1f),
                topLeft = Offset(size.width * 0.45f, size.height * 0.25f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.8f, size.height * 0.65f)
            )
            repeat(4) { index ->
                val y = size.height * (0.25f + index * 0.18f)
                val contour = Path().apply {
                    moveTo(0f, y)
                    cubicTo(
                        size.width * 0.25f, y - size.height * 0.08f,
                        size.width * 0.55f, y + size.height * 0.08f,
                        size.width, y - size.height * 0.03f
                    )
                }
                drawPath(contour, Color(0xFF7FAF65).copy(alpha = 0.09f), style = Stroke(width = 2f))
            }
            val trees = Path().apply {
                moveTo(0f, size.height)
                lineTo(0f, size.height * 0.88f)
                lineTo(size.width * 0.06f, size.height * 0.8f)
                lineTo(size.width * 0.12f, size.height * 0.9f)
                lineTo(size.width * 0.2f, size.height * 0.82f)
                lineTo(size.width * 0.3f, size.height * 0.92f)
                lineTo(size.width * 0.42f, size.height * 0.84f)
                lineTo(size.width * 0.55f, size.height * 0.94f)
                lineTo(size.width * 0.68f, size.height * 0.86f)
                lineTo(size.width * 0.8f, size.height * 0.92f)
                lineTo(size.width * 0.92f, size.height * 0.84f)
                lineTo(size.width, size.height * 0.9f)
                lineTo(size.width, size.height)
                close()
            }
            drawPath(trees, Color(0xFF0A140E).copy(alpha = 0.55f))
        }
        content()
    }
}

@Composable
fun AdventureAvatar(
    emoji: String,
    colorIndex: Int,
    size: Dp = 52.dp,
    selected: Boolean = false,
    modifier: Modifier = Modifier
) {
    val color = ADVENTURE_PLAYER_COLORS[colorIndex % ADVENTURE_PLAYER_COLORS.size]
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f))))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) StorybookGold else Color.White.copy(alpha = 0.3f),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            fontSize = (size.value * 0.48f).sp
        )
    }
}

@Composable
fun ClapBurst(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "clap")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "clap_time"
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(5) { index ->
            val phase = (time + index * 0.17f) % 1f
            Text(
                text = "👏",
                fontSize = 28.sp,
                modifier = Modifier.graphicsLayer {
                    translationY = (-sin(phase * PI).toFloat() * 12.dp.toPx())
                    rotationZ = sin(phase * PI * 2).toFloat() * 8f
                    alpha = 0.55f + sin(phase * PI).toFloat() * 0.45f
                }
            )
        }
    }
}

@Composable
fun AdventurePill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.55f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun StorybookCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Color(0xF21B1733))
            .border(1.dp, StorybookGold.copy(alpha = 0.22f), MaterialTheme.shapes.extraLarge)
            .padding(18.dp),
        content = content
    )
}

