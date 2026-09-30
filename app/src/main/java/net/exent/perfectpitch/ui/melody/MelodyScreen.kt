package net.exent.perfectpitch.ui.melody

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.exent.perfectpitch.R
import net.exent.perfectpitch.data.MelodyRepository
import net.exent.perfectpitch.ui.utils.formatScore
import net.exent.perfectpitch.ui.utils.scoreColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MelodyScreen(
    onBack: () -> Unit,
    onStartPlay: () -> Unit = {},
    viewModel: MelodyViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isPreviewPlaying by viewModel.isPreviewPlaying.collectAsStateWithLifecycle()
    val combinedScores by viewModel.allMelodyCombinedScores.collectAsStateWithLifecycle()
    val difficulties by viewModel.allMelodyDifficulties.collectAsStateWithLifecycle()
    var previewingMelody by remember { mutableStateOf<String?>(null) }
    var expandedMelodyId by remember { mutableStateOf<String?>(null) }
    // The folder the player was in has to outlive the trip into a song, or coming back lists the
    // folders again instead of the songs that were being chosen from.
    var currentFolder by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.initSynthesizer()
    }

    LaunchedEffect(isPreviewPlaying) {
        if (!isPreviewPlaying) {
            previewingMelody = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopPreview()
        }
    }

    BackHandler(enabled = currentFolder != null) {
        currentFolder = null
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            if (viewModel.loadMelodyFromUri(it)) {
                viewModel.stopPreview()
                previewingMelody = null
                Toast.makeText(context, context.getString(R.string.melody_import_success), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.melody_failed_load_midi), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun folderDisplayName(folder: String): String {
        val resId = context.resources.getIdentifier("melody_folder_$folder", "string", context.packageName)
        return if (resId != 0) context.getString(resId) else folder.replaceFirstChar { it.uppercase() }
    }

    val melodies by viewModel.sortedMelodies.collectAsStateWithLifecycle()
    val importedMelodies = melodies.filter { !it.isBundled }
    val bundledFolders = melodies.filter { it.isBundled && it.folder != null }.groupBy { it.folder!! }
    val looseBundled = melodies.filter { it.isBundled && it.folder == null }

    val currentFolderSongs = when (currentFolder) {
        null -> emptyList()
        MelodyRepository.IMPORTED_FOLDER -> importedMelodies
        else -> bundledFolders[currentFolder].orEmpty()
    }

    val songCard: @Composable (MelodyRepository.MelodyInfo) -> Unit = { info ->
        MelodyCard(
            viewModel = viewModel,
            info = info,
            combinedScore = combinedScores[info.id],
            difficulty = difficulties[info.id],
            isPreviewing = previewingMelody == info.name && isPreviewPlaying,
            isExpanded = expandedMelodyId == info.id,
            onPlay = {
                viewModel.stopPreview()
                previewingMelody = null
                if (viewModel.loadMelody(info)) {
                    onStartPlay()
                } else {
                    Toast.makeText(context, context.getString(R.string.melody_failed_load_melody), Toast.LENGTH_SHORT).show()
                }
            },
            onTogglePreview = {
                if (previewingMelody == info.name && isPreviewPlaying) {
                    viewModel.stopPreview()
                    previewingMelody = null
                } else {
                    previewingMelody = info.name
                    viewModel.previewMelody(info)
                }
            },
            onToggleExpand = {
                expandedMelodyId = if (expandedMelodyId == info.id) null else info.id
                if (expandedMelodyId == info.id) {
                    viewModel.loadMelody(info)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.melody_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentFolder != null) currentFolder = null else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                text = if (currentFolder != null) folderDisplayName(currentFolder!!) else stringResource(R.string.melody_choose),
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            when {
                currentFolder == null -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(looseBundled) { info -> songCard(info) }
                        item {
                            FolderCard(
                                name = folderDisplayName(MelodyRepository.IMPORTED_FOLDER),
                                count = importedMelodies.size,
                                onClick = { currentFolder = MelodyRepository.IMPORTED_FOLDER }
                            )
                        }
                        items(bundledFolders.keys.sortedBy { folderDisplayName(it) }) { folder ->
                            FolderCard(
                                name = folderDisplayName(folder),
                                count = bundledFolders[folder]!!.size,
                                onClick = { currentFolder = folder }
                            )
                        }
                    }
                }
                currentFolderSongs.isNotEmpty() -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(currentFolderSongs) { info -> songCard(info) }
                    }

                    if (currentFolder == MelodyRepository.IMPORTED_FOLDER) {
                        Spacer(modifier = Modifier.height(16.dp))
                        ImportButton(onClick = {
                            filePickerLauncher.launch(arrayOf("audio/midi", "audio/x-midi", "application/x-midi"))
                        })
                    }
                }
                else -> {
                    Text(
                        text = context.getString(R.string.melody_no_imported),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    ImportButton(onClick = {
                        filePickerLauncher.launch(arrayOf("audio/midi", "audio/x-midi", "application/x-midi"))
                    })
                }
            }
        }
    }
}

@Composable
private fun FolderCard(
    name: String,
    count: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📂", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ImportButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.melody_import_file))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MelodyCard(
    viewModel: MelodyViewModel,
    info: net.exent.perfectpitch.data.MelodyRepository.MelodyInfo,
    combinedScore: Float?,
    difficulty: Float?,
    isPreviewing: Boolean,
    isExpanded: Boolean,
    onPlay: () -> Unit,
    onTogglePreview: () -> Unit,
    onToggleExpand: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onPlay,
                        onLongClick = { showMenu = true }
                    )
                    .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (info.isBundled) "🎵" else "🎶", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(info.name, style = MaterialTheme.typography.titleMedium)
                    if (difficulty != null) {
                        DifficultyBadge(difficulty = difficulty)
                    }
                }

                if (combinedScore != null) {
                    CombinedScoreBadge(score = combinedScore, onClick = onToggleExpand)
                    Spacer(modifier = Modifier.width(4.dp))
                }

                IconButton(onClick = onTogglePreview) {
                    Icon(
                        imageVector = if (isPreviewing) Icons.Filled.Close else Icons.Filled.PlayArrow,
                        contentDescription = if (isPreviewing) stringResource(R.string.melody_stop_preview) else stringResource(R.string.melody_preview),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.melody_reset_score)) },
                    onClick = {
                        showMenu = false
                        viewModel.resetScore(info.id)
                    }
                )
                if (!info.isBundled) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.melody_remove_song),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            showMenu = false
                            viewModel.removeImportedMelody(info.id)
                        }
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                MelodyProgressSection(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun CombinedScoreBadge(
    score: Float,
    onClick: () -> Unit
) {
    val avgPerLevel = score / 5f
    val color = scoreColor(avgPerLevel)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.2f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = formatScore(score),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun DifficultyBadge(difficulty: Float) {
    val outlineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val fillColors = listOf(Color.Green, Color.Yellow, Color(0xFFFF9800), Color.Red)
    val badgeHeight = 14.dp
    val badgeWidth = 32.dp

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.melody_difficulty),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Canvas(modifier = Modifier.size(width = badgeWidth, height = badgeHeight)) {
            val path = Path().apply {
                moveTo(0f, size.height)
                lineTo(size.width, size.height)
                lineTo(size.width, 0f)
                close()
            }
            drawPath(path, color = outlineColor, style = Stroke(width = 1.dp.toPx()))
            clipPath(path) {
                val fillEnd = size.width * difficulty.coerceIn(0f, 1f)
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = fillColors,
                        startX = 0f,
                        endX = size.width
                    ),
                    size = size.copy(width = fillEnd)
                )
            }
        }
    }
}

@Composable
private fun MelodyProgressSection(
    viewModel: MelodyViewModel
) {
    val scoreHistory by viewModel.currentScoreHistory.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))

        if (scoreHistory.isEmpty()) {
            Text(
                stringResource(R.string.melody_no_playthroughs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (scoreHistory.size >= 1) {
            ScoreProgressGraph(scores = scoreHistory, compact = false)
        }
    }
}
