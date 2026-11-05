package net.exent.harmonisk.ui.adventure

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.exent.harmonisk.R
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import net.exent.harmonisk.ui.components.KeepScreenOn

@Composable
fun AdventureGameScreen(
    state: AdventureState,
    onPlay: () -> Unit,
    onBack: () -> Unit
) {
    KeepScreenOn()
    var mapCenter by remember { mutableStateOf(Offset.Unspecified) }
    var songAnchor by remember { mutableStateOf(Offset.Unspecified) }
    var avatarAnchor by remember { mutableStateOf(Offset.Unspecified) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val context = LocalContext.current
    val sounds = remember(context) { AdventureSounds(context) }
    DisposableEffect(Unit) {
        onDispose { sounds.release() }
    }
    LaunchedEffect(state.phase) {
        when (state.phase) {
            AdventureTurnPhase.MOVING -> sounds.play(AdventureSound.Step, 0.55f)
            AdventureTurnPhase.PULLING -> {
                delay(AdventureTiming.LEVER_HOLD_MS)
                sounds.play(AdventureSound.Lever, 0.7f)
            }
            else -> Unit
        }
    }
    LaunchedEffect(state.reward) {
        when (state.reward) {
            is AdventureReward.Stickers -> sounds.play(AdventureSound.Collect, 0.75f)
            is AdventureReward.Points -> sounds.play(AdventureSound.Collect, 0.65f)
            AdventureReward.GoAgain -> sounds.play(AdventureSound.Bonus, 0.55f)
            null -> Unit
        }
    }
    LaunchedEffect(Unit) {
        delay(600)
        val river = sounds.loop(AdventureSound.River, 0.12f)
        try {
            while (true) {
                delay(7000L + Random.nextLong(9000L))
                when (Random.nextInt(3)) {
                    0 -> sounds.play(AdventureSound.Wind, 0.16f)
                    1 -> sounds.play(AdventureSound.Birds, 0.18f)
                    else -> sounds.play(AdventureSound.Crickets, 0.12f)
                }
            }
        } finally {
            sounds.stop(river)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = Color.White
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.adventure_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = StorybookGold
                    )
                    Text(
                        text = if (state.solo) stringResource(R.string.adventure_solo_badge)
                        else stringResource(R.string.adventure_players_badge, state.players.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
                state.currentPlayer?.let { player ->
                    AdventurePill(
                        text = stringResource(R.string.adventure_field, player.position.coerceAtMost(ADVENTURE_FIELD_COUNT), ADVENTURE_FIELD_COUNT),
                        color = ADVENTURE_PLAYER_COLORS[player.colorIndex % ADVENTURE_PLAYER_COLORS.size]
                    )
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { mapCenter = it.boundsInRoot().center }
            ) {
                AdventureMap(
                    players = state.players,
                    currentPlayerId = state.currentPlayer?.profileId,
                    mapSeed = state.mapSeed,
                    moveFrom = state.moveFrom,
                    moving = state.phase == AdventureTurnPhase.MOVING,
                    modifier = Modifier.fillMaxSize()
                )
            }
            CurrentTurnCard(
                state = state,
                onPlay = onPlay,
                onSongAnchor = { songAnchor = it },
                onAvatarAnchor = { avatarAnchor = it }
            )
        }

        SongRevealOverlay(
            state = state,
            mapCenter = mapCenter,
            songAnchor = songAnchor,
            origin = origin,
            onTick = { volume -> sounds.play(AdventureSound.Tick, volume) }
        )
        RewardOverlay(
            state = state,
            avatarAnchor = avatarAnchor,
            origin = origin,
            onStored = { sounds.play(AdventureSound.Pouch, 0.65f) }
        )
    }
}

@Composable
private fun CurrentTurnCard(
    state: AdventureState,
    onPlay: () -> Unit,
    onSongAnchor: (Offset) -> Unit,
    onAvatarAnchor: (Offset) -> Unit
) {
    val player = state.currentPlayer ?: return
    val color = ADVENTURE_PLAYER_COLORS[player.colorIndex % ADVENTURE_PLAYER_COLORS.size]
    val revealed = state.phase == AdventureTurnPhase.REVEALED
    val levelAlpha by animateFloatAsState(if (revealed) 1f else 0f, label = "level_alpha")
    StorybookCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.onGloballyPositioned { coordinates ->
                    onAvatarAnchor(coordinates.boundsInRoot().center)
                }
            ) {
                AdventureAvatar(player.emoji, player.colorIndex, size = 48.dp, selected = true)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.adventure_turn, player.name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .onGloballyPositioned { coordinates ->
                            val bounds = coordinates.boundsInRoot()
                            onSongAnchor(Offset(bounds.left, bounds.center.y))
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = if (revealed) state.currentSong?.name.orEmpty() else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            AdventurePill(
                text = stringResource(R.string.adventure_level, state.currentLevel),
                color = color,
                modifier = Modifier.alpha(levelAlpha)
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onPlay,
            enabled = revealed && !state.loading && state.currentSong != null && state.reward == null && state.finisherId == null,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color(0xFF151225))
        ) {
            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF151225)
                )
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.adventure_play_song),
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

@Composable
private fun RewardOverlay(
    state: AdventureState,
    avatarAnchor: Offset,
    origin: Offset,
    onStored: () -> Unit
) {
    val reward = state.reward
    val levelUp = state.levelUp
    val emojis = when (reward) {
        is AdventureReward.Stickers -> reward.stickers.map { it.emoji }
        is AdventureReward.Points -> listOf("⭐")
        AdventureReward.GoAgain -> emptyList()
        null -> emptyList()
    }
    var center by remember { mutableStateOf(Offset.Unspecified) }
    var flying by remember { mutableStateOf(false) }
    val fly = remember { Animatable(0f) }
    LaunchedEffect(reward, levelUp) {
        flying = false
        fly.snapTo(0f)
        if (reward == null && levelUp == null) return@LaunchedEffect
        delay(AdventureTiming.REWARD_MS)
        if (emojis.isEmpty()) return@LaunchedEffect
        flying = true
        fly.animateTo(1f, tween(AdventureTiming.REWARD_FLY_MS.toInt(), easing = FastOutSlowInEasing))
        onStored()
    }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { center = it.boundsInRoot().center }
    ) {
        AnimatedVisibility(
            visible = (reward != null || levelUp != null) && !flying,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            RewardCard(reward = reward, levelUp = levelUp)
        }
        if (flying && emojis.isNotEmpty() && center.isSpecified && avatarAnchor.isSpecified) {
            val from = center - origin
            val to = avatarAnchor - origin
            val progress = fly.value
            emojis.forEachIndexed { index, emoji ->
                val spread = (index - (emojis.size - 1) / 2f) * 34f * (1f - progress)
                val x = from.x + (to.x - from.x) * progress + spread
                val y = from.y + (to.y - from.y) * progress - sin(progress * PI).toFloat() * 70f
                Text(
                    emoji,
                    fontSize = 30.sp,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x.roundToInt() - 15.dp.roundToPx(),
                                y.roundToInt() - 15.dp.roundToPx()
                            )
                        }
                        .graphicsLayer {
                            val scale = 1f - 0.5f * progress
                            scaleX = scale
                            scaleY = scale
                            alpha = 1f - progress * progress
                        }
                )
            }
        }
    }
}

@Composable
private fun RewardCard(reward: AdventureReward?, levelUp: Int?) {
    StorybookCard(
        modifier = Modifier
            .widthIn(max = 420.dp)
            .padding(horizontal = 16.dp)
    ) {
        ClapBurst(Modifier.align(Alignment.CenterHorizontally))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (reward) {
                is AdventureReward.Stickers -> reward.stickers.forEach { sticker ->
                    Text(sticker.emoji, fontSize = 30.sp)
                }
                is AdventureReward.Points -> Text("⭐", fontSize = 30.sp)
                AdventureReward.GoAgain -> Text("🎲", fontSize = 30.sp)
                null -> {}
            }
        }
        if (reward is AdventureReward.Stickers) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = pluralStringResource(
                    R.plurals.adventure_stickers_won,
                    reward.stickers.size,
                    reward.stickers.size
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = StorybookGold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (reward is AdventureReward.Points) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.adventure_reward_points, reward.points),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = StorybookGold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (reward is AdventureReward.GoAgain) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.adventure_reward_go_again_short),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = StorybookGold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (levelUp != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.level_reached, levelUp),
                style = MaterialTheme.typography.labelSmall,
                color = StorybookMint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
