package net.exent.harmonisk.ui.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.exent.harmonisk.audio.AudioCaptureService
import net.exent.harmonisk.audio.AudioDeviceMonitor
import net.exent.harmonisk.audio.AudioInputDevice
import net.exent.harmonisk.audio.AudioOutputDevice
import net.exent.harmonisk.audio.PitchProcessor
import net.exent.harmonisk.audio.SoundFontSynthesizer
import net.exent.harmonisk.data.AppSettings
import net.exent.harmonisk.data.AudioDelayRepository
import net.exent.harmonisk.data.SettingsRepository
import net.exent.harmonisk.music.Instrument
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val audioDeviceMonitor: AudioDeviceMonitor,
    private val audioDelayRepository: AudioDelayRepository,
    private val soundFontSynthesizer: SoundFontSynthesizer,
    private val pitchProcessor: PitchProcessor,
    private val audioCaptureService: AudioCaptureService
) : ViewModel() {

    companion object {
        private const val TONE_ON_MS = 500L
        private const val TONE_OFF_MS = 1000L
        private const val MIDI_NOTE = 69
        private const val MIDI_VELOCITY = 100
        private const val TARGET_FREQ = 440f
        private const val FREQ_TOLERANCE = 30f
        private const val REQUIRED_DETECTIONS = 5
        /** Calibration rounds delays to the steps the delay sliders can express. */
        const val DELAY_STEP_MS = 25
        // Fallbacks when the device doesn't report capture/inference latency
        private const val FALLBACK_CAPTURE_LATENCY_MS = 15
        private const val FALLBACK_INFERENCE_LATENCY_MS = 40
    }

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

    fun updateSilenceThreshold(db: Float) {
        viewModelScope.launch { settingsRepository.updateSilenceThreshold(db) }
    }

    fun updateInstrument(name: String) {
        viewModelScope.launch {
            val instrument = Instrument.fromName(name)
            soundFontSynthesizer.setInstrument(instrument.midiBank, instrument.midiProgram)
            settingsRepository.updateInstrument(name)
        }
    }

    fun setInputDelay(ms: Int) {
        viewModelScope.launch { audioDelayRepository.setInputDelayForCurrentDevices(ms) }
    }

    fun setOutputDelay(ms: Int) {
        viewModelScope.launch { audioDelayRepository.setOutputDelayForCurrentDevices(ms) }
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
                try {
                    pitchProcessor.pitchFlow().collect { result ->
                        if (result.isValid && result.frequency > 0f) {
                            detectionChannel.trySend(System.currentTimeMillis() to result.frequency)
                        }
                    }
                } catch (_: SecurityException) {
                    _calibrationNoDetection.value = true
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

                // The round trip is everything the tone spends getting out of the speaker, through
                // the air, and back through the mic and pitch detection. Only what is left after
                // the input side is the app's own playback delay.
                val roundTripMs = roundToStep(measurements.average().toInt())
                val inputMs = measureInputDelayMs()
                audioDelayRepository.setInputDelayForCurrentDevices(inputMs)
                audioDelayRepository.setOutputDelayForCurrentDevices(
                    roundToStep(roundTripMs - inputMs).coerceAtLeast(0)
                )
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

    /**
     * Input delay = mic capture latency + analysis window + CREPE inference time.
     * Sampled from the live capture pipeline after the round-trip measurement.
     */
    private suspend fun measureInputDelayMs(): Int {
        var captureSum = 0
        var inferenceSum = 0
        var captureCount = 0
        var inferenceCount = 0
        repeat(5) {
            val capture = audioCaptureService.captureLatencyMs
            if (capture > 0) { captureSum += capture; captureCount++ }
            val inference = pitchProcessor.inferenceLatencyMs
            if (inference > 0) { inferenceSum += inference; inferenceCount++ }
            delay(100)
        }
        val capture = if (captureCount > 0) captureSum / captureCount else FALLBACK_CAPTURE_LATENCY_MS
        val inference = if (inferenceCount > 0) inferenceSum / inferenceCount else FALLBACK_INFERENCE_LATENCY_MS
        val inputMs = capture + PitchProcessor.ANALYSIS_WINDOW_MS + inference + PitchProcessor.INPUT_ONSET_MS
        return roundToStep(inputMs)
    }

    override fun onCleared() {
        super.onCleared()
        if (soundFontSynthesizer.isReady) {
            soundFontSynthesizer.allNotesOff()
        }
        soundFontSynthesizer.close()
    }
}

/** Rounds a delay to the nearest step the delay sliders can express. */
internal fun roundToStep(ms: Int): Int =
    (ms + SettingsViewModel.DELAY_STEP_MS / 2) / SettingsViewModel.DELAY_STEP_MS * SettingsViewModel.DELAY_STEP_MS
