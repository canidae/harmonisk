package com.perfectpitch.ui.freeplay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.perfectpitch.ui.components.AccuracyIndicator
import com.perfectpitch.ui.components.NoteDisplay
import com.perfectpitch.ui.components.PitchGauge
import com.perfectpitch.ui.components.RequireMicPermission
import com.perfectpitch.ui.theme.Green80
import com.perfectpitch.ui.theme.Red80
import com.perfectpitch.ui.theme.Yellow80
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreePlayScreen(
    onBack: () -> Unit,
    viewModel: FreePlayViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.startListening()
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopListening() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Free Play") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        RequireMicPermission {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Session stats
                SessionStatsBar(state.sessionStats)

                Spacer(modifier = Modifier.height(16.dp))

                // Note display
                NoteDisplay(
                    noteName = state.pitchResult.noteName,
                    octave = state.pitchResult.octave,
                    centsOff = state.pitchResult.centsOff,
                    isSilent = state.pitchResult.isSilent
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Accuracy indicator
                if (state.pitchResult.isValid) {
                    AccuracyIndicator(centsOff = state.pitchResult.centsOff)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Pitch gauge (smaller)
                PitchGauge(
                    centsOff = state.pitchResult.centsOff,
                    modifier = Modifier.fillMaxWidth(0.7f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Note history
                Text(
                    text = "Recent Notes",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.align(Alignment.Start)
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(state.noteHistory) { entry ->
                        NoteHistoryItem(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionStatsBar(stats: SessionStats) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem("Notes", "${stats.totalNotes}")
            StatItem("Accuracy", "${"%.0f".format(stats.accuracyPercent)}%")
            StatItem("Avg Off", "${"%.0f".format(stats.averageCentsOff)}¢")
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleLarge)
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NoteHistoryItem(entry: NoteHistoryEntry) {
    val color = when {
        abs(entry.centsOff) <= 10f -> Green80
        abs(entry.centsOff) <= 25f -> Yellow80
        else -> Red80
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "${entry.noteName}${entry.octave}",
            style = MaterialTheme.typography.bodyLarge,
            color = color
        )
        Text(
            text = "${if (entry.centsOff >= 0) "+" else ""}${"%.0f".format(entry.centsOff)}¢",
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            textAlign = TextAlign.End
        )
    }
}
