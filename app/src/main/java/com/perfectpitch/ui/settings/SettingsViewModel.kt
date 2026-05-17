package com.perfectpitch.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.perfectpitch.audio.AudioDeviceMonitor
import com.perfectpitch.audio.AudioInputDevice
import com.perfectpitch.audio.AudioOutputDevice
import com.perfectpitch.data.AppSettings
import com.perfectpitch.data.AudioDelayRepository
import com.perfectpitch.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val audioDeviceMonitor: AudioDeviceMonitor,
    private val audioDelayRepository: AudioDelayRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val currentDevice: StateFlow<AudioOutputDevice?> = audioDeviceMonitor.currentDevice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentInputDevice: StateFlow<AudioInputDevice?> = audioDeviceMonitor.currentInputDevice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeOutputDelay: StateFlow<Int> = audioDelayRepository.activeOutputDelay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val activeInputDelay: StateFlow<Int> = audioDelayRepository.activeInputDelay
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

    fun saveOutputDelay(ms: Int) {
        val device = audioDeviceMonitor.currentDevice.value
        viewModelScope.launch {
            audioDelayRepository.setOutputDelay(device.deviceKey, ms)
        }
    }

    fun saveInputDelay(ms: Int) {
        val device = audioDeviceMonitor.currentInputDevice.value
        viewModelScope.launch {
            audioDelayRepository.setInputDelay(device.deviceKey, ms)
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch { settingsRepository.resetToDefaults() }
    }
}
