package net.exent.harmonisk.ui.adventure

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.exent.harmonisk.R
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

private val ReelWidth = 250.dp
private val LeverWidth = 100.dp
private val ReelHeight = 138.dp
private val ReelItemHeight = 46.dp
private const val ReelFontSize = 20f
private val CabinetWidth = ReelWidth + LeverWidth
private val ReelPanel = Color(0xFF1B1733)

/**
 * Constant tempo for the first 40% of the spin, then a smooth deceleration to a dead stop.
 */
private object SlotEasing : Easing {
    private const val HoldFraction = 0.4f
    private const val Velocity = 2f / (1f + HoldFraction)

    override fun transform(fraction: Float): Float = when {
        fraction <= 0f -> 0f
        fraction >= 1f -> 1f
        fraction < HoldFraction -> Velocity * fraction
        else -> {
            val u = (fraction - HoldFraction) / (1f - HoldFraction)
            Velocity * (HoldFraction + (1f - HoldFraction) * u * (1f - u / 2f))
        }
    }
}

/**
 * Slot machine that reveals the song of the current turn: the lever is pulled during
 * [AdventureTurnPhase.PULLING], the reel scrolls at a constant tempo and then slows down during
 * [AdventureTurnPhase.SPINNING], the winning song is highlighted during [AdventureTurnPhase.HIGHLIGHT]
 * and the title then flies down to the turn card.
 *
 * @param mapCenter center of the map area in root coordinates
 * @param songAnchor left edge and vertical center of the song label in the turn card, in root coordinates
 * @param origin position of this screen's root box in root coordinates
 */
@Composable
fun SongRevealOverlay(
    state: AdventureState,
    mapCenter: Offset,
    songAnchor: Offset,
    origin: Offset,
    pullEnabled: Boolean = true,
    onPull: () -> Unit = {},
    onTick: (Float) -> Unit = {}
) {
    val song = state.currentSong ?: return
    val songs = state.songPool
    if (songs.isEmpty()) return
    val targetIndex = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
    val startIndex = remember(
        song.id,
        state.currentPlayerIndex,
        state.moveFrom,
        songs.size
    ) { Random.nextInt(songs.size) }
    val startScroll = startIndex.toFloat()
    val targetOffset = ((targetIndex - startIndex) % songs.size + songs.size) % songs.size
    val distance = targetOffset + songs.size
    val endScroll = (startIndex + distance).toFloat()

    val spin = remember { Animatable(startScroll) }
    val lever = remember { Animatable(0f) }
    val fly = remember { Animatable(0f) }
    var flying by remember { mutableStateOf(false) }
    var flightSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(state.phase) {
        when (state.phase) {
            AdventureTurnPhase.PULLING -> {
                flying = false
                fly.snapTo(0f)
                lever.snapTo(0f)
                spin.snapTo(startScroll)
            }
            AdventureTurnPhase.SPINNING -> {
                lever.snapTo(1f)
                var tickedRow = startScroll.toInt() - 1
                val lastRow = endScroll.toInt() - 1
                spin.animateTo(
                    endScroll,
                    tween(state.spinDurationMs.toInt(), easing = SlotEasing)
                ) {
                    val row = value.toInt().coerceAtMost(lastRow)
                    if (row != tickedRow) {
                        tickedRow = row
                        val progress = ((row - startScroll) / (endScroll - startScroll)).coerceIn(0f, 1f)
                        onTick(0.34f - 0.2f * progress)
                    }
                }
            }
            AdventureTurnPhase.HIGHLIGHT -> {
                spin.snapTo(endScroll)
                onTick(0.34f)
                delay(AdventureTiming.HOLD_MS)
                flying = true
                fly.animateTo(1f, tween(AdventureTiming.FLY_MS.toInt(), easing = FastOutSlowInEasing))
            }
            else -> {
                flying = false
                fly.snapTo(0f)
                lever.snapTo(0f)
                spin.snapTo(startScroll)
            }
        }
    }

    val density = LocalDensity.current
    val cabinetWidthPx = with(density) { CabinetWidth.toPx() }
    val cabinetHeightPx = with(density) { ReelHeight.toPx() }
    val leverWidthPx = with(density) { LeverWidth.toPx() }
    val mapReady = mapCenter.isSpecified
    val center = if (mapReady) mapCenter - origin else Offset.Zero
    val reelCenter = Offset(center.x - leverWidthPx / 2f, center.y)
    val cabinetOffset = IntOffset(
        (center.x - cabinetWidthPx / 2f).roundToInt(),
        (center.y - cabinetHeightPx / 2f).roundToInt()
    )
    val showReel = mapReady && (
        state.phase == AdventureTurnPhase.PULLING ||
            state.phase == AdventureTurnPhase.SPINNING ||
            state.phase == AdventureTurnPhase.HIGHLIGHT
        )
    val canFly = flying && mapReady && songAnchor.isSpecified
    val anchor = if (songAnchor.isSpecified) songAnchor - origin else Offset.Zero
    val flightFraction = fly.value
    val endScale = MaterialTheme.typography.bodyMedium.fontSize.value / ReelFontSize

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = showReel && !flying,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.86f),
            exit = fadeOut(tween(120)),
            modifier = Modifier.offset { cabinetOffset }.size(CabinetWidth, ReelHeight)
        ) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                SongReel(
                    songs = songs.map { it.name },
                    scroll = spin.value,
                    highlighted = state.phase == AdventureTurnPhase.HIGHLIGHT,
                    hint = if (state.phase == AdventureTurnPhase.PULLING) {
                        stringResource(R.string.adventure_pull_lever)
                    } else {
                        null
                    },
                    modifier = Modifier
                        .width(ReelWidth)
                        .fillMaxHeight()
                        .clickable(
                            enabled = state.phase == AdventureTurnPhase.PULLING && pullEnabled,
                            onClick = onPull
                        )
                )
                SlotLever(
                    pull = lever.value,
                    enabled = state.phase == AdventureTurnPhase.PULLING && pullEnabled,
                    onPull = onPull,
                    modifier = Modifier.width(LeverWidth).fillMaxHeight()
                )
            }
        }
        AnimatedVisibility(
            visible = showReel && state.phase == AdventureTurnPhase.PULLING,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .offset {
                    IntOffset(
                        0,
                        (center.y - cabinetHeightPx / 2f - 44.dp.toPx()).roundToInt()
                    )
                }
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(
                    onClick = onPull,
                    enabled = pullEnabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = StorybookGold)
                ) {
                    Text(
                        text = stringResource(R.string.adventure_pull_lever),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        if (canFly) {
            val flightScale = 1f + (endScale - 1f) * flightFraction
            val startX = reelCenter.x - flightSize.width / 2f
            val x = startX + (anchor.x - startX) * flightFraction
            val y = center.y + (anchor.y - center.y) * flightFraction
            Text(
                text = song.name,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = ReelFontSize.sp,
                fontWeight = FontWeight.Black,
                color = StorybookGold,
                maxLines = 1,
                modifier = Modifier
                    .offset { IntOffset(x.roundToInt(), (y - flightSize.height / 2f).roundToInt()) }
                    .onSizeChanged { flightSize = it }
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        scaleX = flightScale
                        scaleY = flightScale
                        alpha = 1f - 0.35f * flightFraction
                    }
            )
        }
    }
}

@Composable
private fun SlotLever(
    pull: Float,
    enabled: Boolean,
    onPull: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dragPull by remember { mutableStateOf(0f) }
    LaunchedEffect(enabled) {
        if (!enabled) dragPull = 0f
    }
    val density = LocalDensity.current
    val effectivePull = if (dragPull > 0f) dragPull else pull
    val gestureModifier = if (enabled) {
        Modifier.pointerInput(enabled, density) {
            detectVerticalDragGestures(
                onDragStart = { dragPull = 0f },
                onVerticalDrag = { change, amount ->
                    change.consume()
                    dragPull = (dragPull + amount / with(density) { 72.dp.toPx() }).coerceIn(0f, 1f)
                },
                onDragEnd = {
                    if (dragPull >= 0.55f) {
                        dragPull = 1f
                        onPull()
                    } else {
                        dragPull = 0f
                    }
                },
                onDragCancel = { dragPull = 0f }
            )
        }
    } else {
        Modifier
    }
    Canvas(modifier.then(gestureModifier)) {
        val height = size.height
        val plateLeft = 4.dp.toPx()
        val plateWidth = 46.dp.toPx()
        val plateCorner = CornerRadius(16.dp.toPx())
        val pivot = Offset(plateLeft + plateWidth / 2f, height - 26.dp.toPx())
        val armLength = 70.dp.toPx()
        val armWidth = 9.dp.toPx()
        val knobRadius = 13.dp.toPx()
        val angle = 4f + 91f * effectivePull

        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xFF332B4E), Color(0xFF141020))),
            topLeft = Offset(plateLeft, 0f),
            size = Size(plateWidth, height),
            cornerRadius = plateCorner
        )
        drawRoundRect(
            color = StorybookGold.copy(alpha = 0.22f),
            topLeft = Offset(plateLeft, 0f),
            size = Size(plateWidth, height),
            cornerRadius = plateCorner,
            style = Stroke(1.dp.toPx())
        )
        val bolt = Offset(plateLeft + plateWidth / 2f, 14.dp.toPx())
        drawCircle(Color(0xFF6C6488), 3.dp.toPx(), bolt)
        drawCircle(
            Color(0xFFBDB6D0),
            1.2.dp.toPx(),
            Offset(bolt.x - 0.7.dp.toPx(), bolt.y - 0.7.dp.toPx())
        )

        rotate(angle, pivot = pivot) {
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.32f),
                topLeft = Offset(
                    pivot.x - armWidth / 2f + 2.5.dp.toPx(),
                    pivot.y - armLength + 2.5.dp.toPx()
                ),
                size = Size(armWidth, armLength),
                cornerRadius = CornerRadius(armWidth / 2f)
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.26f),
                radius = knobRadius,
                center = Offset(pivot.x + 2.5.dp.toPx(), pivot.y - armLength + 2.5.dp.toPx())
            )
        }

        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xFFCFC8DE), Color(0xFF5A5272))),
            topLeft = Offset(pivot.x - 12.dp.toPx(), pivot.y - 9.dp.toPx()),
            size = Size(24.dp.toPx(), 20.dp.toPx()),
            cornerRadius = CornerRadius(7.dp.toPx())
        )
        drawCircle(Color(0xFF2A2440), 4.5.dp.toPx(), pivot)
        drawCircle(
            Color(0xFFE4DFF0),
            1.8.dp.toPx(),
            Offset(pivot.x - 1.2.dp.toPx(), pivot.y - 1.2.dp.toPx())
        )

        rotate(angle, pivot = pivot) {
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    0f to Color(0xFF453E5C),
                    0.28f to Color(0xFFF4F0FB),
                    0.5f to Color(0xFFC0B9D4),
                    1f to Color(0xFF3B3450)
                ),
                topLeft = Offset(pivot.x - armWidth / 2f, pivot.y - armLength),
                size = Size(armWidth, armLength),
                cornerRadius = CornerRadius(armWidth / 2f)
            )
            val knobCenter = Offset(pivot.x, pivot.y - armLength)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFFFFFF),
                        Color(0xFFFFE7A8),
                        StorybookGold,
                        Color(0xFF7E5214)
                    ),
                    center = Offset(
                        knobCenter.x - knobRadius * 0.35f,
                        knobCenter.y - knobRadius * 0.42f
                    ),
                    radius = knobRadius * 1.7f
                ),
                radius = knobRadius,
                center = knobCenter
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = knobRadius * 0.26f,
                center = Offset(
                    knobCenter.x - knobRadius * 0.36f,
                    knobCenter.y - knobRadius * 0.42f
                )
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.28f),
                radius = knobRadius,
                center = knobCenter,
                style = Stroke(1.dp.toPx())
            )
        }
    }
}

@Composable
private fun SongReel(
    songs: List<String>,
    scroll: Float,
    highlighted: Boolean,
    hint: String?,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = remember { TextStyle(fontSize = ReelFontSize.sp, fontWeight = FontWeight.Black) }
    val maxWidth = with(density) { (ReelWidth - 32.dp).toPx().roundToInt() }
    val layouts = remember(songs, style) {
        songs.map {
            measurer.measure(
                text = it,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = maxWidth)
            )
        }
    }
    val hintLayout = remember(hint, style) {
        hint?.let {
            measurer.measure(
                text = it,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = maxWidth)
            )
        }
    }
    val hintTransition = rememberInfiniteTransition(label = "reel_hint")
    val hintAlpha = hintTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "reel_hint_alpha"
    )

    Canvas(modifier.clip(RoundedCornerShape(20.dp))) {
        val width = size.width
        val height = size.height
        val itemHeight = ReelItemHeight.toPx()
        val corner = CornerRadius(20.dp.toPx())
        val centerY = height / 2f

        drawRoundRect(ReelPanel, cornerRadius = corner)
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xFF322A4C), Color(0xFF0F0C19))),
            cornerRadius = corner
        )
        drawRoundRect(
            color = StorybookGold.copy(alpha = if (highlighted) 0.22f else 0.10f),
            topLeft = Offset(8.dp.toPx(), centerY - itemHeight / 2f),
            size = Size(width - 16.dp.toPx(), itemHeight),
            cornerRadius = CornerRadius(12.dp.toPx())
        )
        val bandTop = centerY - itemHeight / 2f
        val bandBottom = centerY + itemHeight / 2f
        drawLine(StorybookGold.copy(alpha = 0.45f), Offset(10.dp.toPx(), bandTop), Offset(width - 10.dp.toPx(), bandTop), 1.dp.toPx())
        drawLine(StorybookGold.copy(alpha = 0.45f), Offset(10.dp.toPx(), bandBottom), Offset(width - 10.dp.toPx(), bandBottom), 1.dp.toPx())

        val count = layouts.size
        val reelAlpha = if (hint != null) 0.5f else 1f
        if (count > 0) {
            val whole = floor(scroll).toInt()
            val fraction = scroll - whole
            for (slot in -3..3) {
                val y = centerY + (slot - fraction) * itemHeight
                if (y < -itemHeight || y > height + itemHeight) continue
                val index = ((whole + slot) % count + count) % count
                val distance = abs(slot - fraction)
                val layout = layouts[index]
                val isWinner = highlighted && distance < 0.5f
                val alpha = (1f - distance * 0.34f).coerceIn(0f, 1f) * reelAlpha
                val itemScale = (1f - distance * 0.10f).coerceAtLeast(0.65f)
                scale(itemScale, pivot = Offset(width / 2f, y)) {
                    drawText(
                        textLayoutResult = layout,
                        color = if (isWinner) StorybookGold else Color.White.copy(alpha = alpha),
                        topLeft = Offset((width - layout.size.width) / 2f, y - layout.size.height / 2f),
                        shadow = if (isWinner) Shadow(StorybookGold, blurRadius = 14f) else null
                    )
                }
            }
        }

        drawRect(
            Brush.verticalGradient(
                0f to ReelPanel,
                0.24f to ReelPanel.copy(alpha = 0f),
                0.76f to ReelPanel.copy(alpha = 0f),
                1f to ReelPanel
            )
        )
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = 0.4f),
                0.16f to Color.Transparent,
                0.84f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.4f)
            )
        )
        hintLayout?.let { layout ->
            drawRoundRect(
                color = ReelPanel.copy(alpha = 0.98f),
                topLeft = Offset(8.dp.toPx(), bandTop),
                size = Size(width - 16.dp.toPx(), itemHeight),
                cornerRadius = CornerRadius(12.dp.toPx())
            )
            drawRoundRect(
                color = StorybookGold.copy(alpha = 0.6f),
                topLeft = Offset(8.dp.toPx(), bandTop),
                size = Size(width - 16.dp.toPx(), itemHeight),
                cornerRadius = CornerRadius(12.dp.toPx()),
                style = Stroke(1.5.dp.toPx())
            )
            drawText(
                textLayoutResult = layout,
                color = StorybookGold.copy(alpha = hintAlpha.value),
                topLeft = Offset((width - layout.size.width) / 2f, centerY - layout.size.height / 2f)
            )
        }
        drawRoundRect(
            color = StorybookGold.copy(alpha = 0.35f),
            cornerRadius = corner,
            style = Stroke(1.5.dp.toPx())
        )
    }
}
