package net.exent.harmonisk.ui.whack

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.exent.harmonisk.R
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.ui.components.BrandTopBar
import net.exent.harmonisk.ui.components.KeepScreenOn
import net.exent.harmonisk.ui.components.RequireMicPermission
import net.exent.harmonisk.ui.theme.FingerColors

private val BOARD_PADDING = 16.dp
private val BADGE_SIZE = 34.dp
private val BADGE_GAP = 6.dp
private val ROW_GAP = 8.dp
private val BAND_HEIGHT = 72.dp
private val HOLE_TOP = Color(0xFF120C05)
private val HOLE_RIM = Color(0xFFFFE0A3)

@Composable
fun WhackScreen(
    onBack: () -> Unit,
    viewModel: WhackViewModel = hiltViewModel(),
    adventure: Boolean = false,
    durationMs: Long = WhackRules.GAME_MS,
    subtitle: String? = null,
    onFinished: (Int) -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sounds = remember(context) { WhackSounds(context) }
    DisposableEffect(Unit) {
        onDispose { sounds.release() }
    }
    LaunchedEffect(state.scored?.id) {
        state.scored?.let { sounds.play(it.emoji, 0.85f) }
    }
    LaunchedEffect(state.hammer?.id) {
        state.hammer?.let { sounds.play(WhackSfx.Thud, if (it.hit) 0.5f else 0.7f) }
    }
    LaunchedEffect(state.levelUp?.id) {
        state.levelUp?.let { sounds.play(WhackSfx.Level, 0.7f) }
    }
    val startGame = { viewModel.start(durationMs, persistBest = !adventure) }
    val leave = {
        when {
            state.playing -> viewModel.stop()
            adventure && state.finished -> onFinished(state.hits)
            else -> onBack()
        }
    }
    BackHandler(enabled = state.playing || adventure) { leave() }
    KeepScreenOn()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            BrandTopBar(
                title = stringResource(R.string.whack_title),
                subtitle = subtitle,
                onBack = leave
            )
        }
    ) { padding ->
        RequireMicPermission {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .navigationBarsPadding()
            ) {
                WhackHeader(state, adventure)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    WhackBands(state, Modifier.align(Alignment.TopCenter).fillMaxWidth())
                    if (!state.playing) {
                        WhackOverlay(
                            state = state,
                            adventure = adventure,
                            onStart = startGame,
                            onFinished = onFinished
                        )
                    }
                }
                WhackLetters(state.instrument)
            }
        }
    }
}

@Composable
private fun WhackHeader(state: WhackState, adventure: Boolean) {
    val seconds = (state.remainingMs + 999) / 1000
    val urgent = state.playing && seconds <= 20
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BOARD_PADDING, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${state.hits}",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                contentAlignment = Alignment.Center
            ) {
                state.levelUp?.let { LevelBadge(it) }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatTime(seconds),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                if (!adventure) {
                    Text(
                        text = stringResource(R.string.whack_best, state.best),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (state.remainingMs.toFloat() / state.durationMs).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
            color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            drawStopIndicator = {}
        )
    }
}

private fun formatTime(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

@Composable
private fun WhackBands(state: WhackState, modifier: Modifier) {
    val holes = remember(state.instrument) { whackHoles(state.instrument) }
    Column(
        modifier = modifier.padding(horizontal = BOARD_PADDING, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        for (finger in 0 until state.level) {
            key(finger) {
                val revealed = remember { MutableTransitionState(false) }.apply { targetState = true }
                AnimatedVisibility(
                    visibleState = revealed,
                    enter = fadeIn(tween(200)) + expandVertically(tween(200))
                ) {
                    WhackBand(state, finger, holes.filter { it.finger == finger })
                }
            }
        }
    }
}

@Composable
private fun WhackBand(state: WhackState, finger: Int, holes: List<WhackHole>) {
    val color = FingerColors[finger]
    val shape = RoundedCornerShape(BAND_HEIGHT * 0.4f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAND_HEIGHT)
            .background(
                Brush.verticalGradient(listOf(color.copy(alpha = 0.34f), color.copy(alpha = 0.16f))),
                shape
            )
            .border(1.dp, color.copy(alpha = 0.5f), shape)
            .padding(horizontal = BADGE_GAP),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FingerBadge(finger, color)
        Spacer(Modifier.width(BADGE_GAP))
        holes.forEach { hole ->
            HoleSlot(state, hole, Modifier.weight(1f).fillMaxHeight())
        }
        Spacer(Modifier.width(BADGE_GAP))
        FingerBadge(finger, color)
    }
}

@Composable
private fun FingerBadge(finger: Int, color: Color) {
    Box(
        modifier = Modifier
            .size(BADGE_SIZE)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$finger",
            fontSize = (BADGE_SIZE.value * 0.55f).sp,
            fontWeight = FontWeight.Black,
            color = if (finger == 2 || finger == 3) Color.Black else Color.White
        )
    }
}

@Composable
private fun HoleSlot(state: WhackState, hole: WhackHole, modifier: Modifier) {
    val target = state.target?.takeIf { it.hole == hole }
    var emoji by remember { mutableStateOf<WhackEmoji?>(null) }
    LaunchedEffect(target?.id) {
        if (target != null) emoji = target.emoji
    }
    val rise = remember { Animatable(0f) }
    LaunchedEffect(target?.id) {
        if (target != null) {
            rise.snapTo(0f)
            delay(WhackRules.RISE_DELAY_MS)
            rise.animateTo(
                1f,
                spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow)
            )
        } else {
            rise.animateTo(0f, tween(150))
        }
    }
    val holeHeight = BAND_HEIGHT * 0.34f
    val holeInset = BAND_HEIGHT * 0.06f
    val holeCentre = holeInset + holeHeight / 2
    val clipHeight = BAND_HEIGHT - holeCentre
    val emojiSize = BAND_HEIGHT * 0.44f
    val sunk = holeHeight * 0.25f

    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        Canvas(
            Modifier
                .fillMaxWidth(0.82f)
                .height(holeHeight)
                .align(Alignment.BottomCenter)
                .padding(bottom = holeInset)
        ) {
            drawOval(color = HOLE_TOP, size = Size(size.width, size.height))
            drawOval(
                color = HOLE_RIM.copy(alpha = 0.5f),
                size = Size(size.width, size.height),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
        emoji?.let { current ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(clipHeight)
                    .align(Alignment.TopCenter)
                    .clipToBounds(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Text(
                    text = current.emoji,
                    fontSize = with(LocalDensity.current) { emojiSize.toSp() },
                    modifier = Modifier.graphicsLayer {
                        translationY = (1f - rise.value) * size.height + rise.value * sunk.toPx()
                    }
                )
            }
        }
        state.hammer?.takeIf { it.hole == hole }?.let { hammer ->
            HydraulicPress(
                id = hammer.id,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = holeInset + holeHeight / 2)
            )
        }
    }
}

@Composable
private fun HydraulicPress(id: Long, modifier: Modifier) {
    val drop = remember { Animatable(0f) }
    LaunchedEffect(id) {
        drop.snapTo(0f)
        drop.animateTo(1f, tween(210, easing = FastOutLinearInEasing))
        delay(70)
        drop.animateTo(0f, tween(300))
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth(0.78f)
            .height(BAND_HEIGHT * 0.62f)
            .graphicsLayer {
                translationY = -(1f - drop.value) * size.height * 0.5f
            }
    ) {
        drawWhackPress()
    }
}

@Composable
private fun WhackLetters(instrument: Instrument) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BOARD_PADDING)
            .padding(bottom = 14.dp, top = 4.dp)
    ) {
        Spacer(Modifier.width(BADGE_SIZE + BADGE_GAP * 2))
        instrument.stringLabels.forEach { letter ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = letter,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(Modifier.width(BADGE_SIZE + BADGE_GAP * 2))
    }
}

@Composable
private fun WhackOverlay(
    state: WhackState,
    adventure: Boolean,
    onStart: () -> Unit,
    onFinished: (Int) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (state.finished) {
                    Text(text = "⏰", fontSize = 44.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.whack_time_up),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "${state.hits}",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = stringResource(R.string.whack_hits),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!adventure) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.whack_best, state.best),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    WhackPressIcon(Modifier.size(width = 76.dp, height = 56.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.whack_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(
                            if (adventure) R.string.whack_duration_minute else R.string.whack_duration
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(instrumentName(state.instrument)),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (!adventure) {
                        Text(
                            text = stringResource(R.string.whack_best, state.best),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = {
                        if (state.finished && adventure) onFinished(state.hits) else onStart()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Text(
                        text = stringResource(
                            when {
                                state.finished && adventure -> R.string.whack_continue
                                state.finished -> R.string.whack_again
                                else -> R.string.whack_start
                            }
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelBadge(levelUp: WhackLevelUp) {
    val scale = remember { Animatable(0.6f) }
    LaunchedEffect(levelUp.id) {
        scale.animateTo(
            1f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
        )
    }
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(RoundedCornerShape(50))
            .background(FingerColors[(levelUp.level - 1).coerceIn(0, 4)])
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            text = stringResource(R.string.whack_level_up, levelUp.level),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            color = if (levelUp.level >= 5) Color.White else Color.Black
        )
    }
}

@StringRes
private fun instrumentName(instrument: Instrument): Int = when (instrument) {
    Instrument.VIOLIN -> R.string.instrument_violin
    Instrument.VIOLA -> R.string.instrument_viola
    Instrument.CELLO -> R.string.instrument_cello
}
