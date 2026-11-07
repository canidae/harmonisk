package net.exent.harmonisk.ui.adventure

import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.state.ToggleableState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.exent.harmonisk.R
import net.exent.harmonisk.data.MelodyRepository
import net.exent.harmonisk.data.ProfileRepository
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.ui.components.BrandTopBar
import net.exent.harmonisk.ui.melody.MelodyPlayScreen
import net.exent.harmonisk.ui.melody.PlayPhase
import net.exent.harmonisk.ui.melody.MelodyViewModel
import net.exent.harmonisk.ui.profile.CreateProfileDialog
import net.exent.harmonisk.ui.whack.WhackRules
import net.exent.harmonisk.ui.whack.WhackScreen
import net.exent.harmonisk.ui.whack.WhackViewModel
import kotlinx.coroutines.delay

@Composable
fun AdventureScreen(
    onBack: () -> Unit,
    onHome: () -> Unit = onBack,
    viewModel: AdventureViewModel = hiltViewModel(),
    melodyViewModel: MelodyViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val melodies by viewModel.melodies.collectAsStateWithLifecycle()
    val currentPlayerInstrument by viewModel.currentPlayerInstrument.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showExitDialog by remember { mutableStateOf(false) }
    var showCreatePlayer by remember { mutableStateOf(false) }
    var playingLoaded by rememberSaveable(state.turnId) { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        Toast.makeText(context, context.getString(error), Toast.LENGTH_SHORT).show()
        viewModel.consumeError()
    }

    LaunchedEffect(state.step) {
        if (state.step != AdventureStep.PLAY) {
            melodyViewModel.stop()
            playingLoaded = false
        }
    }

    LaunchedEffect(state.phase, showExitDialog) {
        if (state.phase == AdventureTurnPhase.REVEALED && !showExitDialog) {
            delay(AdventureTiming.AUTO_PLAY_MS)
            viewModel.playCurrentSong()
        }
    }

    BackHandler(enabled = state.step != AdventureStep.PLAY && state.step != AdventureStep.WHACK) {
        when (state.step) {
            AdventureStep.PLAYERS -> onBack()
            AdventureStep.SONGS -> viewModel.backToPlayers()
            AdventureStep.RESULTS -> onHome()
            else -> showExitDialog = true
        }
    }

    if (state.step == AdventureStep.PLAY) {
        val song = state.currentSong
        LaunchedEffect(state.turnId) {
            if (playingLoaded) return@LaunchedEffect
            if (song == null) {
                viewModel.onSongLoadFailed()
            } else {
                playingLoaded = melodyViewModel.loadMelody(song)
                if (!playingLoaded) viewModel.onSongLoadFailed()
            }
        }
        if (playingLoaded) {
            MelodyPlayScreen(
                onBack = {
                    if (melodyViewModel.gameState.value.phase != PlayPhase.FINISHED) {
                        showExitDialog = true
                    }
                },
                viewModel = melodyViewModel,
                adventureMode = true,
                stopOnBack = false
            )
        } else {
            AdventureBackdrop(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = StorybookGold)
                }
            }
        }
        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = { Text(stringResource(R.string.adventure_exit_title)) },
                text = { Text(stringResource(R.string.adventure_exit_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        showExitDialog = false
                        melodyViewModel.stop()
                        viewModel.abandonSong()
                    }) { Text(stringResource(R.string.adventure_exit_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { showExitDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
        return
    }

    if (state.step == AdventureStep.RESULTS) {
        AdventureResultsScreen(
            state = state,
            onNewAdventure = viewModel::restart,
            onHome = onHome
        )
        return
    }

    if (state.step == AdventureStep.WHACK) {
        AdventureWhackScreen(
            state = state,
            instrument = currentPlayerInstrument,
            onFinished = viewModel::finishWhack,
            onBack = viewModel::abandonWhack
        )
        return
    }

    AdventureBackdrop(Modifier.fillMaxSize()) {
        when (state.step) {
            AdventureStep.PLAYERS -> PlayerSetupScreen(
                profiles = profiles,
                selectedIds = state.selectedPlayerIds,
                onToggle = viewModel::togglePlayer,
                onAdd = { showCreatePlayer = true },
                onContinue = viewModel::goToSongs,
                onBack = onBack
            )
            AdventureStep.SONGS -> SongSetupScreen(
                melodies = melodies,
                selectedIds = state.selectedSongIds,
                onToggle = viewModel::toggleSong,
                onSetGroup = viewModel::setSongs,
                onBack = viewModel::backToPlayers,
                onStart = viewModel::startGame
            )
            else -> AdventureGameScreen(
                state = state,
                onPull = viewModel::pullLever,
                onRoll = viewModel::rollDice,
                onBack = { showExitDialog = true }
            )
        }
    }

    if (showCreatePlayer) {
        CreateProfileDialog(
            initialInstrument = Instrument.VIOLIN,
            showInstrument = false,
            onCreate = { name, emoji, _ ->
                viewModel.createPlayer(name, emoji).also { if (it) showCreatePlayer = false }
            },
            onDismiss = { showCreatePlayer = false }
        )
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(stringResource(R.string.adventure_exit_title)) },
            text = { Text(stringResource(R.string.adventure_exit_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    viewModel.clear()
                    onBack()
                }) { Text(stringResource(R.string.adventure_exit_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun AdventureWhackScreen(
    state: AdventureState,
    instrument: Instrument,
    onFinished: (Int) -> Unit,
    onBack: () -> Unit
) {
    val whackViewModel: WhackViewModel = hiltViewModel()
    LaunchedEffect(Unit) {
        whackViewModel.reset(WhackRules.ADVENTURE_GAME_MS, persistBest = false)
    }
    WhackScreen(
        onBack = onBack,
        viewModel = whackViewModel,
        adventure = true,
        durationMs = WhackRules.ADVENTURE_GAME_MS,
        subtitle = state.currentPlayer?.name,
        instrumentOverride = instrument,
        onFinished = onFinished
    )
}

@Composable
private fun PlayerSetupScreen(
    profiles: List<ProfileRepository.Profile>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onAdd: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    val rows = remember(profiles) {
        buildList<PlayerCell> {
            profiles.forEach { add(PlayerCell.Profile(it)) }
            if (profiles.size < 4) add(PlayerCell.Add)
        }.chunked(2)
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            BrandTopBar(
                title = stringResource(R.string.adventure_players_title),
                onBack = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (isLandscape) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    profiles.forEach { profile ->
                        PlayerCard(
                            profile = profile,
                            colorIndex = profiles.indexOf(profile),
                            selected = profile.id in selectedIds,
                            enabled = profile.id in selectedIds || selectedIds.size < 4,
                            onClick = { onToggle(profile.id) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (profiles.size < 4) {
                        AddPlayerCard(onClick = onAdd, modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(rows) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            row.forEach { cell ->
                                when (cell) {
                                    is PlayerCell.Profile -> PlayerCard(
                                        profile = cell.profile,
                                        colorIndex = profiles.indexOf(cell.profile),
                                        selected = cell.profile.id in selectedIds,
                                        enabled = cell.profile.id in selectedIds || selectedIds.size < 4,
                                        onClick = { onToggle(cell.profile.id) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    PlayerCell.Add -> AddPlayerCard(
                                        onClick = onAdd,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            if (row.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onContinue,
                enabled = selectedIds.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StorybookGold, contentColor = Color(0xFF23163E))
            ) {
                Text(
                    text = stringResource(R.string.adventure_choose_songs),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun PlayerCard(
    profile: ProfileRepository.Profile,
    colorIndex: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = ADVENTURE_PLAYER_COLORS[colorIndex % ADVENTURE_PLAYER_COLORS.size]
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 88.dp)
            .then(
                if (selected) Modifier.border(2.dp, StorybookGold, RoundedCornerShape(22.dp))
                else Modifier
            ),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) color.copy(alpha = 0.24f) else Color(0xFF211B3B)
        )
    ) {
        Box(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AdventureAvatar(profile.emoji, colorIndex, size = 46.dp, selected = selected)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = StorybookGold,
                    modifier = Modifier.align(Alignment.TopEnd).size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun AddPlayerCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 88.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 88.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = StorybookGold)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.adventure_add_player),
                color = StorybookGold,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private sealed interface PlayerCell {
    data class Profile(val profile: ProfileRepository.Profile) : PlayerCell
    data object Add : PlayerCell
}

private data class SongGroup(
    val key: String,
    val title: String,
    val songs: List<MelodyRepository.MelodyInfo>
)

@Composable
private fun SongSetupScreen(
    melodies: List<MelodyRepository.MelodyInfo>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onSetGroup: (Collection<String>, Boolean) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit
) {
    val context = LocalContext.current
    val groups = remember(melodies) {
        val grouped = melodies.groupBy { it.isBundled to it.folder }
        grouped.map { (key, songs) ->
            val (bundled, folder) = key
            val title = when {
                folder == null -> context.getString(R.string.adventure_folder_other)
                else -> folderName(context, folder)
            }
            SongGroup(
                key = "${bundled}:${folder ?: ""}",
                title = title,
                songs = songs.sortedBy { it.name.lowercase() }
            )
        }.sortedBy { it.title.lowercase() }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            BrandTopBar(
                title = stringResource(R.string.adventure_songs_title),
                subtitle = stringResource(R.string.adventure_selected_songs, selectedIds.size),
                onBack = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(groups, key = { it.key }) { group ->
                    SongGroupCard(
                        group = group,
                        selectedIds = selectedIds,
                        onToggle = onToggle,
                        onSetGroup = onSetGroup
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onStart,
                enabled = selectedIds.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StorybookGold, contentColor = Color(0xFF23163E))
            ) {
                Text(
                    text = stringResource(R.string.adventure_start),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun SongGroupCard(
    group: SongGroup,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onSetGroup: (Collection<String>, Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    val selected = group.songs.count { it.id in selectedIds }
    val all = selected == group.songs.size
    val state = when {
        all -> ToggleableState.On
        selected > 0 -> ToggleableState.Indeterminate
        else -> ToggleableState.Off
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1936).copy(alpha = 0.92f))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TriStateCheckbox(
                    state = state,
                    onClick = { onSetGroup(group.songs.map { it.id }, !all) }
                )
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    tint = StorybookGold,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = pluralStringResource(R.plurals.adventure_song_count, group.songs.size, group.songs.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.55f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.65f)
                )
            }
            if (expanded) {
                group.songs.forEach { song ->
                    val isSelected = song.id in selectedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(song.id) }
                            .padding(start = 14.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggle(song.id) }
                        )
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = if (isSelected) StorybookMint else Color.White.copy(alpha = 0.45f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = song.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = if (isSelected) 1f else 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

private fun folderName(context: android.content.Context, folder: String): String {
    val resId = context.resources.getIdentifier("melody_folder_$folder", "string", context.packageName)
    return if (resId != 0) context.getString(resId)
    else folder.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
