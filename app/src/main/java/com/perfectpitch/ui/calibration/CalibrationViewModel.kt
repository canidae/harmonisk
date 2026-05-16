package com.perfectpitch.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.perfectpitch.audio.AudioDeviceMonitor
import com.perfectpitch.audio.AudioInputDevice
import com.perfectpitch.audio.MelodySynthesizer
import com.perfectpitch.audio.PitchProcessor
import com.perfectpitch.data.AudioInputDelayRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val melodySynthesizer: MelodySynthesizer,
    private val audioDeviceMonitor: AudioDeviceMonitor,
    private val audioInputDelayRepository: AudioInputDelayRepository,
    private val pitchProcessor: PitchProcessor
) : ViewModel() {

    companion object {
        const val NUM_TONES = 8
        const val TONE_SPACING_MS = 600L
        const val TONE_DURATION_MS = 200L
        const val MIDI_NOTE = 69 // A4 = 440 Hz
        const val MIDI_VELOCITY = 100
        const val TIMEOUT_MS = 15_000L
        const val MAX_DETECTION_WINDOW_MS = 500L
        const val TARGET_FREQ = 440f
        const val FREQ_TOLERANCE = 30f // Hz tolerance for matching
    }

    data class CalibrationState(
        val inputDevice: AudioInputDevice? = null,
        val phase: CalibrationPhase = CalibrationPhase.READY,
        val synthReady: Boolean = false,
        val synthError: String? = null,
        val tonesPlayed: Int = 0,
        val tonesDetected: Int = 0,
        val resultDelayMs: Int? = null,
        val editedDelayMs: String = "",
        val saved: Boolean = false,
        val error: String? = null
    )

    enum class CalibrationPhase {
        READY,
        CALIBRATING,
        RESULT
    }

    private val _state = MutableStateFlow(CalibrationState())
    val state: StateFlow<CalibrationState> = _state.asStateFlow()

    private var calibrationJob: Job? = null

    init {
        viewModelScope.launch {
            audioDeviceMonitor.currentInputDevice.collect { device ->
                _state.update { it.copy(inputDevice = device) }
            }
        }
    }

    fun initSynthesizer(context: android.content.Context) {
        if (melodySynthesizer.isReady) {
            _state.update { it.copy(synthReady = true) }
            return
        }
        viewModelScope.launch {
            try {
                melodySynthesizer.open(context)
                _state.update { it.copy(synthReady = true, synthError = null) }
            } catch (e: Exception) {
                _state.update { it.copy(synthError = e.message ?: "Failed to initialize audio") }
            }
        }
    }

    fun startCalibration() {
        if (_state.value.phase == CalibrationPhase.CALIBRATING) return
        if (!melodySynthesizer.isReady) return

        _state.update {
            it.copy(
                phase = CalibrationPhase.CALIBRATING,
                tonesPlayed = 0,
                tonesDetected = 0,
                resultDelayMs = null,
                editedDelayMs = "",
                saved = false,
                error = null
            )
        }

        calibrationJob = viewModelScope.launch {
            val delays = mutableListOf<Long>()

            // Collect pitch data via PitchProcessor
            val detections = mutableListOf<Pair<Long, Float>>() // (timeMs, frequency)
            val pitchJob = launch {
                pitchProcessor.pitchFlow().collect { result ->
                    if (result.isValid && result.frequency > 0f) {
                        detections.add(System.currentTimeMillis() to result.frequency)
                    }
                }
            }

            // Brief settling period for mic
            delay(300)

            try {
                for (i in 0 until NUM_TONES) {
                    if (!isActive) break

                    val playTime = System.currentTimeMillis()
                    melodySynthesizer.noteOn(0, MIDI_NOTE, MIDI_VELOCITY)
                    delay(TONE_DURATION_MS)
                    melodySynthesizer.noteOff(0, MIDI_NOTE)

                    _state.update { it.copy(tonesPlayed = i + 1) }

                    // Wait for detection within the window
                    val deadline = playTime + MAX_DETECTION_WINDOW_MS
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining > 0) delay(remaining)

                    // Find the first detection of our frequency after playTime
                    val detection = detections.firstOrNull { (time, freq) ->
                        time >= playTime &&
                            time <= playTime + MAX_DETECTION_WINDOW_MS &&
                            kotlin.math.abs(freq - TARGET_FREQ) < FREQ_TOLERANCE
                    }

                    if (detection != null) {
                        val delayMs = detection.first - playTime
                        delays.add(delayMs)
                        _state.update { it.copy(tonesDetected = delays.size) }
                    }

                    // Wait for gap between tones
                    val elapsed = System.currentTimeMillis() - playTime
                    val gap = TONE_SPACING_MS - elapsed
                    if (gap > 0) delay(gap)
                }
            } finally {
                pitchJob.cancel()
                melodySynthesizer.allNotesOff()
            }

            // Compute result
            if (delays.size >= 2) {
                val sorted = delays.sorted()
                val median = if (sorted.size % 2 == 0) {
                    (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
                } else {
                    sorted[sorted.size / 2]
                }
                val result = median.toInt().coerceIn(0, 500)
                _state.update {
                    it.copy(
                        phase = CalibrationPhase.RESULT,
                        resultDelayMs = result,
                        editedDelayMs = result.toString()
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        phase = CalibrationPhase.READY,
                        error = "Could not detect enough tones. Make sure the microphone can hear the speaker."
                    )
                }
            }
        }
    }

    fun updateEditedDelay(text: String) {
        _state.update { it.copy(editedDelayMs = text) }
    }

    fun saveDelay() {
        val device = _state.value.inputDevice ?: return
        val delayMs = _state.value.editedDelayMs.toIntOrNull()
            ?: _state.value.resultDelayMs
            ?: return

        viewModelScope.launch {
            audioInputDelayRepository.setDelayForDevice(device.deviceKey, delayMs)
            _state.update { it.copy(saved = true) }
        }
    }

    fun stopCalibration() {
        calibrationJob?.cancel()
        if (melodySynthesizer.isReady) {
            melodySynthesizer.allNotesOff()
        }
        _state.update { it.copy(phase = CalibrationPhase.READY) }
    }

    override fun onCleared() {
        super.onCleared()
        calibrationJob?.cancel()
    }
}
