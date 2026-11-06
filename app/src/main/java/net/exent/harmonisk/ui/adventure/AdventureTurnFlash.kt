package net.exent.harmonisk.ui.adventure

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import net.exent.harmonisk.R
import kotlin.math.roundToInt

@Composable
fun TurnFlashOverlay(
    state: AdventureState,
    mapCenter: Offset,
    flashAnchor: Offset,
    origin: Offset,
    onRoll: () -> Unit
) {
    val player = state.currentPlayer ?: return
    var size by remember { mutableStateOf(IntSize.Zero) }
    var flying by remember { mutableStateOf(false) }
    val fly = remember { Animatable(0f) }
    LaunchedEffect(state.phase) {
        when (state.phase) {
            AdventureTurnPhase.ROLLING, AdventureTurnPhase.DICE -> {
                flying = false
                fly.snapTo(0f)
            }
            AdventureTurnPhase.MOVING -> {
                flying = true
                fly.snapTo(0f)
                fly.animateTo(
                    1f,
                    tween(AdventureTiming.FLASH_FLY_MS.toInt(), easing = FastOutSlowInEasing)
                )
                flying = false
            }
            else -> {
                flying = false
                fly.snapTo(0f)
            }
        }
    }
    val diceProgress = remember { Animatable(0f) }
    LaunchedEffect(state.diceValue) {
        diceProgress.snapTo(0f)
        if (state.diceValue != null) {
            diceProgress.animateTo(
                1f,
                tween(AdventureTiming.DICE_SHOW_MS.toInt(), easing = FastOutSlowInEasing)
            )
        }
    }
    val visible = state.phase == AdventureTurnPhase.ROLLING ||
        state.phase == AdventureTurnPhase.DICE ||
        flying
    if (!visible || !mapCenter.isSpecified) return
    val progress = fly.value
    val start = mapCenter - origin
    val end = if (flashAnchor.isSpecified) flashAnchor - origin else start
    val center = Offset(
        start.x + (end.x - start.x) * progress,
        start.y + (end.y - start.y) * progress
    )
    val scale = 1f - 0.55f * progress
    val cardAlpha = 1f - 0.78f * progress
    val cardOffset = IntOffset(
        (center.x - size.width / 2f).roundToInt(),
        (center.y - size.height / 2f).roundToInt()
    )
    Box(
        modifier = Modifier
            .offset { cardOffset }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = cardAlpha
            }
            .onSizeChanged { size = it }
            .clickable(
                enabled = state.phase == AdventureTurnPhase.ROLLING,
                onClick = onRoll
            )
    ) {
        StorybookCard(modifier = Modifier.widthIn(min = 280.dp, max = 330.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AdventureAvatar(player.emoji, player.colorIndex, size = 44.dp, selected = true)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.adventure_turn, player.name),
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state.goAgain) {
                        Spacer(Modifier.height(3.dp))
                        AdventurePill(
                            text = stringResource(R.string.adventure_go_again),
                            color = StorybookGold
                        )
                    }
                }
                Dice3D(
                    value = state.diceValue,
                    progress = diceProgress.value,
                    modifier = Modifier.size(80.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    state.phase == AdventureTurnPhase.ROLLING ->
                        stringResource(R.string.adventure_tap_to_throw)
                    else -> state.diceValue?.let { stringResource(R.string.adventure_move, it) }.orEmpty()
                },
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
