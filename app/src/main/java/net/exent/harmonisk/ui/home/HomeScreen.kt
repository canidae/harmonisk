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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.withTimeoutOrNull
import net.exent.harmonisk.BuildConfig
import net.exent.harmonisk.R
import net.exent.harmonisk.data.ProfileRepository
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.ui.components.AppLogo
import net.exent.harmonisk.ui.components.BrandTopBar
import net.exent.harmonisk.ui.components.InstrumentSelector
import net.exent.harmonisk.ui.components.brandBackground
import net.exent.harmonisk.ui.profile.CreateProfileDialog
import net.exent.harmonisk.ui.profile.ProfileButton
import net.exent.harmonisk.ui.profile.ProfileDialog
import net.exent.harmonisk.ui.profile.ProfileEditDialog
import net.exent.harmonisk.ui.profile.ProfileSetupScreen
import net.exent.harmonisk.ui.theme.AdventureGold
import net.exent.harmonisk.ui.theme.BrandBlue
import net.exent.harmonisk.ui.theme.BrandPurple
import net.exent.harmonisk.ui.theme.BrandTeal
import net.exent.harmonisk.ui.theme.PlayGreen

private const val DEV_MODE_HOLD_MS = 7_000L

private data class ModeItem(
    val emoji: String,
    val title: String,
    val accent: Color,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToRecord: () -> Unit,
    onNavigateToMelody: () -> Unit,
    onNavigateToGames: () -> Unit,
    onNavigateToMetronome: () -> Unit,
    onNavigateToTuner: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val snackbarHostState = remember { SnackbarHostState() }
    val selectedInstrument by viewModel.instrument.collectAsStateWithLifecycle()
    val devModeEnabled by viewModel.devModeEnabled.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val profile = activeProfile
    var showProfileDialog by remember { mutableStateOf(false) }
    var showCreateProfileDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ProfileRepository.Profile?>(null) }
    LaunchedEffect(profile) {
        if (profile == null) {
            showProfileDialog = false
            showCreateProfileDialog = false
            editingProfile = null
        }
    }
    if (profile == null) {
        ProfileSetupScreen(onCreate = viewModel::createProfile)
        return
    }
    val modes = listOfNotNull(
        if (devModeEnabled) ModeItem(
            emoji = "🎵",
            title = stringResource(R.string.record_title),
            accent = BrandPurple,
            onClick = onNavigateToRecord
        ) else null,
        ModeItem(
            emoji = "🎼",
            title = stringResource(R.string.melody_title),
            accent = BrandTeal,
            onClick = onNavigateToMelody
        ),
        ModeItem(
            emoji = "🏆",
            title = stringResource(R.string.games_title),
            accent = AdventureGold,
            onClick = onNavigateToGames
        ),
        ModeItem(
            emoji = "⏱️",
            title = stringResource(R.string.metronome_title),
            accent = BrandBlue,
            onClick = onNavigateToMetronome
        ),
        ModeItem(
            emoji = "🎸",
            title = stringResource(R.string.tuner_title),
            accent = PlayGreen,
            onClick = onNavigateToTuner
        )
    )
    val toggleDevMode = {
        val enabled = viewModel.toggleDevMode()
        Toast.makeText(
            context,
            resources.getString(if (enabled) R.string.dev_mode_enabled else R.string.dev_mode_disabled),
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
                    ProfileButton(
                        name = profile.name,
                        emoji = profile.emoji,
                        onClick = { showProfileDialog = true }
                    )
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
            InstrumentSelector(
                selected = selectedInstrument,
                onSelect = viewModel::setInstrument,
                modifier = Modifier.padding(
                    horizontal = 24.dp,
                    vertical = if (isLandscape) 8.dp else 16.dp
                )
            )
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

    if (showProfileDialog) {
        ProfileDialog(
            profiles = profiles,
            activeProfileId = profile.id,
            onSelect = { id ->
                viewModel.selectProfile(id)
                showProfileDialog = false
            },
            onAdd = {
                showProfileDialog = false
                showCreateProfileDialog = true
            },
            onDelete = viewModel::deleteProfile,
            onEdit = { editingProfile = it; showProfileDialog = false },
            onDismiss = { showProfileDialog = false }
        )
    }

    if (showCreateProfileDialog) {
        CreateProfileDialog(
            initialInstrument = selectedInstrument,
            onCreate = viewModel::createProfile,
            onDismiss = { showCreateProfileDialog = false }
        )
    }

    editingProfile?.let { profile ->
        val instrument by remember(profile.id) { viewModel.instrument(profile.id) }
            .collectAsStateWithLifecycle(initialValue = Instrument.VIOLIN)
        ProfileEditDialog(
            profile = profile,
            instrument = instrument,
            onSave = { name, emoji, selected ->
                viewModel.updateProfile(profile.id, name, emoji, selected)
            },
            onDismiss = { editingProfile = null }
        )
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
    val shape = RoundedCornerShape(if (compact) 24.dp else 28.dp)
    Card(
        onClick = mode.onClick,
        modifier = modifier.fillMaxWidth(),
        shape = shape
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
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
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mode.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
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
