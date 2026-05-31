package com.perfectpitch.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.perfectpitch.midi.MelodyTrack
import com.perfectpitch.midi.MidiParser

private const val TAG = "MelodyRepository"

/**
 * Repository for managing MIDI melodies (bundled and user-imported).
 */
class MelodyRepository(
    private val context: Context
) {
    private val parser = MidiParser()
    private val cache = object : LinkedHashMap<String, MelodyTrack>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, MelodyTrack>) = size > MAX_CACHE_ENTRIES
    }

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

    /**
     * Get list of available melodies (bundled + user-imported).
     */
    fun getAvailableMelodies(): List<MelodyInfo> = listOf(
        MelodyInfo("scale_c_major", "C Major Scale", true, rawResId = getRawResId("scale_c_major")),
        MelodyInfo("twinkle_twinkle", "Twinkle Twinkle Little Star", true, rawResId = getRawResId("twinkle_twinkle")),
        MelodyInfo("ode_to_joy", "Ode to Joy", true, rawResId = getRawResId("ode_to_joy"))
    ).filter { it.rawResId != null && it.rawResId != 0 }

    private fun getRawResId(name: String): Int? {
        val resId = context.resources.getIdentifier(name, "raw", context.packageName)
        return if (resId != 0) resId else null
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
}
