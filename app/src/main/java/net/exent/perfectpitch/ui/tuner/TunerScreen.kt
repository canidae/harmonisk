package net.exent.perfectpitch.ui.tuner

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.exent.perfectpitch.ui.components.PitchGauge
import net.exent.perfectpitch.ui.components.RequireMicPermission
import net.exent.perfectpitch.ui.components.KeepScreenOn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunerScreen(
    onBack: () -> Unit,
    viewModel: TunerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    KeepScreenOn()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tune Instrument") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        RequireMicPermission {
            LaunchedEffect(Unit) {
                viewModel.startListening()
            }

            DisposableEffect(Unit) {
                onDispose { viewModel.stopListening() }
            }

            val contentAlpha = 1f

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .alpha(contentAlpha)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    PitchGauge(
                        centsOff = state.smoothedCentsOff,
                        noteName = state.displayedResult.noteName,
                        octave = state.displayedResult.octave,
                        isSilent = !state.displayedResult.isValid,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.alpha(contentAlpha)
                ) {
                    Text(
                        text = "${"%.1f".format(state.displayedResult.frequency)} Hz",
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.alpha(if (state.displayedResult.isValid) 1f else 0f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Reference: ${"%.0f".format(state.referenceFrequency)} Hz",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
