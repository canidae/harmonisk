package net.exent.harmonisk.ui.adventure

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.exent.harmonisk.R
import net.exent.harmonisk.ui.theme.AdventureInk
import net.exent.harmonisk.ui.celebration.CelebrationLevel
import net.exent.harmonisk.ui.celebration.ScoreCelebrationOverlay
import net.exent.harmonisk.ui.components.KeepScreenOn
import net.exent.harmonisk.ui.utils.formatScore
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private data class TallyItem(
    val playerId: String,
    val emoji: String,
    val count: Int,
    val points: Int,
    val bonus: Boolean
)

private fun tallyItems(players: List<AdventurePlayer>): List<TallyItem> =
    players.flatMap { player ->
        val stickers = player.stickers
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedBy { it.key.ordinal }
            .map { (sticker, count) ->
                TallyItem(player.profileId, sticker.emoji, count, sticker.value * count, false)
            }
        stickers + if (player.bonusScore > 0f) {
            listOf(TallyItem(player.profileId, "✨", 1, player.bonusScore.roundToInt(), true))
        } else {
            emptyList()
        }
    }

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
    val items = remember(state.players) { tallyItems(state.players) }
    val songProgress = remember { Animatable(0f) }
    val pop = remember { Animatable(0f) }
    val fly = remember { Animatable(0f) }
    val gold = remember { mutableStateMapOf<String, Float>() }
    val landed = remember { mutableStateMapOf<String, List<TallyItem>>() }
    val anchors = remember { mutableStateMapOf<String, Offset>() }
    var current by remember { mutableStateOf<TallyItem?>(null) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    var showWinner by remember { mutableStateOf(false) }
    val fanfare = remember { AdventureFanfarePlayer() }
    DisposableEffect(Unit) {
        onDispose { fanfare.release() }
    }
    LaunchedEffect(items) {
        delay(450)
        fanfare.play()
        songProgress.animateTo(1f, tween(2200, easing = FastOutSlowInEasing))
        delay(300)
        items.forEach { item ->
            current = item
            pop.snapTo(0f)
            fly.snapTo(0f)
            pop.animateTo(1f, tween(160))
            delay(220)
            fly.animateTo(1f, tween(330, easing = FastOutSlowInEasing))
            gold[item.playerId] = (gold[item.playerId] ?: 0f) + item.points
            landed[item.playerId] = landed[item.playerId].orEmpty() + item
            current = null
            delay(60)
        }
        delay(150)
        showWinner = true
    }
    AdventureBackdrop(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().onSizeChanged { overlaySize = it })
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
                songProgress = songProgress.value,
                gold = gold,
                landed = landed,
                maxTotal = maxTotal,
                showWinner = showWinner,
                solo = state.solo,
                onBarAnchor = { id, anchor -> anchors[id] = anchor },
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
        val item = current
        if (item != null && overlaySize != IntSize.Zero) {
            TallyFlight(
                item = item,
                pop = pop.value,
                fly = fly.value,
                start = Offset(overlaySize.width / 2f, overlaySize.height * 0.44f),
                target = anchors[item.playerId] ?: Offset.Unspecified
            )
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
private fun TallyFlight(
    item: TallyItem,
    pop: Float,
    fly: Float,
    start: Offset,
    target: Offset
) {
    val density = LocalDensity.current
    val width = 150.dp
    val height = 126.dp
    val halfWidth = with(density) { width.toPx() / 2f }
    val halfHeight = with(density) { height.toPx() / 2f }
    val landed = target.isSpecified
    val position = if (landed) {
        Offset(
            start.x + (target.x - start.x) * fly,
            start.y + (target.y - start.y) * fly - sin(fly * PI).toFloat() * 90 * density.density
        )
    } else {
        start
    }
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (position.x - halfWidth).roundToInt(),
                    (position.y - halfHeight).roundToInt()
                )
            }
            .size(width, height)
            .graphicsLayer {
                val scale = (0.55f + 0.45f * pop) * (1f - 0.5f * fly)
                scaleX = scale
                scaleY = scale
                alpha = (pop * (1f - fly * fly)).coerceIn(0f, 1f)
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        StorybookGold.copy(alpha = 0.22f),
                        Color.Transparent
                    )
                ),
                radius = size.minDimension / 2f
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.emoji, fontSize = 52.sp)
                if (item.count > 1) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "×${item.count}",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = StorybookGold
                    )
                }
            }
            Text(
                text = "+${item.points}",
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                color = StorybookGold
            )
            if (item.bonus) {
                Text(
                    text = stringResource(R.string.adventure_bonus),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun ScoreChart(
    standings: List<AdventureStanding>,
    songProgress: Float,
    gold: Map<String, Float>,
    landed: Map<String, List<TallyItem>>,
    maxTotal: Float,
    showWinner: Boolean,
    solo: Boolean,
    onBarAnchor: (String, Offset) -> Unit,
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
                    key(player.profileId) {
                        val goldValue by animateFloatAsState(
                            targetValue = gold[player.profileId] ?: 0f,
                            animationSpec = tween(320),
                            label = "gold_bar"
                        )
                        val goldH = (chartHeight - 42.dp) * (goldValue / maxTotal)
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
                                    goldValue + player.songScore * songProgress
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
                                    .background(
                                        Brush.verticalGradient(listOf(color, color.copy(alpha = 0.5f)))
                                    )
                            )
                            Box(
                                modifier = Modifier
                                    .width(barWidth)
                                    .height(goldH)
                                    .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                                    .background(StorybookGold.copy(alpha = 0.85f))
                                    .onGloballyPositioned { coordinates ->
                                        val bounds = coordinates.boundsInRoot()
                                        onBarAnchor(
                                            player.profileId,
                                            Offset(bounds.center.x, bounds.top)
                                        )
                                    },
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 3.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Bottom
                                ) {
                                    landed[player.profileId]
                                        .orEmpty()
                                        .filter { !it.bonus }
                                        .takeLast(3)
                                        .forEach { group ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(group.emoji, fontSize = 17.sp)
                                                if (group.count > 1) {
                                                    Text(
                                                        text = "×${group.count}",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = AdventureInk
                                                    )
                                                }
                                            }
                                        }
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
