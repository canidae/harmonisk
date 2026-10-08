package net.exent.harmonisk.ui.celebration

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.PartyFactory
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import nl.dionsegijn.konfetti.core.Rotation
import nl.dionsegijn.konfetti.core.models.Shape
import nl.dionsegijn.konfetti.core.models.Size
import kotlin.math.cos
import kotlin.math.sin
import java.util.concurrent.TimeUnit

private val CELEBRATION_COLORS = listOf(
    0xFFCFBCFF.toInt(),
    0xFFA5D6A7.toInt(),
    0xFF80CBC4.toInt(),
    0xFFFFF59D.toInt(),
    0xFFFFCC80.toInt(),
    0xFFFF80AB.toInt(),
    0xFF80D8FF.toInt(),
    0xFFCCFF90.toInt()
)

@Composable
fun ScoreCelebrationOverlay(
    celebrationLevel: CelebrationLevel,
    modifier: Modifier = Modifier,
    content: @Composable (pulseModifier: Modifier) -> Unit
) {
    val payloads = remember(celebrationLevel) { celebrationLevel.toPayloads() }

    // ── Haptics ──────────────────────────────────────────────────────────
    val hapticPayload = payloads.filterIsInstance<CelebrationPayload.Haptic>().firstOrNull()
    if (hapticPayload != null) {
        val haptic = LocalHapticFeedback.current
        LaunchedEffect(celebrationLevel) {
            repeat(hapticPayload.intensity) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    // ── Pulsing + Jitter animation ─────────────────────────────────────
    val pulseScale = payloads.filterIsInstance<CelebrationPayload.Pulsing>().firstOrNull()?.scale
    val jitterPayload = payloads.filterIsInstance<CelebrationPayload.Jitter>().firstOrNull()

    val infiniteTransition = rememberInfiniteTransition(label = "celebration")

    val animScale by if (pulseScale != null) {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = pulseScale,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    val jitterX by if (jitterPayload != null) {
        infiniteTransition.animateFloat(
            initialValue = -jitterPayload.amplitude,
            targetValue = jitterPayload.amplitude,
            animationSpec = infiniteRepeatable(
                animation = tween((1000f / jitterPayload.frequency).toInt()),
                repeatMode = RepeatMode.Reverse
            ),
            label = "jitter_x"
        )
    } else {
        remember { mutableStateOf(0f) }
    }
    val jitterY by if (jitterPayload != null) {
        infiniteTransition.animateFloat(
            initialValue = -jitterPayload.amplitude,
            targetValue = jitterPayload.amplitude,
            animationSpec = infiniteRepeatable(
                animation = tween((1000f / jitterPayload.frequency / 2).toInt()),
                repeatMode = RepeatMode.Reverse
            ),
            label = "jitter_y"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    val hasTransform = pulseScale != null || jitterPayload != null
    val pulseModifier = if (hasTransform) {
        Modifier.graphicsLayer {
            scaleX = animScale
            scaleY = animScale
            translationX = jitterX
            translationY = jitterY
        }
    } else {
        Modifier
    }

    // ── Konfetti ────────────────────────────────────────────────────────
    val confettiPayload = payloads.filterIsInstance<CelebrationPayload.Confetti>().firstOrNull()
    val burstCount = confettiPayload?.count ?: 0

    val parties = remember(celebrationLevel, burstCount) {
        if (burstCount > 0) {
            val sideCount = (burstCount / 3).coerceAtLeast(1)
            val centerCount = burstCount - sideCount * 2
            listOf(
                PartyFactory(Emitter(3, TimeUnit.SECONDS).max(centerCount))
                    .angle(270)
                    .spread(120)
                    .setSpeedBetween(10f, 25f)
                    .setDamping(0.85f)
                    .sizes(celebrationLevel.confettiSizes)
                    .colors(CELEBRATION_COLORS)
                    .shapes(listOf(Shape.Circle, Shape.Square, Shape.Rectangle(0.5f)))
                    .timeToLive(5000L)
                    .fadeOutEnabled(true)
                    .position(Position.Relative(0.5, 0.3))
                    .rotation(Rotation())
                    .build(),
                PartyFactory(Emitter(3, TimeUnit.SECONDS).max(sideCount))
                    .angle(315)
                    .spread(60)
                    .setSpeedBetween(15f, 30f)
                    .setDamping(0.85f)
                    .sizes(celebrationLevel.confettiSizes)
                    .colors(CELEBRATION_COLORS)
                    .shapes(listOf(Shape.Circle, Shape.Square, Shape.Rectangle(0.5f)))
                    .timeToLive(5000L)
                    .fadeOutEnabled(true)
                    .position(Position.Relative(0.0, 0.7))
                    .rotation(Rotation())
                    .build(),
                PartyFactory(Emitter(3, TimeUnit.SECONDS).max(sideCount))
                    .angle(225)
                    .spread(60)
                    .setSpeedBetween(15f, 30f)
                    .setDamping(0.85f)
                    .sizes(celebrationLevel.confettiSizes)
                    .colors(CELEBRATION_COLORS)
                    .shapes(listOf(Shape.Circle, Shape.Square, Shape.Rectangle(0.5f)))
                    .timeToLive(5000L)
                    .fadeOutEnabled(true)
                    .position(Position.Relative(1.0, 0.7))
                    .rotation(Rotation())
                    .build()
            )
        } else {
            emptyList()
        }
    }

    // ── Fireworks ──────────────────────────────────────────────────────
    val fireworksPayload = payloads.filterIsInstance<CelebrationPayload.Fireworks>().firstOrNull()
    val fireworkBursts = fireworksPayload?.burstCount ?: 0

    // ── Layout ──────────────────────────────────────────────────────────
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Content with pulsing + jitter (bottom layer)
        content(pulseModifier)

        // Konfetti layer (on top of content)
        KonfettiView(
            Modifier.fillMaxSize(),
            parties,
            null
        )

        // Music note particles
        val musicNotePayload = payloads.filterIsInstance<CelebrationPayload.MusicNotes>().firstOrNull()
        if (musicNotePayload != null) {
            MusicNoteParticles(
                modifier = Modifier.fillMaxSize(),
                count = musicNotePayload.count,
                triggerKey = celebrationLevel
            )
        }

        // Firework bursts (on top of everything)
        if (fireworkBursts > 0) {
            val alignments = listOf(
                Alignment.TopStart, Alignment.TopEnd,
                Alignment.BottomStart, Alignment.BottomEnd,
                Alignment.Center
            )
            repeat(fireworkBursts.coerceAtMost(alignments.size)) { index ->
                FireworkBurst(
                    modifier = Modifier
                        .size(120.dp)
                        .align(alignments[index]),
                    triggerKey = fireworkBursts
                )
            }
        }
    }
}

@Composable
private fun FireworkBurst(
    modifier: Modifier,
    triggerKey: Int
) {
    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(triggerKey) {
        animProgress.snapTo(0f)
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(800, easing = FastOutSlowInEasing)
        )
    }

    val progress = animProgress.value

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxRadius = size.minDimension * 0.4f * progress
        val sparkCount = 12
        val ringAlpha = (1f - progress).coerceIn(0f, 1f)

        // Expanding ring
        drawCircle(
            color = Color(0xFFCFBCFF).copy(alpha = ringAlpha * 0.4f),
            radius = maxRadius,
            center = Offset(cx, cy),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = (3f - progress * 2f).coerceAtLeast(0.5f)
            )
        )

        // Radial spark lines
        for (i in 0 until sparkCount) {
            val angle = (Math.PI * 2 / sparkCount) * i + progress * 2f
            val length = maxRadius * (0.6f + 0.4f * (1f - progress))
            val startX = cx + (cos(angle) * maxRadius * 0.2f).toFloat()
            val startY = cy + (sin(angle) * maxRadius * 0.2f).toFloat()
            val endX = cx + (cos(angle) * length).toFloat()
            val endY = cy + (sin(angle) * length).toFloat()
            drawLine(
                color = fireworkColor(i).copy(alpha = ringAlpha),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 2f + progress * 2f,
                cap = StrokeCap.Round
            )
        }

        // Center glow
        drawCircle(
            color = Color(0xFFFFD700).copy(alpha = ringAlpha * 0.8f),
            radius = 4f + (1f - progress) * 8f,
            center = Offset(cx, cy)
        )
    }
}

private fun fireworkColor(index: Int): Color = when (index % 6) {
    0 -> Color(0xFFCFBCFF)
    1 -> Color(0xFFA5D6A7)
    2 -> Color(0xFF80CBC4)
    3 -> Color(0xFFFFF59D)
    4 -> Color(0xFFFFCC80)
    5 -> Color(0xFFFF80AB)
    else -> Color(0xFFCFBCFF)
}
