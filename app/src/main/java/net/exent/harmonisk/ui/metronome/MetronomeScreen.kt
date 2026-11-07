package net.exent.harmonisk.ui.metronome

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.exent.harmonisk.R
import net.exent.harmonisk.ui.components.BrandTopBar
import net.exent.harmonisk.ui.components.KeepScreenOn
import net.exent.harmonisk.ui.components.brandBackground
import net.exent.harmonisk.ui.theme.AmberAccent
import net.exent.harmonisk.ui.theme.MetronomeBlue
import net.exent.harmonisk.ui.theme.PlayGreen
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetronomeScreen(
    onBack: () -> Unit,
    viewModel: MetronomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val pulse = remember { Animatable(0f) }

    KeepScreenOn()

    LaunchedEffect(state.pulse) {
        if (state.pulse == 0L || state.currentBeat in state.mutedBeats) return@LaunchedEffect
        pulse.snapTo(1f)
        pulse.animateTo(0f, tween(durationMillis = 220, easing = LinearEasing))
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stop() }
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .brandBackground(),
        containerColor = Color.Transparent,
        topBar = {
            BrandTopBar(
                title = stringResource(R.string.metronome_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { viewModel.toggleSound() }) {
                        Icon(
                            if (state.soundEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = stringResource(
                                if (state.soundEnabled) R.string.metronome_sound_on else R.string.metronome_sound_off
                            ),
                            tint = if (state.soundEnabled) MetronomeBlue else Color.White.copy(alpha = 0.55f)
                        )
                    }
                }
            )
        }
    ) { padding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp)

        if (isLandscape) {
            Row(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BeatDisplay(
                    state = state,
                    pulse = pulse,
                    compact = true,
                    onAccentChange = viewModel::setAccentBeat,
                    onMuteToggle = viewModel::toggleMutedBeat,
                    modifier = Modifier.weight(1f)
                )
                ControlPanel(state, viewModel, Modifier.weight(1f))
            }
        } else {
            Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
                BeatDisplay(
                    state = state,
                    pulse = pulse,
                    compact = false,
                    onAccentChange = viewModel::setAccentBeat,
                    onMuteToggle = viewModel::toggleMutedBeat,
                    modifier = Modifier.weight(1f)
                )
                ControlPanel(state, viewModel)
            }
        }
    }
}

@Composable
private fun ControlPanel(
    state: MetronomeState,
    viewModel: MetronomeViewModel,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            TempoControls(state, viewModel)
            Spacer(Modifier.height(16.dp))
            RunButton(state, viewModel)
        }
    }
}

@Composable
private fun BeatDisplay(
    state: MetronomeState,
    pulse: Animatable<Float, *>,
    compact: Boolean,
    onAccentChange: (Int) -> Unit,
    onMuteToggle: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val accented = state.isRunning && state.currentBeat == state.accentBeat
    val muted = state.isRunning && state.currentBeat in state.mutedBeats
    val color = if (accented) AmberAccent else MetronomeBlue
    val alpha = if (muted) 0.45f else 1f

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(if (compact) 132.dp else 200.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f
                val strength = pulse.value
                drawCircle(color = color.copy(alpha = (0.05f + 0.10f * strength) * alpha), radius = radius)
                drawCircle(
                    color = color.copy(alpha = 0.30f * strength * alpha),
                    radius = radius * (0.78f + 0.22f * strength),
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(color = color.copy(alpha = (0.10f + 0.30f * strength) * alpha), radius = radius * 0.66f)
            }
            Text(
                text = "${state.currentBeat + 1}",
                style = if (compact) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = color.copy(alpha = alpha)
            )
        }

        Spacer(Modifier.height(if (compact) 12.dp else 24.dp))

        BeatDots(state, compact = compact, onAccentChange = onAccentChange, onMuteToggle = onMuteToggle)

        Spacer(Modifier.height(6.dp))

        Text(
            text = stringResource(R.string.metronome_accent_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun BeatDots(
    state: MetronomeState,
    compact: Boolean,
    onAccentChange: (Int) -> Unit,
    onMuteToggle: (Int) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val beats = state.beatsPerBar
        val spacing = if (compact) 4.dp else 6.dp
        val dotSize = ((maxWidth - spacing * (beats - 1)) / beats)
            .coerceIn(18.dp, if (compact) 26.dp else 32.dp)

        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(beats) { beat ->
                BeatDot(
                    beat = beat,
                    state = state,
                    dotSize = dotSize,
                    onClick = { onAccentChange(beat) },
                    onLongClick = { onMuteToggle(beat) }
                )
            }
        }
    }
}

@Composable
private fun BeatDot(
    beat: Int,
    state: MetronomeState,
    dotSize: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val isMuted = beat in state.mutedBeats
    val isAccent = beat == state.accentBeat && !isMuted
    val isCurrent = state.isRunning && beat == state.currentBeat && !isMuted
    val container = when {
        isCurrent && isAccent -> AmberAccent
        isCurrent -> MetronomeBlue
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        isMuted -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
        isCurrent -> Color.Black
        isAccent -> AmberAccent
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val slash = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    val silenced = stringResource(R.string.metronome_beat_silenced)

    Surface(
        modifier = Modifier.size(dotSize),
        shape = CircleShape,
        color = container,
        border = if (isAccent) BorderStroke(2.dp, AmberAccent) else null
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics { if (isMuted) stateDescription = silenced }
                .combinedClickable(
                    onClickLabel = stringResource(R.string.metronome_accent_beat),
                    onLongClickLabel = stringResource(R.string.metronome_silence_beat),
                    onClick = onClick,
                    onLongClick = onLongClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "${beat + 1}", fontSize = 11.sp, color = content, fontWeight = FontWeight.Medium)
            if (isMuted) {
                Canvas(Modifier.fillMaxSize()) {
                    val inset = size.minDimension * 0.24f
                    drawLine(
                        color = slash,
                        start = Offset(inset, size.height - inset),
                        end = Offset(size.width - inset, inset),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}

@Composable
private fun TempoControls(
    state: MetronomeState,
    viewModel: MetronomeViewModel,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = { viewModel.adjustBpm(-1) }) {
                Icon(Icons.Filled.Remove, stringResource(R.string.metronome_slower))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${state.bpm}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.bpm),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { viewModel.adjustBpm(1) }) {
                Icon(Icons.Filled.Add, stringResource(R.string.metronome_faster))
            }
        }

        Slider(
            value = state.bpm.toFloat(),
            onValueChange = { viewModel.setBpm(it.roundToInt()) },
            onValueChangeFinished = { viewModel.saveBpm() },
            valueRange = METRONOME_MIN_BPM.toFloat()..METRONOME_MAX_BPM.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.metronome_beats_per_bar), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            ChoiceMenu(
                value = state.beatsPerBar,
                options = (1..METRONOME_MAX_BEATS).toList(),
                onSelect = viewModel::setBeatsPerBar
            )
        }

    }
}

@Composable
private fun ChoiceMenu(value: Int, options: List<Int>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("$value", style = MaterialTheme.typography.titleLarge)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "$option",
                            fontWeight = if (option == value) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun RunButton(state: MetronomeState, viewModel: MetronomeViewModel) {
    Button(
        onClick = { viewModel.toggleRunning() },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (state.isRunning) MaterialTheme.colorScheme.surfaceVariant else PlayGreen,
            contentColor = if (state.isRunning) MaterialTheme.colorScheme.onSurfaceVariant else Color.Black
        )
    ) {
        Icon(if (state.isRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(if (state.isRunning) R.string.stop else R.string.start),
            style = MaterialTheme.typography.titleMedium
        )
    }
}
