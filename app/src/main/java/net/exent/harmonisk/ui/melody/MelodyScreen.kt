package net.exent.harmonisk.ui.melody

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QrCodeScanner
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import net.exent.harmonisk.R
import net.exent.harmonisk.data.MelodyRepository
import net.exent.harmonisk.ui.components.BrandTopBar
import net.exent.harmonisk.ui.components.brandBackground
import net.exent.harmonisk.ui.profile.CreateProfileDialog
import net.exent.harmonisk.ui.profile.ProfileButton
import net.exent.harmonisk.ui.profile.ProfileSwitchDialog
import net.exent.harmonisk.ui.theme.BrandPurple
import net.exent.harmonisk.ui.utils.formatScore
import net.exent.harmonisk.ui.utils.scoreColor
import kotlinx.coroutines.launch

/** The importer sniffs the file's content, so the picker accepts any file. */
private val IMPORT_MIME_TYPES = arrayOf("*/*")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MelodyScreen(
    onBack: () -> Unit,
    onStartPlay: () -> Unit = {},
    viewModel: MelodyViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isPreviewPlaying by viewModel.isPreviewPlaying.collectAsStateWithLifecycle()
    val scores by viewModel.allMelodyScores.collectAsStateWithLifecycle()
    val difficulties by viewModel.allMelodyDifficulties.collectAsStateWithLifecycle()
    var previewingMelody by remember { mutableStateOf<String?>(null) }
    var expandedMelodyId by remember { mutableStateOf<String?>(null) }
    // The folder the player was in has to outlive the trip into a song, or coming back lists the
    // folders again instead of the songs that were being chosen from.
    var currentFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var currentSubfolder by rememberSaveable { mutableStateOf<String?>(null) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlText by rememberSaveable { mutableStateOf("") }
    var importingUrl by remember { mutableStateOf<String?>(null) }
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val profile by viewModel.activeProfile.collectAsStateWithLifecycle()
    var showProfileDialog by remember { mutableStateOf(false) }
    var showCreateProfileDialog by remember { mutableStateOf(false) }

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
        if (currentSubfolder != null) currentSubfolder = null else currentFolder = null
    }

    val scope = rememberCoroutineScope()
    fun importUrl(url: String) {
        if (importingUrl != null) return
        importingUrl = url
        scope.launch {
            when (val result = viewModel.importFromUrl(url)) {
                null -> Toast.makeText(
                    context,
                    context.getString(R.string.melody_failed_load_url),
                    Toast.LENGTH_SHORT
                ).show()
                is MelodyRepository.ImportResult.Song -> {
                    currentFolder = MelodyRepository.IMPORTED_FOLDER
                    currentSubfolder = null
                    Toast.makeText(context, context.getString(R.string.melody_import_success), Toast.LENGTH_SHORT).show()
                }
                is MelodyRepository.ImportResult.Folder -> {
                    currentFolder = MelodyRepository.IMPORTED_FOLDER
                    currentSubfolder = result.folder
                    Toast.makeText(context, context.getString(R.string.melody_import_folder_success), Toast.LENGTH_SHORT).show()
                }
            }
            importingUrl = null
        }
    }

    val qrScanLauncher = rememberLauncherForActivityResult(contract = ScanContract()) { result ->
        result.contents?.trim()?.takeIf { it.isNotEmpty() }?.let { importUrl(it) }
    }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                when (val result = viewModel.importFile(it)) {
                    is MelodyRepository.ImportResult.Song -> {
                        viewModel.stopPreview()
                        previewingMelody = null
                        if (viewModel.loadMelody(result.info)) {
                            Toast.makeText(context, context.getString(R.string.melody_import_success), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, context.getString(R.string.melody_failed_load_melody), Toast.LENGTH_SHORT).show()
                        }
                    }
                    is MelodyRepository.ImportResult.Folder -> {
                        currentFolder = MelodyRepository.IMPORTED_FOLDER
                        currentSubfolder = result.folder
                        Toast.makeText(context, context.getString(R.string.melody_import_folder_success), Toast.LENGTH_SHORT).show()
                    }
                    null -> Toast.makeText(context, context.getString(R.string.melody_failed_load_midi), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun folderDisplayName(folder: String): String {
        val resId = context.resources.getIdentifier("melody_folder_$folder", "string", context.packageName)
        return if (resId != 0) context.getString(resId)
        else folder.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    val melodies by viewModel.sortedMelodies.collectAsStateWithLifecycle()
    val importedMelodies = melodies.filter { !it.isBundled }
    val looseImported = importedMelodies.filter { it.folder == MelodyRepository.IMPORTED_FOLDER }
    val importedSubfolders = importedMelodies
        .filter { it.folder != null && it.folder != MelodyRepository.IMPORTED_FOLDER }
        .groupBy { it.folder!! }
    val bundledFolders = melodies.filter { it.isBundled && it.folder != null }.groupBy { it.folder!! }
    val looseBundled = melodies.filter { it.isBundled && it.folder == null }

    val inImportedFolder = currentFolder == MelodyRepository.IMPORTED_FOLDER
    val browsingImported = inImportedFolder && currentSubfolder == null
    val currentFolderSongs = when {
        currentSubfolder != null -> importedSubfolders[currentSubfolder].orEmpty()
        inImportedFolder -> looseImported
        currentFolder != null -> bundledFolders[currentFolder].orEmpty()
        else -> emptyList()
    }

    val songCard: @Composable (MelodyRepository.MelodyInfo) -> Unit = { info ->
        MelodyCard(
            viewModel = viewModel,
            info = info,
            scoreSummary = scores[info.id],
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
        modifier = Modifier
            .fillMaxSize()
            .brandBackground(),
        containerColor = Color.Transparent,
        bottomBar = {
            if (browsingImported) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ImportButton(Modifier.weight(1f).fillMaxHeight(), Icons.Default.Add, R.string.melody_import_file) {
                        filePickerLauncher.launch(IMPORT_MIME_TYPES)
                    }
                    ImportButton(Modifier.weight(1f).fillMaxHeight(), Icons.Default.Link, R.string.melody_import_url) {
                        urlText = ""
                        showUrlDialog = true
                    }
                    ImportButton(Modifier.weight(1f).fillMaxHeight(), Icons.Default.QrCodeScanner, R.string.melody_import_qr) {
                        qrScanLauncher.launch(
                            ScanOptions().apply {
                                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                setPrompt(context.getString(R.string.melody_import_qr_prompt))
                                setBeepEnabled(false)
                            }
                        )
                    }
                }
            }
        },
        topBar = {
            BrandTopBar(
                title = stringResource(R.string.melody_title),
                subtitle = when {
                    currentSubfolder != null -> folderDisplayName(currentSubfolder!!)
                    currentFolder != null -> folderDisplayName(currentFolder!!)
                    else -> stringResource(R.string.melody_choose)
                },
                onBack = {
                    when {
                        currentSubfolder != null -> currentSubfolder = null
                        currentFolder != null -> currentFolder = null
                        else -> onBack()
                    }
                },
                actions = {
                    profile?.let {
                        ProfileButton(it.name, it.emoji, onClick = { showProfileDialog = true })
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
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
                currentFolderSongs.isNotEmpty() || inImportedFolder -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (browsingImported) {
                            items(importedSubfolders.keys.sortedBy { folderDisplayName(it) }) { folder ->
                                FolderCard(
                                    name = folderDisplayName(folder),
                                    count = importedSubfolders[folder]!!.size,
                                    onClick = { currentSubfolder = folder },
                                    onRemove = { viewModel.removeImportedFolder(folder) }
                                )
                            }
                        }
                        items(currentFolderSongs) { info -> songCard(info) }
                        if (browsingImported && currentFolderSongs.isEmpty() && importedSubfolders.isEmpty()) {
                            item {
                                Text(
                                    text = context.getString(R.string.melody_no_imported),
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(32.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text(stringResource(R.string.melody_import_url)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.melody_import_url_hint),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        singleLine = true,
                        placeholder = { Text("https://…") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = urlText.isNotBlank(),
                    onClick = {
                        val url = urlText.trim()
                        showUrlDialog = false
                        importUrl(url)
                    }
                ) {
                    Text(stringResource(R.string.melody_import_url_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    importingUrl?.let { url ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.melody_import_url)) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.melody_import_downloading))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            confirmButton = {}
        )
    }

    if (showProfileDialog) {
        ProfileSwitchDialog(
            profiles = profiles,
            activeProfileId = profile?.id,
            onSelect = { id ->
                viewModel.selectProfile(id)
                showProfileDialog = false
            },
            onAdd = {
                showProfileDialog = false
                showCreateProfileDialog = true
            },
            onDismiss = { showProfileDialog = false }
        )
    }

    if (showCreateProfileDialog) {
        CreateProfileDialog(
            onCreate = { name, emoji -> viewModel.createProfile(name, emoji) },
            onDismiss = { showCreateProfileDialog = false }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    name: String,
    count: Int,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (onRemove != null) showMenu = true }
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("📂", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                contentColor = MaterialTheme.colorScheme.primary,
                shape = CircleShape
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            if (onRemove != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.melody_remove_folder),
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        showMenu = false
                        onRemove()
                    }
                )
            }
        }
    }
}

@Composable
private fun ImportButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    labelRes: Int,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MelodyCard(
    viewModel: MelodyViewModel,
    info: net.exent.harmonisk.data.MelodyRepository.MelodyInfo,
    scoreSummary: MelodyScoreSummary?,
    difficulty: Float?,
    isPreviewing: Boolean,
    isExpanded: Boolean,
    onPlay: () -> Unit,
    onTogglePreview: () -> Unit,
    onToggleExpand: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
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
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(BrandPurple.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (info.isBundled) "🎵" else "🎶", style = MaterialTheme.typography.headlineSmall)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(info.name, style = MaterialTheme.typography.titleMedium)
                    if (difficulty != null) {
                        DifficultyBadge(difficulty = difficulty)
                    }
                }

                if (scoreSummary != null) {
                    LevelScoreBadge(
                        level = scoreSummary.level,
                        score = scoreSummary.score,
                        onClick = onToggleExpand
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }

                IconButton(onClick = onTogglePreview) {
                    Icon(
                        imageVector = if (isPreviewing) Icons.Filled.Close else Icons.Filled.MusicNote,
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
private fun LevelScoreBadge(level: Int, score: Float?, onClick: () -> Unit) {
    val tint = score?.let { scoreColor(it) } ?: MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.2f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = level.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = score?.let { formatScore(it) } ?: "–",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (score != null) tint else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
