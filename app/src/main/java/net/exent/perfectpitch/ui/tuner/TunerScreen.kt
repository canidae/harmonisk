package net.exent.perfectpitch.ui.tuner

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import net.exent.perfectpitch.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
                title = { Text(stringResource(R.string.tuner_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
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
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.tuner_frequency, state.displayedResult.frequency),
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.alpha(if (state.displayedResult.isValid) 1f else 0f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(R.string.tuner_reference, state.referenceFrequency),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
