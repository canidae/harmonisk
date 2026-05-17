package com.perfectpitch.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.perfectpitch.audio.AudioDeviceMonitor
import com.perfectpitch.audio.AudioInputDevice
import com.perfectpitch.audio.AudioOutputDevice
import com.perfectpitch.data.AppSettings
import com.perfectpitch.data.AudioDelayProfileRepository
import com.perfectpitch.data.AudioInputDelayRepository
import com.perfectpitch.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val audioDeviceMonitor: AudioDeviceMonitor,
    private val audioDelayProfileRepository: AudioDelayProfileRepository,
    private val audioInputDelayRepository: AudioInputDelayRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val currentDevice: StateFlow<AudioOutputDevice?> = audioDeviceMonitor.currentDevice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentInputDevice: StateFlow<AudioInputDevice?> = audioDeviceMonitor.currentInputDevice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeDelay: StateFlow<Int> = audioDelayProfileRepository.activeDelay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val activeInputDelay: StateFlow<Int> = audioInputDelayRepository.activeDelay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun updateConcertPitch(pitch: Float) {
        viewModelScope.launch { settingsRepository.updateConcertPitch(pitch) }
    }

    fun updateInTuneCents(cents: Int) {
        viewModelScope.launch { settingsRepository.updateInTuneCents(cents) }
    }

    fun updateDarkTheme(dark: Boolean) {
        viewModelScope.launch { settingsRepository.updateDarkTheme(dark) }
    }

    fun updateSilenceThreshold(db: Float) {
        viewModelScope.launch { settingsRepository.updateSilenceThreshold(db) }
    }

    fun updateContinuousPitchLine(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateContinuousPitchLine(enabled) }
    }

    fun saveDelay(ms: Int) {
        val device = audioDeviceMonitor.currentDevice.value
        viewModelScope.launch {
            if (device.type == com.perfectpitch.audio.AudioOutputDeviceType.SPEAKER) {
                // For built-in speaker: save as offset relative to measured delay
                val currentTotal = activeDelay.value
                val measuredBase = currentTotal - (audioDelayProfileRepository.getBuiltinOffset().first())
                val newOffset = ms - measuredBase
                audioDelayProfileRepository.setBuiltinOffset(newOffset)
            } else {
                audioDelayProfileRepository.setDelayForDevice(device.deviceKey, ms)
            }
        }
    }

    fun saveInputDelay(ms: Int) {
        val device = audioDeviceMonitor.currentInputDevice.value
        viewModelScope.launch {
            if (device.type == com.perfectpitch.audio.AudioInputDeviceType.BUILTIN) {
                // For built-in mic: save as offset relative to measured delay
                val currentTotal = activeInputDelay.value
                val measuredBase = currentTotal - (audioInputDelayRepository.getBuiltinOffset().first())
                val newOffset = ms - measuredBase
                audioInputDelayRepository.setBuiltinOffset(newOffset)
            } else {
                audioInputDelayRepository.setDelayForDevice(device.deviceKey, ms)
            }
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch { settingsRepository.resetToDefaults() }
    }
}
