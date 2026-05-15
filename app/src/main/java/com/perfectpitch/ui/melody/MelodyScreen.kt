package com.perfectpitch.ui.melody

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MelodyScreen(
    onBack: () -> Unit,
    onStartPlay: () -> Unit = {},
    viewModel: MelodyViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var waitMode by remember { mutableStateOf(false) }
    var tempoMultiplier by remember { mutableFloatStateOf(1.0f) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.loadMelodyFromUri(it)
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
            // Melody list
            Text(
                text = "Choose a Melody",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Built-in melodies
            val melodies = remember { viewModel.getAvailableMelodies() }
            if (melodies.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(melodies) { info ->
                        Card(
                            onClick = {
                                viewModel.loadMelody(info)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🎵", style = MaterialTheme.typography.headlineMedium)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(info.name, style = MaterialTheme.typography.titleMedium)
                                    if (info.isBundled) {
                                        Text(
                                            "Built-in",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
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

            // Import MIDI button
            OutlinedButton(
                onClick = { filePickerLauncher.launch(arrayOf("audio/midi", "audio/x-midi", "application/x-midi")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Import MIDI File")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Settings section
            if (state.melodyTrack != null) {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Loaded: ${state.melodyTrack?.name}",
                    style = MaterialTheme.typography.titleMedium
                )

                val track = state.melodyTrack
                if (track != null) {
                    Text(
                        text = "${track.notes.size} notes • ${"%.0f".format(track.durationSeconds)}s",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Wait mode toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Wait for correct note", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = waitMode,
                        onCheckedChange = {
                            waitMode = it
                            viewModel.setWaitMode(it)
                        }
                    )
                }

                // Tempo slider
                Text(
                    text = "Tempo: ${"%.0f".format(tempoMultiplier * 100)}%",
                    style = MaterialTheme.typography.bodyLarge
                )
                Slider(
                    value = tempoMultiplier,
                    onValueChange = {
                        tempoMultiplier = it
                        viewModel.setTempoMultiplier(it)
                    },
                    valueRange = 0.5f..1.5f,
                    steps = 9
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Start button
                Button(
                    onClick = {
                        viewModel.startPlaying()
                        onStartPlay()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("Start Playing", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}
