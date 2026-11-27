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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import net.exent.harmonisk.R
import net.exent.harmonisk.data.MelodyRepository
import net.exent.harmonisk.data.PlaylistRepository
import net.exent.harmonisk.ui.components.BrandTopBar
import net.exent.harmonisk.ui.components.brandBackground
import net.exent.harmonisk.ui.profile.ProfileButton
import net.exent.harmonisk.ui.profile.ProfileDialogs
import net.exent.harmonisk.ui.profile.rememberProfileDialogState
import net.exent.harmonisk.ui.theme.AdventureGold
import net.exent.harmonisk.ui.theme.BrandPurple
import net.exent.harmonisk.ui.theme.LocalHarmoniskDarkTheme
import net.exent.harmonisk.ui.theme.MetronomeBlue
import net.exent.harmonisk.ui.theme.MetronomeBlueLight
import net.exent.harmonisk.ui.theme.Purple80
import net.exent.harmonisk.ui.theme.PurpleLight
import net.exent.harmonisk.ui.theme.YellowLight
import net.exent.harmonisk.ui.utils.folderDisplayName
import net.exent.harmonisk.ui.utils.formatScore
import net.exent.harmonisk.ui.utils.scoreColor
import kotlinx.coroutines.launch
import kotlin.math.abs

private val IMPORT_MIME_TYPES = arrayOf("*/*")

private const val PLAYLISTS_FOLDER = "playlists"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MelodyScreen(
    onBack: () -> Unit,
    onStartPlay: () -> Unit = {},
    viewModel: MelodyViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val isPreviewPlaying by viewModel.isPreviewPlaying.collectAsStateWithLifecycle()
    val scores by viewModel.allMelodyScores.collectAsStateWithLifecycle()
    val difficulties by viewModel.allMelodyDifficulties.collectAsStateWithLifecycle()
    var previewingMelody by remember { mutableStateOf<String?>(null) }
    var expandedMelodyId by remember { mutableStateOf<String?>(null) }
    var currentFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var currentSubfolder by rememberSaveable { mutableStateOf<String?>(null) }
    var currentPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingPlaylist by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<PlaylistRepository.Playlist?>(null) }
    var addingSongs by remember { mutableStateOf(false) }
    var addingToPlaylistSong by remember { mutableStateOf<MelodyRepository.MelodyInfo?>(null) }
    var playlistToDelete by remember { mutableStateOf<PlaylistRepository.Playlist?>(null) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlText by rememberSaveable { mutableStateOf("") }
    var importingUrl by remember { mutableStateOf<String?>(null) }
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val profile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val instrument by viewModel.currentInstrument.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val melodyLevels by viewModel.allMelodyLevels.collectAsStateWithLifecycle()
    val melodyOverrides by viewModel.allMelodyOverrides.collectAsStateWithLifecycle()
    val profileDialogs = rememberProfileDialogState()

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
            if (previewingMelody != null) viewModel.stopPreview()
        }
    }

    BackHandler(enabled = currentFolder != null) {
        when {
            currentPlaylistId != null -> {
                currentPlaylistId = null
                addingSongs = false
            }
            currentSubfolder != null -> currentSubfolder = null
            else -> currentFolder = null
        }
    }

    val scope = rememberCoroutineScope()
    fun importUrl(url: String) {
        if (importingUrl != null) return
        importingUrl = url
        scope.launch {
            when (val result = viewModel.importFromUrl(url)) {
                null -> Toast.makeText(
                    context,
                    resources.getString(R.string.melody_failed_load_url),
                    Toast.LENGTH_SHORT
                ).show()
                is MelodyRepository.ImportResult.Song -> {
                    currentFolder = MelodyRepository.IMPORTED_FOLDER
                    currentSubfolder = null
                    Toast.makeText(context, resources.getString(R.string.melody_import_success), Toast.LENGTH_SHORT).show()
                }
                is MelodyRepository.ImportResult.Folder -> {
                    currentFolder = MelodyRepository.IMPORTED_FOLDER
                    currentSubfolder = result.folder
                    Toast.makeText(context, resources.getString(R.string.melody_import_folder_success), Toast.LENGTH_SHORT).show()
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
                            Toast.makeText(context, resources.getString(R.string.melody_import_success), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, resources.getString(R.string.melody_failed_load_melody), Toast.LENGTH_SHORT).show()
                        }
                    }
                    is MelodyRepository.ImportResult.Folder -> {
                        currentFolder = MelodyRepository.IMPORTED_FOLDER
                        currentSubfolder = result.folder
                        Toast.makeText(context, resources.getString(R.string.melody_import_folder_success), Toast.LENGTH_SHORT).show()
                    }
                    null -> Toast.makeText(context, resources.getString(R.string.melody_failed_load_midi), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val melodies by viewModel.sortedMelodies.collectAsStateWithLifecycle()
    val importedMelodies = melodies.filter { !it.isBundled }
    val looseImported = importedMelodies.filter { it.folder == MelodyRepository.IMPORTED_FOLDER }
    val importedSubfolders = importedMelodies
        .filter { it.folder != null && it.folder != MelodyRepository.IMPORTED_FOLDER }
        .groupBy { it.folder.orEmpty() }
    val bundledFolders = melodies.filter { it.isBundled && it.folder != null }.groupBy { it.folder.orEmpty() }
    val looseBundled = melodies.filter { it.isBundled && it.folder == null }
    val melodyById = melodies.associateBy { it.id }
    val currentPlaylist = currentPlaylistId?.let { id -> playlists.firstOrNull { it.id == id } }

    val inImportedFolder = currentFolder == MelodyRepository.IMPORTED_FOLDER
    val browsingImported = inImportedFolder && currentSubfolder == null
    val currentFolderSongs = when {
        currentSubfolder != null -> importedSubfolders[currentSubfolder].orEmpty()
        inImportedFolder -> looseImported
        currentFolder != null -> bundledFolders[currentFolder].orEmpty()
        else -> emptyList()
    }

    fun playSong(info: MelodyRepository.MelodyInfo) {
        viewModel.stopPreview()
        previewingMelody = null
        viewModel.endPlaylistSession()
        if (viewModel.loadMelody(info)) {
            onStartPlay()
        } else {
            Toast.makeText(context, resources.getString(R.string.melody_failed_load_melody), Toast.LENGTH_SHORT).show()
        }
    }

    val songCard: @Composable (MelodyRepository.MelodyInfo) -> Unit = { info ->
        MelodyCard(
            viewModel = viewModel,
            info = info,
            scoreSummary = scores[info.id],
            difficulty = difficulties[info.id],
            isPreviewing = previewingMelody == info.name && isPreviewPlaying,
            isExpanded = expandedMelodyId == info.id,
            onPlay = { playSong(info) },
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
            },
            onAddToPlaylist = { addingToPlaylistSong = info },
            onResetOverrides = if (info.id in melodyOverrides) {
                { viewModel.resetMelodyOverrides(info.id) }
            } else null
        )
    }

    fun movePlaylistSong(playlistId: String, from: Int, to: Int) {
        val ids = playlists.firstOrNull { it.id == playlistId }?.melodyIds?.toMutableList() ?: return
        if (from !in ids.indices || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        viewModel.setPlaylistMelodies(playlistId, ids)
    }

    val addSongsPlaylist = currentPlaylist
    if (addingSongs && addSongsPlaylist != null) {
        BackHandler { addingSongs = false }
        AddSongsScreen(
            melodies = melodies,
            existingIds = addSongsPlaylist.melodyIds.toSet(),
            onAdd = { ids ->
                viewModel.setPlaylistMelodies(addSongsPlaylist.id, addSongsPlaylist.melodyIds + ids)
                addingSongs = false
            },
            onBack = { addingSongs = false }
        )
        return
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .brandBackground(),
        containerColor = Color.Transparent,
        bottomBar = {
            when {
                browsingImported -> {
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
                                    setPrompt(resources.getString(R.string.melody_import_qr_prompt))
                                    setBeepEnabled(false)
                                }
                            )
                        }
                    }
                }
                currentFolder == PLAYLISTS_FOLDER && currentPlaylist == null -> {
                    Button(
                        onClick = { creatingPlaylist = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .height(52.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.melody_playlist_new))
                    }
                }
                currentPlaylist != null -> {
                    val playlist = currentPlaylist
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ImportButton(
                            Modifier.weight(1f).fillMaxHeight(),
                            Icons.Default.PlayArrow,
                            R.string.melody_playlist_play,
                            enabled = playlist.melodyIds.isNotEmpty()
                        ) {
                            scope.launch {
                                if (viewModel.startPlaylist(playlist)) {
                                    onStartPlay()
                                } else {
                                    Toast.makeText(context, resources.getString(R.string.melody_failed_load_melody), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        ImportButton(Modifier.weight(1f).fillMaxHeight(), Icons.Default.Add, R.string.melody_playlist_add_songs) {
                            addingSongs = true
                        }
                    }
                }
            }
        },
        topBar = {
            BrandTopBar(
                title = currentPlaylist?.name
                    ?: currentSubfolder?.let { folderDisplayName(context, it) }
                    ?: currentFolder?.let { folder ->
                        if (folder == PLAYLISTS_FOLDER) stringResource(R.string.melody_playlists)
                        else folderDisplayName(context, folder)
                    }
                    ?: stringResource(R.string.melody_title),
                onBack = {
                    when {
                        currentPlaylistId != null -> {
                            currentPlaylistId = null
                            addingSongs = false
                        }
                        currentSubfolder != null -> currentSubfolder = null
                        currentFolder != null -> currentFolder = null
                        else -> onBack()
                    }
                },
                actions = {
                    profile?.let {
                        ProfileButton(it.name, it.emoji, onClick = { profileDialogs.open(false) })
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
                currentPlaylist != null -> {
                    val playlist = currentPlaylist
                    val entries = playlist.melodyIds.mapNotNull { id -> melodyById[id] }
                    val listState = rememberLazyListState()
                    val dragEdge = with(LocalDensity.current) { 80.dp.toPx() }
                    var draggingSongId by remember(playlist.id) { mutableStateOf<String?>(null) }
                    var dragOffset by remember(playlist.id) { mutableStateOf(0f) }
                    var dragCenter by remember(playlist.id) { mutableStateOf(0f) }
                    LaunchedEffect(draggingSongId) {
                        while (draggingSongId != null) {
                            val viewport = listState.layoutInfo
                            val step = when {
                                dragCenter < viewport.viewportStartOffset + dragEdge -> -12f
                                dragCenter > viewport.viewportEndOffset - dragEdge -> 12f
                                else -> 0f
                            }
                            val consumed = listState.scrollBy(step)
                            dragOffset += consumed
                            dragCenter += consumed
                            withFrameNanos {}
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (entries.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.melody_playlist_empty),
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(32.dp)
                                )
                            }
                        }
                        itemsIndexed(entries, key = { _, info -> info.id }) { index, info ->
                            PlaylistSongRow(
                                name = info.name,
                                isBundled = info.isBundled,
                                difficulty = difficulties[info.id],
                                level = melodyLevels[info.id] ?: 1,
                                score = scores[info.id]?.score,
                                dragging = draggingSongId == info.id,
                                dragOffset = if (draggingSongId == info.id) dragOffset else 0f,
                                onPlay = { playSong(info) },
                                onDragStart = {
                                    draggingSongId = info.id
                                    dragOffset = 0f
                                    dragCenter = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.index == index }
                                        ?.let { it.offset + it.size / 2f } ?: 0f
                                },
                                onDrag = {
                                    dragOffset += it
                                    dragCenter += it
                                },
                                onDragEnd = {
                                    val from = entries.indexOfFirst { it.id == draggingSongId }
                                    val to = listState.layoutInfo.visibleItemsInfo
                                        .minByOrNull { abs(it.offset + it.size / 2f - dragCenter) }
                                        ?.index ?: from
                                    if (to != from) movePlaylistSong(playlist.id, from, to)
                                    draggingSongId = null
                                    dragOffset = 0f
                                },
                                onRemove = { viewModel.setPlaylistMelodies(playlist.id, playlist.melodyIds - info.id) },
                                onResetOverrides = if (info.id in melodyOverrides) {
                                    { viewModel.resetMelodyOverrides(info.id) }
                                } else null
                            )
                        }
                    }
                }
                currentFolder == PLAYLISTS_FOLDER -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (playlists.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.melody_playlist_no_playlists),
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(32.dp)
                                )
                            }
                        }
                        items(playlists, key = { it.id }) { playlist ->
                            PlaylistCard(
                                name = playlist.name,
                                count = playlist.melodyIds.size,
                                onClick = { currentPlaylistId = playlist.id },
                                onRename = { playlistToRename = playlist },
                                onDelete = { playlistToDelete = playlist }
                            )
                        }
                    }
                }
                currentFolder == null -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FolderCard(
                                name = stringResource(R.string.melody_playlists),
                                count = playlists.size,
                                icon = "\uD83C\uDFBC",
                                accent = folderAccent(PLAYLISTS_FOLDER),
                                onClick = { currentFolder = PLAYLISTS_FOLDER }
                            )
                        }
                        item {
                            FolderCard(
                                name = folderDisplayName(context, MelodyRepository.IMPORTED_FOLDER),
                                count = importedMelodies.size,
                                icon = "\uD83D\uDCE5",
                                accent = folderAccent(MelodyRepository.IMPORTED_FOLDER),
                                onClick = { currentFolder = MelodyRepository.IMPORTED_FOLDER }
                            )
                        }
                        items(bundledFolders.keys.sortedBy { folderDisplayName(context, it) }) { folder ->
                            FolderCard(
                                name = folderDisplayName(context, folder),
                                count = bundledFolders[folder]?.size ?: 0,
                                icon = "\uD83D\uDCC2",
                                accent = folderAccent(folder),
                                onClick = { currentFolder = folder }
                            )
                        }
                        items(looseBundled) { info -> songCard(info) }
                    }
                }
                currentFolderSongs.isNotEmpty() || inImportedFolder -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (browsingImported) {
                            items(importedSubfolders.keys.sortedBy { folderDisplayName(context, it) }) { folder ->
                                FolderCard(
                                    name = folderDisplayName(context, folder),
                                    count = importedSubfolders[folder]?.size ?: 0,
                                    icon = "\uD83D\uDCC2",
                                    accent = folderAccent(folder),
                                    onClick = { currentSubfolder = folder },
                                    onRemove = { viewModel.removeImportedFolder(folder) }
                                )
                            }
                        }
                        items(currentFolderSongs) { info -> songCard(info) }
                        if (browsingImported && currentFolderSongs.isEmpty() && importedSubfolders.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.melody_no_imported),
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

    if (creatingPlaylist) {
        PlaylistNameDialog(
            title = stringResource(R.string.melody_playlist_new),
            initial = "",
            confirmLabel = stringResource(R.string.melody_playlist_create),
            onDismiss = { creatingPlaylist = false },
            onConfirm = { name ->
                creatingPlaylist = false
                viewModel.createPlaylist(name) { id ->
                    currentPlaylistId = id
                    addingSongs = true
                }
            }
        )
    }

    playlistToRename?.let { playlist ->
        PlaylistNameDialog(
            title = stringResource(R.string.melody_playlist_rename),
            initial = playlist.name,
            confirmLabel = stringResource(R.string.melody_playlist_rename),
            onDismiss = { playlistToRename = null },
            onConfirm = { name ->
                viewModel.renamePlaylist(playlist.id, name)
                playlistToRename = null
            }
        )
    }

    addingToPlaylistSong?.let { song ->
        AddToPlaylistDialog(
            song = song,
            playlists = playlists,
            onToggle = { playlist, add ->
                viewModel.setPlaylistMelodies(
                    playlist.id,
                    if (add) playlist.melodyIds + song.id else playlist.melodyIds - song.id
                )
            },
            onDismiss = { addingToPlaylistSong = null }
        )
    }

    playlistToDelete?.let { playlist ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text(stringResource(R.string.melody_playlist_delete)) },
            text = { Text(stringResource(R.string.melody_playlist_delete_confirm, playlist.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePlaylist(playlist.id)
                        playlistToDelete = null
                    }
                ) {
                    Text(
                        stringResource(R.string.melody_playlist_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
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

    ProfileDialogs(
        state = profileDialogs,
        profiles = profiles,
        activeProfileId = profile?.id,
        initialInstrument = instrument,
        onSelect = viewModel::selectProfile,
        onCreate = viewModel::createProfile
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    name: String,
    count: Int,
    icon: String,
    accent: Color,
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
                    .background(accent.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, style = MaterialTheme.typography.headlineSmall)
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
private fun folderAccent(folder: String): Color {
    val darkTheme = LocalHarmoniskDarkTheme.current
    return when (folder) {
        PLAYLISTS_FOLDER -> if (darkTheme) AdventureGold else YellowLight
        MelodyRepository.IMPORTED_FOLDER -> if (darkTheme) MetronomeBlue else MetronomeBlueLight
        else -> if (darkTheme) Purple80 else PurpleLight
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistCard(
    name: String,
    count: Int,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val accent = folderAccent(PLAYLISTS_FOLDER)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
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
                    .background(accent.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("\uD83C\uDFBC", style = MaterialTheme.typography.headlineSmall)
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
            DropdownMenuItem(
                text = { Text(stringResource(R.string.melody_playlist_rename)) },
                onClick = {
                    showMenu = false
                    onRename()
                }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.melody_playlist_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                },
                onClick = {
                    showMenu = false
                    onDelete()
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistSongRow(
    name: String,
    isBundled: Boolean,
    difficulty: Float?,
    level: Int,
    score: Float?,
    dragging: Boolean,
    dragOffset: Float,
    onPlay: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onRemove: () -> Unit,
    onResetOverrides: (() -> Unit)?
) {
    var showMenu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffset
                shadowElevation = if (dragging) 12f else 0f
            }
            .combinedClickable(onClick = onPlay, onLongClick = { showMenu = true }),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(BrandPurple.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(if (isBundled) "🎵" else "🎶", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (difficulty != null) {
                    DifficultyBadge(difficulty = difficulty)
                }
            }
            LevelScoreBadge(level = level, score = score)
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDrag = { change, amount ->
                                change.consume()
                                onDrag(amount.y)
                            },
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragEnd
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = stringResource(R.string.melody_playlist_reorder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            onResetOverrides?.let { reset ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.melody_reset_overrides)) },
                    onClick = {
                        showMenu = false
                        reset()
                    }
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.melody_remove_song),
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

@Composable
private fun AddToPlaylistDialog(
    song: MelodyRepository.MelodyInfo,
    playlists: List<PlaylistRepository.Playlist>,
    onToggle: (PlaylistRepository.Playlist, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.melody_playlist_add_to)) },
        text = {
            if (playlists.isEmpty()) {
                Text(stringResource(R.string.melody_playlist_no_playlists))
            } else {
                Column {
                    Text(song.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            val inPlaylist = song.id in playlist.melodyIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggle(playlist, !inPlaylist) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = inPlaylist, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Text(playlist.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.melody_playlist_done)) }
        }
    )
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit: () -> Unit = {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) onConfirm(trimmed)
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.melody_playlist_name)) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = submit) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

private data class SongGroup(
    val key: String,
    val title: String,
    val songs: List<MelodyRepository.MelodyInfo>
)

@Composable
private fun AddSongsScreen(
    melodies: List<MelodyRepository.MelodyInfo>,
    existingIds: Set<String>,
    onAdd: (List<String>) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var selected by remember { mutableStateOf(setOf<String>()) }
    val groups = remember(melodies, resources) {
        melodies.groupBy { it.isBundled to it.folder }
            .map { (key, songs) ->
                val (bundled, folder) = key
                SongGroup(
                    key = "$bundled:${folder ?: ""}",
                    title = folder?.let { folderDisplayName(context, it) }
                        ?: resources.getString(R.string.adventure_folder_other),
                    songs = songs.sortedBy { it.name.lowercase() }
                )
            }
            .sortedBy { it.title.lowercase() }
    }
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .brandBackground(),
        containerColor = Color.Transparent,
        topBar = {
            BrandTopBar(
                title = stringResource(R.string.melody_playlist_add_songs),
                subtitle = stringResource(R.string.melody_playlist_selected, selected.size),
                onBack = onBack
            )
        },
        bottomBar = {
            Button(
                onClick = { onAdd(melodies.map { it.id }.filter { it in selected }) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .height(52.dp)
            ) {
                Text(stringResource(R.string.melody_playlist_add))
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(groups, key = { it.key }) { group ->
                AddSongsGroup(
                    group = group,
                    existingIds = existingIds,
                    selectedIds = selected,
                    onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
                    onSetGroup = { checked ->
                        val ids = group.songs.map { it.id }.filter { it !in existingIds }.toSet()
                        selected = if (checked) selected + ids else selected - ids
                    }
                )
            }
        }
    }
}

@Composable
private fun AddSongsGroup(
    group: SongGroup,
    existingIds: Set<String>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onSetGroup: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    val checkedCount = group.songs.count { it.id in existingIds || it.id in selectedIds }
    val allChecked = checkedCount == group.songs.size
    val state = when {
        allChecked -> ToggleableState.On
        checkedCount > 0 -> ToggleableState.Indeterminate
        else -> ToggleableState.Off
    }
    val hasSelectable = group.songs.any { it.id !in existingIds }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
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
                    onClick = { onSetGroup(!allChecked) },
                    enabled = hasSelectable
                )
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = pluralStringResource(R.plurals.adventure_song_count, group.songs.size, group.songs.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                group.songs.forEach { song ->
                    val existing = song.id in existingIds
                    val isSelected = existing || song.id in selectedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !existing) { onToggle(song.id) }
                            .padding(start = 34.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = null,
                            enabled = !existing
                        )
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = song.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isSelected) 1f else 0.8f),
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

@Composable
private fun ImportButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    labelRes: Int,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
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
    onToggleExpand: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onResetOverrides: (() -> Unit)?
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
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.melody_playlist_add_to)) },
                    onClick = {
                        showMenu = false
                        onAddToPlaylist()
                    }
                )
                onResetOverrides?.let { reset ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.melody_reset_overrides)) },
                        onClick = {
                            showMenu = false
                            reset()
                        }
                    )
                }
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
private fun LevelScoreBadge(level: Int, score: Float?, onClick: (() -> Unit)? = null) {
    val darkTheme = LocalHarmoniskDarkTheme.current
    val tint = score?.let { scoreColor(it, darkTheme) } ?: MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.2f))
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
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
