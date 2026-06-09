package net.exent.perfectpitch.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.exent.perfectpitch.audio.AudioDeviceMonitor
import net.exent.perfectpitch.audio.AudioInputDevice
import net.exent.perfectpitch.audio.AudioOutputDevice
import net.exent.perfectpitch.data.AppSettings
import net.exent.perfectpitch.data.AudioDelayRepository
import net.exent.perfectpitch.data.SettingsRepository
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

    fun updateSilenceThreshold(db: Float) {
        viewModelScope.launch { settingsRepository.updateSilenceThreshold(db) }
    }

    fun setOutputDelay(ms: Int) {
        viewModelScope.launch { audioDelayRepository.setOutputDelayForCurrentDevices(ms) }
    }

    fun setInputDelay(ms: Int) {
        viewModelScope.launch { audioDelayRepository.setInputDelayForCurrentDevices(ms) }
    }

    fun resetToDefaults() {
        viewModelScope.launch { settingsRepository.resetToDefaults() }
    }
}
