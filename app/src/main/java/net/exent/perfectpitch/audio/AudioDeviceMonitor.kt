package net.exent.perfectpitch.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors the active audio output device and emits changes.
 *
 * Device priority (highest first): Bluetooth A2DP → wired → built-in speaker.
 * Each device is identified by a key (e.g. "bluetooth_AirPods Pro", "speaker", "wired").
 */
class AudioDeviceMonitor(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Estimated output latency: framesPerBuffer × 2 / nativeSampleRate × 1000ms (double-buffered), clamped to [10, 200].
    val estimatedOutputLatencyMs: Int by lazy {
        val nativeSrStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val nativeFpbStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        val nativeSr = nativeSrStr?.toIntOrNull() ?: 48000
        val nativeFpb = nativeFpbStr?.toIntOrNull() ?: 256
        val estimateMs = (nativeFpb.toLong() * 2 * 1000 / nativeSr).toInt()
        estimateMs.coerceIn(10, 200)
    }

    private val _currentDevice = MutableStateFlow(resolveActiveOutputDevice())
    val currentDevice: StateFlow<AudioOutputDevice> = _currentDevice.asStateFlow()

    private val _currentInputDevice = MutableStateFlow(resolveActiveInputDevice())
    val currentInputDevice: StateFlow<AudioInputDevice> = _currentInputDevice.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshDevices()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshDevices()
    }

    private fun refreshDevices() {
        _currentDevice.value = resolveActiveOutputDevice()
        _currentInputDevice.value = resolveActiveInputDevice()
    }

    init {
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun close() {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    private fun resolveActiveOutputDevice(): AudioOutputDevice {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        firstOfTypes(outputs, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)?.let { bluetooth ->
            val name = bluetooth.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth"
            return AudioOutputDevice(type = AudioOutputDeviceType.BLUETOOTH, name = name, deviceKey = "bluetooth_$name")
        }

        firstOfTypes(outputs, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)?.let {
            return AudioOutputDevice(type = AudioOutputDeviceType.WIRED, name = "Wired headphones", deviceKey = "wired")
        }

        return AudioOutputDevice(type = AudioOutputDeviceType.SPEAKER, name = "Phone speaker", deviceKey = "speaker")
    }

    private fun resolveActiveInputDevice(): AudioInputDevice {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        firstOfTypes(inputs, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)?.let { bluetooth ->
            val name = bluetooth.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth"
            return AudioInputDevice(type = AudioInputDeviceType.BLUETOOTH, name = name, deviceKey = "bt_mic_$name")
        }

        firstOfTypes(inputs, AudioDeviceInfo.TYPE_WIRED_HEADSET)?.let {
            return AudioInputDevice(type = AudioInputDeviceType.WIRED, name = "Wired headset mic", deviceKey = "wired_mic")
        }

        return AudioInputDevice(type = AudioInputDeviceType.BUILTIN, name = "Built-in microphone", deviceKey = "builtin_mic")
    }

    private fun firstOfTypes(devices: Array<out AudioDeviceInfo>, vararg types: Int): AudioDeviceInfo? {
        for (type in types) {
            val found = devices.firstOrNull { it.type == type }
            if (found != null) return found
        }
        return null
    }
}

data class AudioOutputDevice(
    val type: AudioOutputDeviceType,
    val name: String,
    val deviceKey: String
)

enum class AudioOutputDeviceType {
    SPEAKER, WIRED, BLUETOOTH
}

data class AudioInputDevice(
    val type: AudioInputDeviceType,
    val name: String,
    val deviceKey: String
)

enum class AudioInputDeviceType {
    BUILTIN, WIRED, BLUETOOTH
}
