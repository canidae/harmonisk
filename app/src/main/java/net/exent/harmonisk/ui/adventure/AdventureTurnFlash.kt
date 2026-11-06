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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
    diceTarget: Offset,
    origin: Offset,
    onRoll: () -> Unit
) {
    val player = state.currentPlayer ?: return
    var size by remember { mutableStateOf(IntSize.Zero) }
    var dieAnchor by remember { mutableStateOf(Offset.Unspecified) }
    val handoff = remember { Animatable(0f) }
    LaunchedEffect(state.phase) {
        if (state.phase == AdventureTurnPhase.DELIVERING) {
            handoff.snapTo(0f)
            handoff.animateTo(
                1f,
                tween(AdventureTiming.DICE_HANDOFF_MS.toInt(), easing = FastOutSlowInEasing)
            )
        } else {
            handoff.snapTo(0f)
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
        state.phase == AdventureTurnPhase.DELIVERING
    if (!visible || !mapCenter.isSpecified) return
    val center = mapCenter - origin
    val cardAlpha = (1f - 1.6f * handoff.value).coerceIn(0f, 1f)
    val cardScale = 1f - 0.18f * handoff.value
    val cardOffset = IntOffset(
        (center.x - size.width / 2f).roundToInt(),
        (center.y - size.height / 2f).roundToInt()
    )
    Box(
        modifier = Modifier
            .offset { cardOffset }
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
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
                if (state.phase == AdventureTurnPhase.DELIVERING) {
                    Spacer(Modifier.size(80.dp))
                } else {
                    Dice3D(
                        value = state.diceValue,
                        progress = diceProgress.value,
                        modifier = Modifier
                            .size(80.dp)
                            .onGloballyPositioned { dieAnchor = it.boundsInRoot().center }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (state.phase) {
                    AdventureTurnPhase.ROLLING ->
                        stringResource(R.string.adventure_tap_to_throw)
                    AdventureTurnPhase.DELIVERING -> ""
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

    if (state.phase == AdventureTurnPhase.DELIVERING &&
        dieAnchor.isSpecified && diceTarget.isSpecified
    ) {
        val start = dieAnchor - origin
        val end = diceTarget - origin
        val t = handoff.value
        val position = Offset(
            start.x + (end.x - start.x) * t,
            start.y + (end.y - start.y) * t
        )
        val dieScale = 1f - 0.45f * t
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (position.x - 40.dp.toPx()).roundToInt(),
                        (position.y - 40.dp.toPx()).roundToInt()
                    )
                }
                .size(80.dp)
                .graphicsLayer {
                    scaleX = dieScale
                    scaleY = dieScale
                    alpha = 1f - 0.15f * t
                }
        ) {
            Dice3D(value = state.diceValue, progress = 1f, modifier = Modifier.fillMaxSize())
        }
    }
}
