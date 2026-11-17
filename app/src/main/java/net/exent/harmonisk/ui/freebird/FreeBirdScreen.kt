package net.exent.harmonisk.ui.freebird

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.exent.harmonisk.R
import net.exent.harmonisk.data.ProfileRepository
import net.exent.harmonisk.ui.components.KeepScreenOn
import net.exent.harmonisk.ui.components.RequireMicPermission
import net.exent.harmonisk.ui.profile.ProfileButton
import net.exent.harmonisk.ui.profile.ProfileDialogs
import net.exent.harmonisk.ui.profile.ProfileIconButton
import net.exent.harmonisk.ui.profile.rememberProfileDialogState

@Composable
fun FreeBirdScreen(
    onBack: () -> Unit,
    viewModel: FreeBirdViewModel = hiltViewModel()
) {
    val instrument by viewModel.instrument.collectAsStateWithLifecycle()
    val pitch by viewModel.pitch.collectAsStateWithLifecycle()
    val game by viewModel.game.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val profile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val profileDialogs = rememberProfileDialogState()
    val profileDialogOpen = profileDialogs.showSwitchConfirm || profileDialogs.showProfile || profileDialogs.showCreate
    LaunchedEffect(profileDialogOpen) {
        if (profileDialogOpen) viewModel.pauseGame() else viewModel.resumeGame()
    }
    BackHandler(onBack = onBack)
    KeepScreenOn()
    Box(Modifier.fillMaxSize()) {
        FreeBirdBackdrop(Modifier.fillMaxSize())
        FreeBirdStaff(instrument, game.level, Modifier.fillMaxSize())
        FreeBirdSeagull(Modifier.fillMaxSize())
        FreeBirdLetters(instrument, game.letters, game.level, pitch, Modifier.fillMaxSize())
        RequireMicPermission {
            FreeBirdBird(instrument, pitch, game.level, Modifier.fillMaxSize())
            if (!game.playing && !game.finished) {
                FreeBirdStartOverlay(
                    profile = profile,
                    onProfile = { profileDialogs.open(false) },
                    onStart = viewModel::startGame
                )
            }
        }
        game.feedback?.let {
            FreeBirdSingingNotes(instrument, game.level, it, Modifier.fillMaxSize())
        }
        if (game.playing || game.finished) {
            FreeBirdHud(
                game = game,
                profile = profile,
                onProfileClick = { profileDialogs.open(game.playing) }
            )
        }
        if (game.playing) {
            FreeBirdTimer(
                timeMs = game.timeMs,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 24.dp, bottom = 20.dp)
            )
        }
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.28f))
                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = Color.White
            )
        }
        if (game.playing || game.finished) {
            game.levelUp?.let { LevelUpOverlay(it) }
            if (game.finished) {
                GameOverOverlay(
                    score = game.score,
                    best = game.best,
                    onRestart = viewModel::startGame,
                    onBack = onBack
                )
            }
        }
        ProfileDialogs(
            state = profileDialogs,
            profiles = profiles,
            activeProfileId = profile?.id,
            initialInstrument = instrument,
            onSelect = viewModel::selectProfile,
            onCreate = viewModel::createProfile
        )
    }
}

@Composable
private fun FreeBirdStartOverlay(
    profile: ProfileRepository.Profile?,
    onProfile: () -> Unit,
    onStart: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 330.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 30.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.freebird_title),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(18.dp))
                profile?.let {
                    ProfileButton(name = it.name, emoji = it.emoji, onClick = onProfile)
                    Spacer(Modifier.height(16.dp))
                }
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.start), fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun FreeBirdHud(
    game: FreeBirdGameState,
    profile: ProfileRepository.Profile?,
    onProfileClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 70.dp, end = 12.dp, top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HudChip(stringResource(R.string.freebird_score, game.score))
        HudChip(stringResource(R.string.freebird_best, game.best))
        Spacer(Modifier.weight(1f))
        HudChip(stringResource(R.string.freebird_level, game.level))
        if (game.playing) {
            profile?.let {
                ProfileIconButton(emoji = it.emoji, name = it.name, onClick = onProfileClick)
            }
        }
    }
}

@Composable
private fun HudChip(text: String) {
    Surface(
        color = Color.Black.copy(alpha = 0.25f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun FreeBirdTimer(timeMs: Long, modifier: Modifier = Modifier) {
    val warning = timeMs < 5_000L
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.28f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
    ) {
        Text(
            text = formatTime(timeMs),
            fontSize = 42.sp,
            fontWeight = FontWeight.Black,
            color = if (warning) Color(0xFFFF5C5C) else Color.White,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun LevelUpOverlay(level: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            color = Color.Black.copy(alpha = 0.32f),
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
        ) {
            Text(
                text = stringResource(R.string.freebird_level, level),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 30.dp, vertical = 14.dp)
            )
        }
    }
}

@Composable
private fun GameOverOverlay(score: Int, best: Int, onRestart: () -> Unit, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 330.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 26.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.freebird_time_up),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.freebird_score, score),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.freebird_best, best),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onRestart,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.freebird_play_again), fontSize = 16.sp)
                }
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.back))
                }
            }
        }
    }
}

private fun formatTime(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
