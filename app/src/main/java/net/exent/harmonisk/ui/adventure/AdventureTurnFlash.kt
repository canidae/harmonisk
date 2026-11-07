package net.exent.harmonisk.ui.adventure

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
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

enum class TurnIntroStage { INTRO, HANDOFF, PANEL }

@Composable
fun TurnFlashOverlay(
    state: AdventureState,
    mapCenter: Offset,
    panelDiceAnchor: Offset,
    origin: Offset,
    stage: TurnIntroStage
) {
    val player = state.currentPlayer ?: return
    var size by remember { mutableStateOf(IntSize.Zero) }
    var dieAnchor by remember { mutableStateOf(Offset.Unspecified) }
    val introHandoff = remember { Animatable(0f) }
    LaunchedEffect(stage) {
        if (stage == TurnIntroStage.HANDOFF) {
            introHandoff.snapTo(0f)
            introHandoff.animateTo(
                1f,
                tween(AdventureTiming.TURN_HANDOFF_MS.toInt(), easing = FastOutSlowInEasing)
            )
        } else {
            introHandoff.snapTo(0f)
        }
    }
    if (state.phase == AdventureTurnPhase.ROLLING &&
        stage != TurnIntroStage.PANEL &&
        mapCenter.isSpecified
    ) {
        val center = mapCenter - origin
        val t = introHandoff.value
        val cardAlpha = if (stage == TurnIntroStage.HANDOFF) (1f - 1.6f * t).coerceIn(0f, 1f) else 1f
        val cardScale = 1f - 0.18f * t
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
        ) {
            StorybookCard(modifier = Modifier.widthIn(min = 280.dp, max = 330.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AdventureAvatar(player.emoji, player.colorIndex, size = 44.dp, selected = true)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.adventure_turn, player.name),
                            style = MaterialTheme.typography.titleMedium,
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
                    if (stage == TurnIntroStage.INTRO) {
                        Dice3D(
                            value = null,
                            progress = 0f,
                            modifier = Modifier
                                .size(80.dp)
                                .onGloballyPositioned { dieAnchor = it.boundsInRoot().center }
                        )
                    } else {
                        Spacer(Modifier.size(80.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.adventure_throw_dice),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (stage == TurnIntroStage.HANDOFF && dieAnchor.isSpecified && panelDiceAnchor.isSpecified) {
            val start = dieAnchor - origin
            val end = panelDiceAnchor - origin
            val position = Offset(
                start.x + (end.x - start.x) * t,
                start.y + (end.y - start.y) * t
            )
            val dieScale = 1f - 0.3f * t
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
                    }
            ) {
                Dice3D(value = null, progress = 0f, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
