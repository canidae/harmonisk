package net.exent.perfectpitch.data

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.IOException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import net.exent.perfectpitch.R
import net.exent.perfectpitch.midi.MelodyTrack
import net.exent.perfectpitch.midi.MidiParser
import net.exent.perfectpitch.music.DifficultyCalculator

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
    private val parser = MidiParser()
    private val cache = object : LinkedHashMap<String, MelodyTrack>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, MelodyTrack>) = size > MAX_CACHE_ENTRIES
    }

    private val importedMelodyStore = JsonPreferenceStore<String, ImportedMelody>(
        dataStore = context.importedStore,
        keyName = "imported"
    )

    @Serializable
    data class ImportedMelody(
        val name: String,
        val uri: String
    )

    companion object {
        private const val MAX_CACHE_ENTRIES = 20
    }

    data class MelodyInfo(
        val id: String,
        val name: String,
        val isBundled: Boolean,
        val uri: Uri? = null,
        val rawResId: Int? = null
    )

    /** Bundled melodies from raw resources. */
    fun getBundledMelodies(): List<MelodyInfo> {
        return R.raw::class.java.fields.map { field ->
            val resId = field.getInt(null)
            MelodyInfo(field.name, resourceNameToDisplayName(field.name), true, rawResId = resId)
        }
    }

    /** Flow of user-imported melodies. */
    val importedMelodies: Flow<List<MelodyInfo>> = importedMelodyStore.flow.map { map ->
        map.map { (id, mel) -> MelodyInfo(id, mel.name, false, uri = Uri.parse(mel.uri)) }
    }

    private fun resourceNameToDisplayName(name: String): String {
        return name.split('_').joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Load and parse a melody by its info.
     */
    fun loadMelody(info: MelodyInfo): MelodyTrack? {
        cache[info.id]?.let { return it }

        val track = try {
            when {
                info.rawResId != null -> {
                    context.resources.openRawResource(info.rawResId).use { stream ->
                        parser.parse(stream, info.name)
                    }
                }
                info.uri != null -> {
                    context.contentResolver.openInputStream(info.uri)?.use { stream ->
                        parser.parse(stream, info.name)
                    }
                }
                else -> null
            }
         } catch (e: IOException) {
             Log.e(TAG, "Failed to load melody '${info.name}' (id=${info.id})", e)
             null
         }

         track?.let { cache[info.id] = it }
        return track
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
        }?.let { raw ->
            raw.removeSuffix(".mid")
                .removeSuffix(".midi")
                .removeSuffix(".MID")
                .removeSuffix(".MIDI")
                .replace('_', ' ')
                .trim()
                .ifEmpty { null }
        } ?: run {
            // Fallback: decode last path segment
            val rawPath = uri.lastPathSegment ?: return "Imported MIDI"
            android.net.Uri.decode(rawPath)
                .substringAfterLast('/')
                .removeSuffix(".mid")
                .removeSuffix(".midi")
                .removeSuffix(".MID")
                .removeSuffix(".MIDI")
                .replace('_', ' ')
                .trim()
                .ifEmpty { "Imported MIDI" }
        }
    }

    /**
     * Load a multi-track MIDI file from a URI. Returns one [MelodyTrack] per original MIDI track.
     * Useful for MIDI files with separate instrument tracks.
     */
    fun loadMultiTrackFromUri(uri: Uri, name: String): List<MelodyTrack> {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                parser.parseMultiTrack(stream, name)
            } ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to load multi-track MIDI from URI: $uri", e)
            emptyList()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load multi-track MIDI from URI: $uri", e)
            emptyList()
        }
    }

    suspend fun addImportedMelody(info: MelodyInfo) {
        val uriStr = info.uri?.toString() ?: return
        importedMelodyStore.set(info.id, ImportedMelody(info.name, uriStr))
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

    /**
     * Copy a content URI to a local file so it's available offline.
     * Returns the local file URI, or null if copying fails.
     */
    fun copyUriToLocal(uri: Uri, displayName: String): Uri? {
        val melodiesDir = java.io.File(context.filesDir, "melodies")
        if (!melodiesDir.exists()) melodiesDir.mkdirs()

        val safeName = displayName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        val filename = "${safeName}_${uri.hashCode().toLong() and 0xFFFFFFFFL}.mid"
        val targetFile = java.io.File(melodiesDir, filename)

        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Uri.fromFile(targetFile)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy URI $uri to local file", e)
            null
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to copy URI $uri to local file", e)
            null
        }
    }

    private fun deleteLocalFile(uri: Uri) {
        if (uri.scheme == "file") {
            val path = uri.path ?: return
            val file = java.io.File(path)
            if (file.exists()) file.delete()
        }
    }

    suspend fun removeImportedMelody(id: String) {
        val entry = importedMelodyStore.flow.first()[id]
        entry?.let { deleteLocalFile(Uri.parse(it.uri)) }
        importedMelodyStore.remove(id)
    }
}
