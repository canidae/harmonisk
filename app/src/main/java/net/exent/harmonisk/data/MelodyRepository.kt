package net.exent.harmonisk.data

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.exent.harmonisk.midi.AbcParser
import net.exent.harmonisk.midi.MelodyFileFormat
import net.exent.harmonisk.midi.MelodyFileParser
import net.exent.harmonisk.midi.MelodyFormat
import net.exent.harmonisk.midi.MelodyTrack
import net.exent.harmonisk.music.DifficultyCalculator

private val Context.importedStore: DataStore<Preferences> by preferencesDataStore(
    name = "imported_melodies"
)

private const val TAG = "MelodyRepository"

/**
 * Repository for managing MIDI melodies (bundled and user-imported).
 */
class MelodyRepository(
    private val context: Context,
    private val difficultyRepository: DifficultyRepository
) {
    private val cache = object : LinkedHashMap<String, MelodyTrack>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, MelodyTrack>) = size > MAX_CACHE_ENTRIES
    }
    private val multiTrackCache = object : LinkedHashMap<String, List<MelodyTrack>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<MelodyTrack>>) = size > MAX_CACHE_ENTRIES
    }
    private val cacheLock = Any()

    private val importedMelodyStore = JsonPreferenceStore<String, ImportedMelody>(
        dataStore = context.importedStore,
        keyName = "imported"
    )

    @Serializable
    data class ImportedMelody(
        val name: String,
        val uri: String,
        val folder: String? = null
    )

    companion object {
        private const val MAX_CACHE_ENTRIES = 20

        private val ZIP_MIME_TYPES = setOf(
            "application/zip",
            "application/x-zip",
            "application/x-zip-compressed",
            "multipart/x-zip"
        )

        /** Folder key for user-imported melodies. */
        const val IMPORTED_FOLDER = "imported"

        /** Asset directory holding bundled melodies, one subdirectory per folder. */
        const val MELODY_ASSETS_DIR = "melodies"

    }

    data class MelodyInfo(
        val id: String,
        val name: String,
        val isBundled: Boolean,
        val folder: String? = null,
        val uri: Uri? = null,
        val assetPath: String? = null
    )

    sealed interface ImportResult {
        data class Song(val info: MelodyInfo) : ImportResult
        data class Folder(val folder: String, val count: Int) : ImportResult
    }

    /** Bundled melodies from the assets/melodies directory tree. */
    fun getBundledMelodies(): List<MelodyInfo> {
        val result = mutableListOf<MelodyInfo>()
        val folders = context.assets.list(MELODY_ASSETS_DIR).orEmpty()
        for (folder in folders) {
            val folderPrefix = "$MELODY_ASSETS_DIR/$folder"
            val files = context.assets.list(folderPrefix).orEmpty()
                .filter { name -> MelodyNames.extensions.any { name.endsWith(it, true) } }
                .sorted()
            for (file in files) {
                val assetPath = "$folderPrefix/$file"
                val displayName = resourceNameToDisplayName(file.substringBeforeLast('.'))
                result.add(MelodyInfo(assetPath, displayName, true, folder = folder, assetPath = assetPath))
            }
        }
        return result
    }

    /** Flow of user-imported melodies. */
    val importedMelodies: Flow<List<MelodyInfo>> = importedMelodyStore.flow.map { map ->
        map.map { (id, mel) ->
            MelodyInfo(id, mel.name, false, folder = mel.folder ?: IMPORTED_FOLDER, uri = Uri.parse(mel.uri))
        }
    }

    private fun resourceNameToDisplayName(name: String): String {
        return name.split('_').joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Load and parse a melody by its info.
     */
    fun loadMelody(info: MelodyInfo): MelodyTrack? = synchronized(cacheLock) {
        cache[info.id]?.let { return it }

        val track = try {
            when {
                info.assetPath != null -> {
                    context.assets.open(info.assetPath).use { stream ->
                        MelodyFileParser.parse(stream, info.name)
                    }
                }
                info.uri != null -> {
                    context.contentResolver.openInputStream(info.uri)?.use { stream ->
                        MelodyFileParser.parse(stream, info.name)
                    }
                }
                else -> null
            }
         } catch (e: IOException) {
             Log.e(TAG, "Failed to load melody '${info.name}' (id=${info.id})", e)
             null
         }

         track?.let { cache[info.id] = it }
        track
    }

    /**
     * Load a melody preserving one [MelodyTrack] per original MIDI track (voice).
     * Unlike [loadMelody], tracks are not merged, so multi-voice files keep their
     * per-track identity.
     */
    fun loadMultiTrack(info: MelodyInfo): List<MelodyTrack> = synchronized(cacheLock) {
        multiTrackCache[info.id]?.let { return it }

        val tracks = try {
            when {
                info.assetPath != null -> {
                    context.assets.open(info.assetPath).use { stream ->
                        MelodyFileParser.parseMultiTrack(stream, info.name)
                    }
                }
                info.uri != null -> {
                    context.contentResolver.openInputStream(info.uri)?.use { stream ->
                        MelodyFileParser.parseMultiTrack(stream, info.name)
                    } ?: emptyList()
                }
                else -> emptyList()
            }
         } catch (e: IOException) {
             Log.e(TAG, "Failed to load multi-track melody '${info.name}' (id=${info.id})", e)
             emptyList()
         }

         if (tracks.isNotEmpty()) multiTrackCache[info.id] = tracks
        tracks
    }

    /** Stable melody ID derived from a user-supplied URI (uses full URI string, no hash collisions). */
    fun uriMelodyId(uri: Uri): String = "user_${uri}"

    /**
     * Resolve a content URI to a human-readable display name using ContentResolver metadata.
     * Falls back to extracting from the URI path if the provider doesn't expose DISPLAY_NAME.
     */
    fun resolveDisplayName(uri: Uri): String {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) cursor.getString(nameIdx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }?.let { raw -> MelodyNames.displayName(raw) } ?: run {
            // Fallback: decode last path segment
            val rawPath = uri.lastPathSegment ?: return "Imported song"
            MelodyNames.displayName(Uri.decode(rawPath).substringAfterLast('/'))
                ?: "Imported song"
        }
    }

    /** Drop cached parses so a re-imported file is never served from the previous copy. */
    private suspend fun evictCached(id: String) {
        synchronized(cacheLock) {
            cache.remove(id)
            multiTrackCache.remove(id)
        }
        difficultyRepository.remove(id)
    }

    suspend fun addImportedMelody(info: MelodyInfo) {
        val uriStr = info.uri?.toString() ?: return
        evictCached(info.id)
        importedMelodyStore.set(info.id, ImportedMelody(info.name, uriStr, info.folder))
    }

    suspend fun importFile(uri: Uri): ImportResult? = withContext(Dispatchers.IO) {
        try {
            val displayName = resolveDisplayName(uri)
            if (isZip(uri, displayName)) {
                return@withContext context.contentResolver.openInputStream(uri)?.use {
                    extractZip(displayName, it)
                }?.let { ImportResult.Folder(it.first, it.second) }
            }
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
            importBytes(bytes, displayName, uri.hashCode(), uriMelodyId(uri))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import melody from $uri", e)
            null
        }
    }

    private fun isZip(uri: Uri, displayName: String): Boolean {
        val mimeType = context.contentResolver.getType(uri)?.substringBefore(';') ?: ""
        return mimeType in ZIP_MIME_TYPES || displayName.endsWith(".zip", true)
    }

    suspend fun importFromUrl(url: String): ImportResult? = withContext(Dispatchers.IO) {
        try {
            val download = MelodyUrlImporter.download(url)
            importBytes(download.bytes, download.name, url.hashCode(), null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import melody from $url", e)
            null
        }
    }

    private suspend fun importBytes(
        bytes: ByteArray,
        displayName: String,
        seed: Int,
        id: String?
    ): ImportResult? {
        if (MelodyUrlImporter.isZip(bytes)) {
            return extractZip(displayName, ByteArrayInputStream(bytes))
                ?.let { ImportResult.Folder(it.first, it.second) }
        }
        val songs = if (MelodyFileFormat.detect(bytes) == MelodyFormat.ABC) {
            splitPlayableSongs(String(bytes, Charsets.UTF_8), displayName)
        } else {
            emptyList()
        }
        if (songs.size > 1) {
            return extractAbc(displayName, songs)?.let { ImportResult.Folder(it.first, it.second) }
        }
        if (MelodyFileParser.parseMultiTrack(ByteArrayInputStream(bytes), displayName).isEmpty()) return null
        val fileUri = writeMelodyFile(seed, displayName, bytes)
        val info = MelodyInfo(id ?: uriMelodyId(fileUri), displayName, false, folder = IMPORTED_FOLDER, uri = fileUri)
        addImportedMelody(info)
        return ImportResult.Song(info)
    }

    private suspend fun extractZip(displayName: String, input: InputStream): Pair<String, Int>? {
        val folder = MelodyFolderImporter.folderName(displayName)
        val dir = MelodyFolderImporter.directory(context.filesDir, folder)
        val melodies = MelodyFolderImporter.extract(input, dir).flatMap { melody ->
            if (melody.file.extension.equals("abc", true)) splitAbcFile(melody, dir) else listOf(melody)
        }
        return registerFolder(folder, melodies)
    }

    private suspend fun extractAbc(displayName: String, songs: List<AbcParser.Song>): Pair<String, Int>? {
        val folder = MelodyFolderImporter.folderName(displayName)
        val dir = MelodyFolderImporter.directory(context.filesDir, folder)
        return registerFolder(folder, writeAbcSongs(dir, songs))
    }

    private fun splitPlayableSongs(text: String, fallbackName: String): List<AbcParser.Song> =
        AbcParser.splitSongs(text, fallbackName).filter { song ->
            AbcParser.parse(song.text, song.title).any { it.notes.isNotEmpty() }
        }

    private fun splitAbcFile(
        melody: MelodyFolderImporter.ExtractedMelody,
        dir: File
    ): List<MelodyFolderImporter.ExtractedMelody> {
        val songs = splitPlayableSongs(melody.file.readText(), melody.name)
        if (songs.size < 2) return listOf(melody)
        melody.file.delete()
        return writeAbcSongs(dir, songs)
    }

    private fun writeAbcSongs(
        dir: File,
        songs: List<AbcParser.Song>
    ): List<MelodyFolderImporter.ExtractedMelody> {
        dir.mkdirs()
        val used = mutableSetOf<String>()
        return songs.map { song ->
            val base = MelodyFolderImporter.safeFileName(song.title)
            var fileName = "$base.abc"
            var suffix = 2
            while (!used.add(fileName.lowercase())) {
                fileName = "${base}_$suffix.abc"
                suffix++
            }
            val file = File(dir, fileName)
            file.writeText(song.text)
            MelodyFolderImporter.ExtractedMelody(song.title, file)
        }
    }

    private suspend fun registerFolder(
        folder: String,
        melodies: List<MelodyFolderImporter.ExtractedMelody>
    ): Pair<String, Int>? {
        if (melodies.isEmpty()) return null
        for (melody in melodies) {
            val fileUri = Uri.fromFile(melody.file)
            val id = uriMelodyId(fileUri)
            evictCached(id)
            importedMelodyStore.set(id, ImportedMelody(melody.name, fileUri.toString(), folder))
        }
        return folder to melodies.size
    }

    suspend fun getDifficulty(info: MelodyInfo): Float {
        val cached = difficultyRepository.get(info.id)
        if (cached != null && cached.algorithmVersion == DifficultyCalculator.ALGORITHM_VERSION) {
            return cached.difficulty
        }
        val track = loadMelody(info) ?: return 0.0f
        val difficulty = DifficultyCalculator.calculate(track)
        difficultyRepository.set(
            info.id,
            DifficultyRepository.DifficultyData(difficulty, DifficultyCalculator.ALGORITHM_VERSION)
        )
        return difficulty
    }

    /** Write [bytes] into the local melody directory, keyed by [seed] so a re-import replaces its file. */
    private fun writeMelodyFile(seed: Int, displayName: String, bytes: ByteArray): Uri {
        val melodiesDir = File(context.filesDir, "melodies")
        if (!melodiesDir.exists()) melodiesDir.mkdirs()
        val safeName = MelodyNames.safeFileName(displayName)
        val extension = MelodyFileFormat.detect(bytes).extension
        val targetFile = File(melodiesDir, "${safeName}_${seed.toLong() and 0xFFFFFFFFL}.$extension")
        targetFile.writeBytes(bytes)
        return Uri.fromFile(targetFile)
    }

    private fun deleteLocalFile(uri: Uri) {
        if (uri.scheme == "file") {
            val path = uri.path ?: return
            val file = File(path)
            if (file.exists()) file.delete()
        }
    }

    suspend fun removeImportedMelody(id: String) {
        evictCached(id)
        val entry = importedMelodyStore.flow.first()[id]
        entry?.let { deleteLocalFile(Uri.parse(it.uri)) }
        importedMelodyStore.remove(id)
    }

    /** Remove every melody imported into [folder] and delete its extracted files. Returns the removed ids. */
    suspend fun removeImportedFolder(folder: String): List<String> {
        val entries = importedMelodyStore.flow.first().filterValues { it.folder == folder }
        for ((id, melody) in entries) {
            evictCached(id)
            deleteLocalFile(Uri.parse(melody.uri))
            importedMelodyStore.remove(id)
        }
        MelodyFolderImporter.directory(context.filesDir, folder).deleteRecursively()
        return entries.keys.toList()
    }
}
