package net.exent.harmonisk.ui.home

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.withTimeoutOrNull
import net.exent.harmonisk.BuildConfig
import net.exent.harmonisk.R
import net.exent.harmonisk.data.DevModeState
import net.exent.harmonisk.ui.components.AppLogo
import net.exent.harmonisk.ui.components.BrandTopBar
import net.exent.harmonisk.ui.components.brandBackground
import net.exent.harmonisk.ui.theme.BrandBlue
import net.exent.harmonisk.ui.theme.BrandPurple
import net.exent.harmonisk.ui.theme.BrandTeal
import net.exent.harmonisk.ui.theme.PlayGreen
import javax.inject.Inject

private const val DEV_MODE_HOLD_MS = 7_000L

private data class ModeItem(
    val emoji: String,
    val title: String,
    val accent: Color,
    val onClick: () -> Unit
)

@Composable
fun HomeScreen(
    onNavigateToFreePlay: () -> Unit,
    onNavigateToMelody: () -> Unit,
    onNavigateToMetronome: () -> Unit,
    onNavigateToTuner: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val snackbarHostState = remember { SnackbarHostState() }
    val modes = listOf(
        ModeItem(
            emoji = "🎵",
            title = stringResource(R.string.home_free_play_title),
            accent = BrandPurple,
            onClick = onNavigateToFreePlay
        ),
        ModeItem(
            emoji = "🎼",
            title = stringResource(R.string.home_melody_title),
            accent = BrandTeal,
            onClick = onNavigateToMelody
        ),
        ModeItem(
            emoji = "⏱️",
            title = stringResource(R.string.home_metronome_title),
            accent = BrandBlue,
            onClick = onNavigateToMetronome
        ),
        ModeItem(
            emoji = "🎸",
            title = stringResource(R.string.home_tuner_title),
            accent = PlayGreen,
            onClick = onNavigateToTuner
        )
    )
    val toggleDevMode = {
        val enabled = viewModel.toggleDevMode()
        Toast.makeText(
            context,
            context.getString(if (enabled) R.string.dev_mode_enabled else R.string.dev_mode_disabled),
            Toast.LENGTH_SHORT
        ).show()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .brandBackground(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            BrandTopBar(
                title = stringResource(R.string.app_name),
                leading = { AppLogo() },
                titleModifier = Modifier.devModeToggle(toggleDevMode),
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (isLandscape) {
                    LandscapeContent(
                        modifier = Modifier
                            .fillMaxSize(),
                        modes = modes
                    )
                } else {
                    PortraitContent(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        modes = modes
                    )
                }
            }
            Text(
                text = stringResource(R.string.home_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun PortraitContent(
    modifier: Modifier,
    modes: List<ModeItem>
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        modes.forEach { ModeCard(mode = it) }
    }
}

@Composable
private fun LandscapeContent(
    modifier: Modifier,
    modes: List<ModeItem>
) {
    Row(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        modes.forEach { mode ->
            ModeCard(mode = mode, compact = true, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ModeCard(
    mode: ModeItem,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = mode.onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 24.dp else 28.dp)
    ) {
        if (compact) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ModeIcon(mode, size = 44.dp, fontSize = 22.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = mode.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModeIcon(mode, size = 52.dp, fontSize = 26.sp)
                Spacer(Modifier.width(16.dp))
                Text(
                    text = mode.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = mode.accent,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
private fun ModeIcon(mode: ModeItem, size: Dp, fontSize: TextUnit) {
    Box(
        modifier = Modifier
            .size(size)
            .background(
                Brush.linearGradient(
                    listOf(mode.accent, mode.accent.copy(alpha = 0.62f))
                ),
                RoundedCornerShape(size / 3)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(mode.emoji, fontSize = fontSize)
    }
}

private fun Modifier.devModeToggle(onToggle: () -> Unit) = pointerInput(onToggle) {
    detectTapGestures(
        onPress = {
            if (withTimeoutOrNull(DEV_MODE_HOLD_MS) { tryAwaitRelease() } == null) {
                onToggle()
            }
        }
    )
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val devModeState: DevModeState
) : ViewModel() {

    fun toggleDevMode(): Boolean = devModeState.toggle()
}
