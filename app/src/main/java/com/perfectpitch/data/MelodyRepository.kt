package com.perfectpitch.data

import android.content.Context
import android.net.Uri
import com.perfectpitch.midi.MelodyTrack
import com.perfectpitch.midi.MidiParser

/**
 * Repository for managing MIDI melodies (bundled and user-imported).
 */
class MelodyRepository(
    private val context: Context
) {
    private val parser = MidiParser()
    private val cache = mutableMapOf<String, MelodyTrack>()

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
    fun getAvailableMelodies(): List<MelodyInfo> {
        return getBundledMelodies()
    }

    private fun getBundledMelodies(): List<MelodyInfo> {
        return listOf(
            MelodyInfo("scale_c_major", "C Major Scale", true, rawResId = getRawResId("scale_c_major")),
            MelodyInfo("twinkle_twinkle", "Twinkle Twinkle Little Star", true, rawResId = getRawResId("twinkle_twinkle")),
            MelodyInfo("ode_to_joy", "Ode to Joy", true, rawResId = getRawResId("ode_to_joy"))
        ).filter { it.rawResId != null && it.rawResId != 0 }
    }

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
            null
        }

        track?.let { cache[info.id] = it }
        return track
    }

    /**
     * Load a melody from a user-selected file URI.
     */
    fun loadFromUri(uri: Uri, name: String): MelodyTrack? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val track = parser.parse(stream, name)
                val id = "user_${uri.hashCode()}"
                cache[id] = track
                track
            }
        } catch (e: Exception) {
            null
        }
    }
}
