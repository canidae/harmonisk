package net.exent.perfectpitch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import net.exent.perfectpitch.audio.AudioDeviceMonitor
import net.exent.perfectpitch.audio.AudioInputDeviceType
import net.exent.perfectpitch.audio.AudioOutputDeviceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable

private val Context.audioDelayStore: DataStore<Preferences> by preferencesDataStore(
    name = "audio_delays"
)

class AudioDelayRepository(
    context: Context,
    private val audioDeviceMonitor: AudioDeviceMonitor
) {
    companion object {
        fun defaultAudioDelay(type: AudioOutputDeviceType): Int = when (type) {
            AudioOutputDeviceType.SPEAKER -> 0
            AudioOutputDeviceType.WIRED -> 0
            AudioOutputDeviceType.BLUETOOTH -> 150
        }

        fun defaultVisualDelay(type: AudioInputDeviceType): Int = when (type) {
            AudioInputDeviceType.BUILTIN -> 0
            AudioInputDeviceType.WIRED -> 0
            AudioInputDeviceType.BLUETOOTH -> 0
        }
    }

    @Serializable
    data class DelayProfile(
        val audioDelayMs: Int = 0,
        val visualDelayMs: Int = 0
    )

    private val store = JsonPreferenceStore<String, DelayProfile>(
        dataStore = context.audioDelayStore,
        keyName = "delay_profiles_json"
    )

    val activeAudioDelay: Flow<Int> = combine(
        audioDeviceMonitor.currentDevice,
        store.flow
    ) { device, profiles ->
        profiles[device.deviceKey]?.audioDelayMs ?: defaultAudioDelay(device.type)
    }

    val activeVisualDelay: Flow<Int> = combine(
        audioDeviceMonitor.currentInputDevice,
        store.flow
    ) { device, profiles ->
        profiles[device.deviceKey]?.visualDelayMs ?: defaultVisualDelay(device.type)
    }

    val allProfiles: Flow<Map<String, DelayProfile>> = store.flow

    suspend fun setAudioDelay(deviceKey: String, audioDelayMs: Int) =
        store.update(deviceKey) { existing ->
            DelayProfile(
                audioDelayMs = audioDelayMs.coerceIn(0, 1000),
                visualDelayMs = existing?.visualDelayMs ?: 0
            )
        }

    suspend fun setVisualDelay(deviceKey: String, visualDelayMs: Int) =
        store.update(deviceKey) { existing ->
            DelayProfile(
                audioDelayMs = existing?.audioDelayMs ?: 0,
                visualDelayMs = visualDelayMs.coerceIn(0, 1000)
            )
        }

    suspend fun setAudioDelayForCurrentDevices(audioDelayMs: Int) {
        val outputKey = audioDeviceMonitor.currentDevice.value.deviceKey
        val inputKey = audioDeviceMonitor.currentInputDevice.value.deviceKey
        setAudioDelay(outputKey, audioDelayMs)
        setAudioDelay(inputKey, audioDelayMs)
    }

    suspend fun setVisualDelayForCurrentDevices(visualDelayMs: Int) {
        val outputKey = audioDeviceMonitor.currentDevice.value.deviceKey
        val inputKey = audioDeviceMonitor.currentInputDevice.value.deviceKey
        setVisualDelay(outputKey, visualDelayMs)
        setVisualDelay(inputKey, visualDelayMs)
    }
}
