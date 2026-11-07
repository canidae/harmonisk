package net.exent.harmonisk.ui.adventure

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import net.exent.harmonisk.ui.components.KeepScreenOn
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

@Composable
fun AdventureGameScreen(
    state: AdventureState,
    onPull: () -> Unit,
    onRoll: () -> Unit,
    onBack: () -> Unit
) {
    KeepScreenOn()
    var mapCenter by remember { mutableStateOf(Offset.Unspecified) }
    var songAnchor by remember { mutableStateOf(Offset.Unspecified) }
    var tokenAnchor by remember { mutableStateOf(Offset.Unspecified) }
    var avatarAnchor by remember { mutableStateOf(Offset.Unspecified) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val latestToken = remember { TokenPosition() }
    val reportTokenPosition = state.phase == AdventureTurnPhase.ROLLING ||
        state.phase == AdventureTurnPhase.DICE ||
        state.phase == AdventureTurnPhase.DELIVERING
    LaunchedEffect(state.phase) {
        if (reportTokenPosition && latestToken.position.isSpecified) {
            tokenAnchor = latestToken.position
        }
    }
    val context = LocalContext.current
    val sounds = remember(context) { AdventureSounds(context) }
    DisposableEffect(Unit) {
        onDispose { sounds.release() }
    }
    LaunchedEffect(state.phase) {
        when (state.phase) {
            AdventureTurnPhase.MOVING -> sounds.play(AdventureSound.Step, 0.55f)
            AdventureTurnPhase.SPINNING -> sounds.play(AdventureSound.Lever, 0.7f)
            AdventureTurnPhase.DICE -> sounds.play(AdventureSound.Tick, 0.4f)
            else -> Unit
        }
    }
    LaunchedEffect(state.reward) {
        when (state.reward) {
            is AdventureReward.Stickers -> sounds.play(AdventureSound.Collect, 0.75f)
            AdventureReward.GoAgain -> sounds.play(AdventureSound.Bonus, 0.55f)
            AdventureReward.Song, AdventureReward.Whack, null -> Unit
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
                    fieldRewards = state.fieldRewards,
                    moveFrom = state.moveFrom,
                    moving = state.phase == AdventureTurnPhase.MOVING,
                    onCurrentTokenPosition = { position ->
                        latestToken.position = position
                        if (reportTokenPosition) tokenAnchor = position
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            CurrentTurnCard(
                state = state,
                onSongAnchor = { songAnchor = it },
                onAvatarAnchor = { avatarAnchor = it }
            )
        }

        TurnFlashOverlay(
            state = state,
            mapCenter = mapCenter,
            diceTarget = tokenAnchor,
            origin = origin,
            onRoll = onRoll
        )
        SongRevealOverlay(
            state = state,
            mapCenter = mapCenter,
            songAnchor = songAnchor,
            origin = origin,
            pullEnabled = !state.loading,
            onPull = onPull,
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
    onSongAnchor: (Offset) -> Unit,
    onAvatarAnchor: (Offset) -> Unit
) {
    val player = state.currentPlayer ?: return
    val color = ADVENTURE_PLAYER_COLORS[player.colorIndex % ADVENTURE_PLAYER_COLORS.size]
    val levelVisible = state.currentSong != null && !state.loading
    val levelAlpha by animateFloatAsState(if (levelVisible) 1f else 0f, label = "level_alpha")
    val status = when (state.phase) {
        AdventureTurnPhase.ROLLING -> stringResource(R.string.adventure_throw_dice)
        AdventureTurnPhase.DICE, AdventureTurnPhase.DELIVERING, AdventureTurnPhase.MOVING -> ""
        AdventureTurnPhase.EVENT -> when {
            state.finisherId != null -> stringResource(R.string.adventure_castle_reached)
            state.turnScore != null -> stringResource(R.string.adventure_score)
            state.reward is AdventureReward.Stickers -> stringResource(R.string.adventure_reward_stickers)
            state.reward is AdventureReward.GoAgain -> stringResource(R.string.adventure_reward_go_again_short)
            else -> stringResource(R.string.adventure_song_complete)
        }
        AdventureTurnPhase.PULLING, AdventureTurnPhase.SPINNING, AdventureTurnPhase.HIGHLIGHT ->
            stringResource(R.string.adventure_choose_song)
        AdventureTurnPhase.REVEALED -> state.currentSong?.name.orEmpty()
    }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.adventure_turn, player.name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (player.stickers.isEmpty()) Modifier else Modifier.widthIn(max = 140.dp)
                    )
                    if (player.stickers.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        StickerStrip(
                            stickers = player.stickers,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (status.isNotEmpty()) {
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
                            text = status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = color,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                AdventurePill(
                    text = stringResource(R.string.adventure_level, state.currentLevel),
                    color = color,
                    modifier = Modifier.alpha(levelAlpha)
                )
            }
        }
    }
}

@Composable
private fun StickerStrip(stickers: List<AdventureSticker>, modifier: Modifier = Modifier) {
    val counts = remember(stickers) {
        stickers.groupingBy { it }.eachCount().entries.sortedBy { it.key.ordinal }
    }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        counts.forEach { (sticker, count) ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(sticker.emoji, fontSize = 15.sp)
                if (count > 1) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = "×$count",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                }
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
    val score = state.turnScore
    val levelUp = state.levelUp
    val emojis = when (reward) {
        is AdventureReward.Stickers -> reward.stickers.map { it.emoji }
        AdventureReward.Song, AdventureReward.GoAgain, AdventureReward.Whack, null -> emptyList()
    }
    var center by remember { mutableStateOf(Offset.Unspecified) }
    var flying by remember { mutableStateOf(false) }
    val fly = remember { Animatable(0f) }
    LaunchedEffect(reward, score, levelUp) {
        flying = false
        fly.snapTo(0f)
        if (reward == null && score == null && levelUp == null) return@LaunchedEffect
        delay(AdventureTiming.REWARD_MS)
        if (emojis.isEmpty() && score == null) return@LaunchedEffect
        flying = true
        fly.animateTo(1f, tween(AdventureTiming.REWARD_FLY_MS.toInt(), easing = FastOutSlowInEasing))
        if (emojis.isNotEmpty()) onStored()
    }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { center = it.boundsInRoot().center }
    ) {
        AnimatedVisibility(
            visible = (reward != null || score != null || levelUp != null) && !flying,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            RewardCard(reward = reward, score = score, levelUp = levelUp)
        }
        if (flying && center.isSpecified && avatarAnchor.isSpecified) {
            val from = center - origin
            val to = avatarAnchor - origin
            val progress = fly.value
            if (emojis.isNotEmpty()) {
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
            } else if (score != null) {
                val x = from.x + (to.x - from.x) * progress
                val y = from.y + (to.y - from.y) * progress - sin(progress * PI).toFloat() * 70f
                Text(
                    text = "+$score",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    color = StorybookGold,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x.roundToInt() - 30.dp.roundToPx(),
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
private fun RewardCard(reward: AdventureReward?, score: Int?, levelUp: Int?) {
    StorybookCard(
        modifier = Modifier
            .widthIn(max = 420.dp)
            .padding(horizontal = 16.dp)
    ) {
        if (score != null) {
            ClapBurst(Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.adventure_score),
                style = MaterialTheme.typography.labelMedium,
                color = StorybookGold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "$score",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                color = StorybookGold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (reward) {
                is AdventureReward.Stickers -> reward.stickers.forEach { sticker ->
                    Text(sticker.emoji, fontSize = 30.sp)
                }
                AdventureReward.GoAgain -> Text("🎲", fontSize = 30.sp)
                AdventureReward.Song, AdventureReward.Whack, null -> {}
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

private class TokenPosition {
    var position: Offset = Offset.Unspecified
}
