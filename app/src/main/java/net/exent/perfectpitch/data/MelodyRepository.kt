package net.exent.perfectpitch.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load melody '${info.name}' (id=${info.id})", e)
            null
        }

        track?.let { cache[info.id] = it }
        return track
    }

    /** Stable melody ID derived from a user-supplied URI (uses full URI string, no hash collisions). */
    fun uriMelodyId(uri: Uri): String = "user_${uri}"

    /**
     * Load a melody from a user-selected file URI.
     */
    fun loadFromUri(uri: Uri, name: String): MelodyTrack? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val track = parser.parse(stream, name)
                cache[uriMelodyId(uri)] = track
                track
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load melody from URI: $uri", e)
            null
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
        } catch (e: Exception) {
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

    suspend fun removeImportedMelody(id: String) {
        importedMelodyStore.remove(id)
    }
}
