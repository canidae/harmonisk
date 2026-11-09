package net.exent.harmonisk.ui.soundtest

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import net.exent.harmonisk.R
import net.exent.harmonisk.audio.MidiCue
import net.exent.harmonisk.audio.MidiCues
import net.exent.harmonisk.ui.adventure.AdventureBackdrop
import net.exent.harmonisk.ui.components.BrandTopBar
import net.exent.harmonisk.ui.theme.AdventureGold

@Composable
fun SoundTestScreen(
    onBack: () -> Unit,
    viewModel: SoundTestViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) {
        viewModel.prepare()
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.stop() }
    }
    AdventureBackdrop(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
                BrandTopBar(
                    title = stringResource(R.string.sound_test_title),
                    onBack = onBack
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CueCard(R.string.sound_test_turn, MidiCues.TurnChime, viewModel)
                CueCard(R.string.sound_test_dice, MidiCues.DiceRattle, viewModel)
                CueCard(R.string.sound_test_drumroll, MidiCues.DrumRoll, viewModel)
                CueCard(R.string.sound_test_applause, MidiCues.Applause, viewModel)
                CueCard(R.string.sound_test_whistle, MidiCues.Whistle, viewModel)
                CueCard(R.string.sound_test_faerie, MidiCues.Faerie, viewModel)
                CueCard(R.string.sound_test_castle, MidiCues.CastleFanfare, viewModel)
                CueCard(R.string.sound_test_fanfare, MidiCues.Fanfare, viewModel)
                CueCard(R.string.sound_test_whack, MidiCues.WhackIntro, viewModel)
                CueCard(R.string.sound_test_song, MidiCues.SongIntro, viewModel)
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = viewModel::playAll,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        text = stringResource(R.string.sound_test_play_all),
                        fontWeight = FontWeight.Bold
                    )
                }
                OutlinedButton(
                    onClick = viewModel::stop,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(stringResource(R.string.sound_test_stop))
                }
            }
        }
    }
}

@Composable
private fun CueCard(
    @StringRes label: Int,
    cue: MidiCue,
    viewModel: SoundTestViewModel
) {
    Card(
        onClick = { viewModel.play(cue) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = AdventureGold
            )
        }
    }
}
