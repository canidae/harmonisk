package net.exent.harmonisk.ui.adventure

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.exent.harmonisk.R
import net.exent.harmonisk.ui.theme.AdventureInk
import net.exent.harmonisk.ui.celebration.CelebrationLevel
import net.exent.harmonisk.ui.celebration.ScoreCelebrationOverlay
import net.exent.harmonisk.ui.components.KeepScreenOn
import net.exent.harmonisk.ui.utils.formatScore
import kotlin.math.max

@Composable
fun AdventureResultsScreen(
    state: AdventureState,
    onNewAdventure: () -> Unit,
    onHome: () -> Unit
) {
    KeepScreenOn()
    val standings = remember(state.players) { standings(state.players) }
    val maxSongs = max(1, state.players.maxOfOrNull { it.songsPlayed } ?: 1)
    val maxTotal = max(100f * maxSongs, standings.maxOfOrNull { it.player.total } ?: 0f) * 1.08f
    val stickerProgress = remember { Animatable(0f) }
    val songProgress = remember { Animatable(0f) }
    val fanfare = remember { AdventureFanfarePlayer() }
    var showWinner by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { fanfare.release() }
    }
    LaunchedEffect(Unit) {
        delay(450)
        stickerProgress.animateTo(1f, tween(1200, easing = FastOutSlowInEasing))
        delay(250)
        fanfare.play()
        songProgress.animateTo(1f, tween(2500, easing = FastOutSlowInEasing))
        delay(250)
        showWinner = true
    }
    AdventureBackdrop(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (state.solo) R.string.adventure_results_solo_title
                            else R.string.adventure_results_title
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = StorybookGold
                    )
                }
                IconButton(onClick = onHome) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.adventure_results_home),
                        tint = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
            ScoreChart(
                standings = standings,
                stickerProgress = stickerProgress.value,
                songProgress = songProgress.value,
                maxTotal = maxTotal,
                showWinner = showWinner,
                solo = state.solo,
                modifier = Modifier.weight(1f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onHome,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.adventure_results_home))
                }
                Button(
                    onClick = onNewAdventure,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StorybookGold,
                        contentColor = AdventureInk
                    )
                ) {
                    Text(stringResource(R.string.adventure_results_new), fontWeight = FontWeight.Bold)
                }
            }
        }
        if (showWinner) {
            ScoreCelebrationOverlay(
                celebrationLevel = CelebrationLevel.INTENSE,
                score = 95f,
                level = 2,
                modifier = Modifier.fillMaxSize()
            ) {}
        }
    }
}

@Composable
private fun ScoreChart(
    standings: List<AdventureStanding>,
    stickerProgress: Float,
    songProgress: Float,
    maxTotal: Float,
    showWinner: Boolean,
    solo: Boolean,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val nameSpace = 54.dp
        val chartHeight = (maxHeight - nameSpace).coerceAtLeast(120.dp)
        Canvas(Modifier.fillMaxWidth().height(chartHeight)) {
            val step = size.height / 4f
            repeat(5) { index ->
                val y = step * index
                drawLine(
                    color = Color.White.copy(alpha = 0.08f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
            }
        }
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(chartHeight),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                standings.forEach { standing ->
                    val player = standing.player
                    val stickerH = (chartHeight - 42.dp) * ((player.stickerScore + player.bonusScore) * stickerProgress / maxTotal)
                    val songH = (chartHeight - 42.dp) * (player.songScore * songProgress / maxTotal)
                    val color = ADVENTURE_PLAYER_COLORS[player.colorIndex % ADVENTURE_PLAYER_COLORS.size]
                    val barWidth = if (solo) 96.dp else if (standings.size == 2) 64.dp else 48.dp
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        AnimatedVisibility(
                            visible = showWinner && !solo,
                            enter = fadeIn() + scaleIn(initialScale = 0.2f)
                        ) {
                            val label = medalLabel(standing.medal)
                            Text(
                                text = medalEmoji(standing.medal),
                                fontSize = 30.sp,
                                modifier = Modifier
                                    .padding(bottom = 2.dp)
                                    .semantics { contentDescription = label }
                            )
                        }
                        Text(
                            text = formatScore(
                                (player.stickerScore + player.bonusScore) * stickerProgress +
                                    player.songScore * songProgress
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Black,
                            color = color
                        )
                        Box(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .width(barWidth)
                                .height(songH)
                                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                                .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.5f))))
                        )
                        Box(
                            modifier = Modifier
                                .width(barWidth)
                                .height(stickerH)
                                .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                                .background(StorybookGold.copy(alpha = 0.85f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                modifier = Modifier.alpha(stickerProgress).padding(horizontal = 2.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                player.stickers.distinct().take(4).forEach { sticker ->
                                    Text(sticker.emoji, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                standings.forEach { standing ->
                    Text(
                        text = standing.player.name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private fun medalEmoji(medal: AdventureMedal): String = when (medal) {
    AdventureMedal.GOLD -> "🥇"
    AdventureMedal.SILVER -> "🥈"
    AdventureMedal.BRONZE -> "🥉"
    AdventureMedal.NONE -> ""
}

@Composable
private fun medalLabel(medal: AdventureMedal): String = when (medal) {
    AdventureMedal.GOLD -> stringResource(R.string.adventure_medal_gold)
    AdventureMedal.SILVER -> stringResource(R.string.adventure_medal_silver)
    AdventureMedal.BRONZE -> stringResource(R.string.adventure_medal_bronze)
    AdventureMedal.NONE -> ""
}
