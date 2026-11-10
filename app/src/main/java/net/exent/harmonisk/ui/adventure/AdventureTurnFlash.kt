package net.exent.harmonisk.ui.adventure

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import net.exent.harmonisk.R
import net.exent.harmonisk.ui.theme.AdventureInk
import kotlin.math.roundToInt

enum class TurnIntroStage { INTRO, PANEL }

@Composable
fun TurnFlashOverlay(
    state: AdventureState,
    mapCenter: Offset,
    origin: Offset,
    stage: TurnIntroStage
) {
    val player = state.currentPlayer ?: return
    var size by remember { mutableStateOf(IntSize.Zero) }
    AnimatedVisibility(
        visible = state.phase == AdventureTurnPhase.ROLLING &&
            stage == TurnIntroStage.INTRO &&
            mapCenter.isSpecified,
        enter = fadeIn(tween(200)) + scaleIn(tween(220), initialScale = 0.82f),
        exit = fadeOut(tween(240)) + scaleOut(tween(240), targetScale = 1.06f)
    ) {
        val center = mapCenter - origin
        val outlineWidth = with(LocalDensity.current) { 2.dp.toPx() }
        Column(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (center.x - size.width / 2f).roundToInt(),
                        (center.y - size.height / 2f).roundToInt()
                    )
                }
                .onSizeChanged { size = it },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val text = stringResource(R.string.adventure_turn, player.name)
            val style = MaterialTheme.typography.displaySmall.copy(
                shadow = Shadow(StorybookGold.copy(alpha = 0.55f), blurRadius = 26f)
            )
            Box(contentAlignment = Alignment.Center) {
                TurnText(
                    text = text,
                    style = style.copy(drawStyle = Stroke(width = outlineWidth)),
                    color = AdventureInk
                )
                TurnText(text = text, style = style, color = StorybookGold)
            }
        }
    }
}

@Composable
private fun TurnText(text: String, style: TextStyle, color: Color) {
    Text(
        text = text,
        style = style,
        fontWeight = FontWeight.Black,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 2
    )
}
