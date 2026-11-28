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
import androidx.compose.runtime.remember
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
    val layer = remember { Modifier.fillMaxSize() }
    Box(Modifier.fillMaxSize()) {
        FreeBirdBackdrop(layer)
        FreeBirdStaff(instrument, layer)
        FreeBirdSeagull(layer)
        FreeBirdLetters(instrument, game.letters, pitch, layer)
        RequireMicPermission {
            FreeBirdBird(instrument, pitch, game.beatAt, game.beatAccent, layer)
            if (!game.playing && !game.finished) {
                FreeBirdStartOverlay(
                    profile = profile,
                    onProfile = { profileDialogs.open(false) },
                    onStart = viewModel::startGame
                )
            }
        }
        game.feedback?.let {
            FreeBirdSingingNotes(instrument, it, layer)
        }
        val openProfile = remember(profileDialogs, game.playing) {
            { profileDialogs.open(game.playing) }
        }
        if (game.playing || game.finished) {
            FreeBirdHud(
                score = game.score,
                best = game.best,
                level = game.level,
                playing = game.playing,
                profile = profile,
                onProfileClick = openProfile
            )
        }
        if (game.playing) {
            FreeBirdTimer(
                seconds = game.timeMs / 1_000L,
                warning = game.timeMs < 5_000L,
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
            if (game.finished) {
                GameOverOverlay(
                    score = game.score,
                    best = game.best,
                    profile = profile,
                    onProfile = { profileDialogs.open(false) },
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
    score: Int,
    best: Int,
    level: Int,
    playing: Boolean,
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
        HudChip(stringResource(R.string.freebird_score, score))
        HudChip(stringResource(R.string.freebird_best, best))
        Spacer(Modifier.weight(1f))
        HudChip(stringResource(R.string.freebird_level, level))
        if (playing) {
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
private fun FreeBirdTimer(seconds: Long, warning: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.28f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
    ) {
        Text(
            text = formatTime(seconds),
            fontSize = 42.sp,
            fontWeight = FontWeight.Black,
            color = if (warning) Color(0xFFFF5C5C) else Color.White,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun GameOverOverlay(
    score: Int,
    best: Int,
    profile: ProfileRepository.Profile?,
    onProfile: () -> Unit,
    onRestart: () -> Unit,
    onBack: () -> Unit
) {
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
                profile?.let {
                    ProfileButton(name = it.name, emoji = it.emoji, onClick = onProfile)
                    Spacer(Modifier.height(16.dp))
                }
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

private fun formatTime(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
