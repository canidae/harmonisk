package com.perfectpitch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.perfectpitch.audio.AudioDeviceMonitor
import com.perfectpitch.audio.AudioInputDeviceType
import com.perfectpitch.audio.AudioOutputDeviceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.audioDelayStore: DataStore<Preferences> by preferencesDataStore(
    name = "audio_delays"
)

/**
 * Stores calibrated audio round-trip delay and output/input split per device pair.
 * Output and input delays are derived: `output = total * split`, `input = total * (1 - split)`.
 */
class AudioDelayRepository(
    private val context: Context,
    private val audioDeviceMonitor: AudioDeviceMonitor
) {
    companion object {
        private val PROFILES_KEY = stringPreferencesKey("delay_profiles_json")

        const val DEFAULT_OUTPUT_SPLIT = 0.9f

        fun defaultOutputDelay(type: AudioOutputDeviceType): Int = when (type) {
            AudioOutputDeviceType.SPEAKER -> 0
            AudioOutputDeviceType.WIRED -> 0
            AudioOutputDeviceType.BLUETOOTH -> 150
        }

        fun defaultInputDelay(type: AudioInputDeviceType): Int = when (type) {
            AudioInputDeviceType.BUILTIN -> 0
            AudioInputDeviceType.WIRED -> 0
            AudioInputDeviceType.BLUETOOTH -> 200
        }
    }

    data class DelayProfile(
        val totalDelayMs: Int,
        val splitPercent: Float
    ) {
        val outputDelayMs: Int get() = (totalDelayMs * splitPercent).toInt()
        val inputDelayMs: Int get() = totalDelayMs - outputDelayMs
    }

    private val profilesFlow: Flow<Map<String, DelayProfile>> =
        context.audioDelayStore.data.map { prefs ->
            parseProfiles(prefs[PROFILES_KEY])
        }

    val activeOutputDelay: Flow<Int> = combine(
        audioDeviceMonitor.currentDevice,
        profilesFlow
    ) { device, profiles ->
        profiles[device.deviceKey]?.outputDelayMs ?: defaultOutputDelay(device.type)
    }

    val activeInputDelay: Flow<Int> = combine(
        audioDeviceMonitor.currentInputDevice,
        profilesFlow
    ) { device, profiles ->
        profiles[device.deviceKey]?.inputDelayMs ?: defaultInputDelay(device.type)
    }

    val activeTotalDelay: Flow<Int> = combine(
        audioDeviceMonitor.currentDevice,
        profilesFlow
    ) { device, profiles ->
        profiles[device.deviceKey]?.totalDelayMs ?: 0
    }

    val activeSplitPercent: Flow<Float> = combine(
        audioDeviceMonitor.currentDevice,
        profilesFlow
    ) { device, profiles ->
        profiles[device.deviceKey]?.splitPercent ?: DEFAULT_OUTPUT_SPLIT
    }

    val allProfiles: Flow<Map<String, DelayProfile>> = profilesFlow

    fun getProfile(deviceKey: String): Flow<DelayProfile?> =
        profilesFlow.map { it[deviceKey] }

    fun isCalibrated(deviceKey: String): Flow<Boolean> =
        profilesFlow.map { it.containsKey(deviceKey) }

    suspend fun saveCalibration(
        deviceKey: String,
        totalDelayMs: Int,
        splitPercent: Float
    ) {
        context.audioDelayStore.edit { prefs ->
            val current = parseProfiles(prefs[PROFILES_KEY]).toMutableMap()
            current[deviceKey] = DelayProfile(
                totalDelayMs = totalDelayMs.coerceIn(0, 1000),
                splitPercent = splitPercent.coerceIn(0f, 1f)
            )
            prefs[PROFILES_KEY] = serializeProfiles(current)
        }
    }

    suspend fun setTotalDelay(deviceKey: String, totalDelayMs: Int) {
        context.audioDelayStore.edit { prefs ->
            val current = parseProfiles(prefs[PROFILES_KEY]).toMutableMap()
            val existing = current[deviceKey]
            val split = existing?.splitPercent ?: DEFAULT_OUTPUT_SPLIT
            current[deviceKey] = DelayProfile(
                totalDelayMs = totalDelayMs.coerceIn(0, 1000),
                splitPercent = split
            )
            prefs[PROFILES_KEY] = serializeProfiles(current)
        }
    }

    suspend fun setSplitPercent(deviceKey: String, splitPercent: Float) {
        context.audioDelayStore.edit { prefs ->
            val current = parseProfiles(prefs[PROFILES_KEY]).toMutableMap()
            val existing = current[deviceKey]
            val total = existing?.totalDelayMs ?: 0
            current[deviceKey] = DelayProfile(
                totalDelayMs = total,
                splitPercent = splitPercent.coerceIn(0f, 1f)
            )
            prefs[PROFILES_KEY] = serializeProfiles(current)
        }
    }

    private fun parseProfiles(json: String?): Map<String, DelayProfile> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, DelayProfile>()
            for (key in obj.keys()) {
                val profile = obj.getJSONObject(key)
                map[key] = DelayProfile(
                    totalDelayMs = profile.optInt("totalDelayMs", 0),
                    splitPercent = profile.optDouble("splitPercent", DEFAULT_OUTPUT_SPLIT.toDouble()).toFloat()
                )
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun serializeProfiles(profiles: Map<String, DelayProfile>): String {
        val obj = JSONObject()
        for ((key, profile) in profiles) {
            val p = JSONObject()
            p.put("totalDelayMs", profile.totalDelayMs)
            p.put("splitPercent", profile.splitPercent.toDouble())
            obj.put(key, p)
        }
        return obj.toString()
    }
}
