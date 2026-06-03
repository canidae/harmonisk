package com.perfectpitch.ui.melody

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.perfectpitch.data.ScoreHistoryRepository.ScoreEntry
import com.perfectpitch.music.MelodyScore
import com.perfectpitch.ui.theme.Green80
import com.perfectpitch.ui.theme.Red80
import com.perfectpitch.ui.theme.Teal80
import com.perfectpitch.ui.theme.Yellow80

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MelodyScreen(
    onBack: () -> Unit,
    onStartPlay: () -> Unit = {},
    viewModel: MelodyViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isPreviewPlaying by viewModel.isPreviewPlaying.collectAsStateWithLifecycle()
    val allAverages by viewModel.allMelodyAverages.collectAsStateWithLifecycle()
    var previewingMelody by remember { mutableStateOf<String?>(null) }
    var expandedMelodyId by remember { mutableStateOf<String?>(null) }

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

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            if (viewModel.loadMelodyFromUri(it)) {
                viewModel.stopPreview()
                previewingMelody = null
                onStartPlay()
            } else {
                Toast.makeText(context, "Failed to load MIDI file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Play by Melody") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
                text = "Choose a Melody",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            val melodies by viewModel.availableMelodies.collectAsStateWithLifecycle()
            if (melodies.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(melodies) { info ->
                        MelodyCard(
                            viewModel = viewModel,
                            info = info,
                            averageScore = allAverages[info.id],
                            isPreviewing = previewingMelody == info.name && isPreviewPlaying,
                            isExpanded = expandedMelodyId == info.id,
                            onPlay = {
                                viewModel.stopPreview()
                                previewingMelody = null
                                if (viewModel.loadMelody(info)) {
                                    onStartPlay()
                                } else {
                                    Toast.makeText(context, "Failed to load melody", Toast.LENGTH_SHORT).show()
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
                }
            } else {
                Text(
                    text = "No bundled melodies available.\nImport a MIDI file to get started.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = { filePickerLauncher.launch(arrayOf("audio/midi", "audio/x-midi", "application/x-midi")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Import MIDI File")
            }
        }
    }
}

@Composable
private fun MelodyCard(
    viewModel: MelodyViewModel,
    info: com.perfectpitch.data.MelodyRepository.MelodyInfo,
    averageScore: Float?,
    isPreviewing: Boolean,
    isExpanded: Boolean,
    onPlay: () -> Unit,
    onTogglePreview: () -> Unit,
    onToggleExpand: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onPlay)
                    .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🎵", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(info.name, style = MaterialTheme.typography.titleMedium)
                    if (info.isBundled) {
                        Text(
                            "Built-in",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (averageScore != null) {
                    ScoreBadge(score = averageScore, onClick = onToggleExpand)
                    Spacer(modifier = Modifier.width(4.dp))
                }

                IconButton(onClick = onTogglePreview) {
                    Icon(
                        imageVector = if (isPreviewing) Icons.Filled.Close else Icons.Filled.PlayArrow,
                        contentDescription = if (isPreviewing) "Stop preview" else "Preview melody",
                        tint = MaterialTheme.colorScheme.primary
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
private fun ScoreBadge(
    score: Float,
    onClick: () -> Unit
) {
    val color = scoreColor(score)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.2f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "${score.toInt()}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
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
                "No playthroughs yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (scoreHistory.size >= 2) {
            ScoreProgressGraph(scores = scoreHistory, compact = false)
        }

        scoreHistory.lastOrNull()?.let { entry ->
            val melodyScore = entry.toMelodyScore()
            ScoreSummaryPanel(score = melodyScore)
        }
    }
}

private fun ScoreEntry.toMelodyScore(): MelodyScore = MelodyScore(
    overallScore = overallScore,
    totalNotes = totalNotes,
    notesHit = notesHit,
    perfectNotes = perfectNotes,
    greatNotes = greatNotes,
    longestStreak = longestStreak,
    avgPitchScore = avgPitchScore,
    avgDurationScore = avgDurationScore
)

private fun scoreColor(score: Float): androidx.compose.ui.graphics.Color = when {
    score >= 95f -> Green80
    score >= 60f -> Teal80
    score >= 20f -> Yellow80
    else -> Red80
}
