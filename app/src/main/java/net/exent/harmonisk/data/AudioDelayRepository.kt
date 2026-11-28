package net.exent.harmonisk.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import net.exent.harmonisk.audio.AudioDeviceMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val Context.audioDelayStore: DataStore<Preferences> by preferencesDataStore(
    name = "audio_delays"
)

class AudioDelayRepository(
    context: Context,
    private val audioDeviceMonitor: AudioDeviceMonitor
) {
    companion object {
        const val DEFAULT_AUDIO_DELAY_MS = 150
        const val MAX_AUDIO_DELAY_MS = 500
    }

    @Serializable
    data class DelayProfile(
        @SerialName("outputDelayMs") val audioDelayMs: Int = DEFAULT_AUDIO_DELAY_MS
    )

    private val store = JsonPreferenceStore<String, DelayProfile>(
        dataStore = context.audioDelayStore,
        keyName = "delay_profiles_json_v5"
    )

    val audioDelay: Flow<Int> = combine(
        audioDeviceMonitor.currentDevice,
        store.flow
    ) { device, profiles ->
        profiles[device.deviceKey]?.audioDelayMs ?: DEFAULT_AUDIO_DELAY_MS
    }

    suspend fun setAudioDelay(deviceKey: String, audioDelayMs: Int) =
        store.update(deviceKey) { DelayProfile(audioDelayMs.coerceIn(0, MAX_AUDIO_DELAY_MS)) }

    suspend fun setAudioDelayForCurrentDevice(audioDelayMs: Int) =
        setAudioDelay(audioDeviceMonitor.currentDevice.value.deviceKey, audioDelayMs)

    suspend fun resetToDefaults() =
        store.remove(audioDeviceMonitor.currentDevice.value.deviceKey)
}
