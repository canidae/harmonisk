package net.exent.perfectpitch.ui.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.exent.perfectpitch.audio.AudioDeviceMonitor
import net.exent.perfectpitch.audio.AudioInputDevice
import net.exent.perfectpitch.audio.AudioOutputDevice
import net.exent.perfectpitch.audio.PitchProcessor
import net.exent.perfectpitch.audio.SoundFontSynthesizer
import net.exent.perfectpitch.data.AppSettings
import net.exent.perfectpitch.data.AudioDelayRepository
import net.exent.perfectpitch.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val audioDeviceMonitor: AudioDeviceMonitor,
    private val audioDelayRepository: AudioDelayRepository,
    private val soundFontSynthesizer: SoundFontSynthesizer,
    private val pitchProcessor: PitchProcessor
) : ViewModel() {

    companion object {
        private const val TONE_ON_MS = 500L
        private const val TONE_OFF_MS = 1000L
        private const val MIDI_NOTE = 69
        private const val MIDI_VELOCITY = 100
        private const val TARGET_FREQ = 440f
        private const val FREQ_TOLERANCE = 30f
        private const val REQUIRED_DETECTIONS = 5
    }

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val currentDevice: StateFlow<AudioOutputDevice?> = audioDeviceMonitor.currentDevice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentInputDevice: StateFlow<AudioInputDevice?> = audioDeviceMonitor.currentInputDevice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeAudioDelay: StateFlow<Int> = audioDelayRepository.activeAudioDelay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val activeVisualDelay: StateFlow<Int> = audioDelayRepository.activeVisualDelay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _isCalibrating = MutableStateFlow(false)
    val isCalibrating: StateFlow<Boolean> = _isCalibrating.asStateFlow()

    private val _calibrationTonesDetected = MutableStateFlow(0)
    val calibrationTonesDetected: StateFlow<Int> = _calibrationTonesDetected.asStateFlow()

    private val _calibrationNoDetection = MutableStateFlow(false)
    val calibrationNoDetection: StateFlow<Boolean> = _calibrationNoDetection.asStateFlow()

    private var calibrationJob: Job? = null

    fun updateLanguageCode(code: String, activity: Activity) {
        viewModelScope.launch {
            settingsRepository.updateLanguageCode(code)
            activity.recreate()
        }
    }

    fun updateConcertPitch(pitch: Float) {
        viewModelScope.launch { settingsRepository.updateConcertPitch(pitch) }
    }

    fun updateSilenceThreshold(db: Float) {
        viewModelScope.launch { settingsRepository.updateSilenceThreshold(db) }
    }

    fun setAudioDelay(ms: Int) {
        viewModelScope.launch { audioDelayRepository.setAudioDelayForCurrentDevices(ms) }
    }

    fun setVisualDelay(ms: Int) {
        viewModelScope.launch { audioDelayRepository.setVisualDelayForCurrentDevices(ms) }
    }

    fun setAudioDelayFromText(text: String) {
        val ms = text.toIntOrNull()?.coerceIn(0, 1000) ?: return
        setAudioDelay(ms)
    }

    fun resetToDefaults() {
        viewModelScope.launch { settingsRepository.resetToDefaults() }
    }

    fun autoCalibrateAudioDelay() {
        if (_isCalibrating.value) {
            stopCalibration()
            return
        }

        calibrationJob = viewModelScope.launch {
            try {
                if (!soundFontSynthesizer.isReady) {
                    soundFontSynthesizer.open()
                }
            } catch (e: Exception) {
                _calibrationNoDetection.value = true
                return@launch
            }

            _isCalibrating.value = true
            _calibrationTonesDetected.value = 0
            _calibrationNoDetection.value = false

            val detectionChannel = Channel<Pair<Long, Float>>(Channel.UNLIMITED)

            val pitchJob = launch {
                pitchProcessor.pitchFlow().collect { result ->
                    if (result.isValid && result.frequency > 0f) {
                        detectionChannel.trySend(System.currentTimeMillis() to result.frequency)
                    }
                }
            }

            delay(300)

            val measurements = mutableListOf<Int>()
            var consecutiveUndetected = 0

            try {
                while (measurements.size < REQUIRED_DETECTIONS) {
                    val playTime = System.currentTimeMillis()

                    soundFontSynthesizer.noteOn(0, MIDI_NOTE, MIDI_VELOCITY)
                    delay(TONE_ON_MS)
                    soundFontSynthesizer.noteOff(0, MIDI_NOTE)

                    val deadline = playTime + TONE_ON_MS + TONE_OFF_MS
                    var detected: Pair<Long, Float>? = null
                    while (System.currentTimeMillis() < deadline) {
                        if (detected == null) {
                            val det = detectionChannel.tryReceive().getOrNull()
                            if (det != null && det.first >= playTime &&
                                abs(det.second - TARGET_FREQ) < FREQ_TOLERANCE
                            ) {
                                detected = det
                            }
                        }
                        delay(10)
                    }

                    if (detected != null) {
                        val roundTrip = (detected.first - playTime).toInt().coerceIn(0, 1000)
                        measurements.add(roundTrip)
                        _calibrationTonesDetected.value = measurements.size
                        consecutiveUndetected = 0
                        _calibrationNoDetection.value = false
                    } else {
                        consecutiveUndetected++
                        if (consecutiveUndetected >= 3) {
                            _calibrationNoDetection.value = true
                        }
                    }
                }

                val avg = measurements.average().toInt()
                audioDelayRepository.setAudioDelayForCurrentDevices(avg)
            } catch (_: Exception) {
            } finally {
                pitchJob.cancel()
                soundFontSynthesizer.allNotesOff()
                detectionChannel.close()
                _isCalibrating.value = false
                _calibrationNoDetection.value = false
            }
        }
    }

    private fun stopCalibration() {
        calibrationJob?.cancel()
        calibrationJob = null
        if (soundFontSynthesizer.isReady) {
            soundFontSynthesizer.allNotesOff()
        }
        _isCalibrating.value = false
        _calibrationNoDetection.value = false
    }

    override fun onCleared() {
        super.onCleared()
        if (soundFontSynthesizer.isReady) {
            soundFontSynthesizer.allNotesOff()
        }
        soundFontSynthesizer.close()
    }
}
