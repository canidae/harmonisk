package net.exent.perfectpitch.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.exent.perfectpitch.audio.*
import net.exent.perfectpitch.data.AudioDelayRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val melodySynthesizer: MelodySynthesizer,
    private val audioDeviceMonitor: AudioDeviceMonitor,
    private val pitchProcessor: PitchProcessor,
    private val audioDelayRepository: AudioDelayRepository
) : ViewModel() {

    companion object {
        const val NUM_TONES = 8
        const val TONE_ON_MS = 500L
        const val TONE_OFF_MS = 500L
        const val MIDI_NOTE = 69
        const val MIDI_VELOCITY = 100
        const val MAX_DETECTION_WINDOW_MS = 600L
        const val TARGET_FREQ = 440f
        const val FREQ_TOLERANCE = 30f
        const val SLIDER_MAX = 500
        const val SLIDER_STEP = 10
        const val SLIDER_DEFAULT = 250
        const val MIN_DETECTIONS = 3
    }

    enum class CalibrationPhase {
        INIT,
        INSTRUCTIONS,
        CALIBRATING,
        DONE
    }

    data class CalibrationState(
        val phase: CalibrationPhase = CalibrationPhase.INIT,
        val synthReady: Boolean = false,
        val synthError: String? = null,
        val inputDevice: AudioInputDevice? = null,
        val outputDevice: AudioOutputDevice? = null,
        val flashDelayMs: Int = SLIDER_DEFAULT,
        val calibrationActive: Boolean = false,
        val tonesPlayed: Int = 0,
        val tonesDetected: Int = 0,
        val toneStartTimeMs: Long = 0L,
        val computedAudioDelayMs: Int = 0,
        val computedVisualDelayMs: Int = 0,
        val saved: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(CalibrationState())
    val state: StateFlow<CalibrationState> = _state.asStateFlow()

    private var calibrationJob: Job? = null
    private var calibrationLoopJob: Job? = null
    private val roundTripMeasurements = mutableListOf<Int>()

    init {
        viewModelScope.launch {
            combine(
                audioDeviceMonitor.currentDevice,
                audioDeviceMonitor.currentInputDevice
            ) { output, input -> Pair(output, input) }
                .collect { (output, input) ->
                    _state.update { it.copy(outputDevice = output, inputDevice = input) }
                }
        }
    }

    fun initSynthesizer(context: android.content.Context) {
        if (melodySynthesizer.isReady) {
            _state.update { it.copy(synthReady = true) }
            goToInstructions()
            return
        }
        viewModelScope.launch {
            try {
                melodySynthesizer.open()
                _state.update { it.copy(synthReady = true, synthError = null) }
                goToInstructions()
            } catch (e: Exception) {
                _state.update { it.copy(synthError = e.message ?: "Failed to initialize audio") }
            }
        }
    }

    private fun goToInstructions() {
        _state.update { it.copy(phase = CalibrationPhase.INSTRUCTIONS, error = null) }
    }

    fun startCalibration() {
        if (!melodySynthesizer.isReady) return
        roundTripMeasurements.clear()

        _state.update {
            it.copy(
                phase = CalibrationPhase.CALIBRATING,
                tonesPlayed = 0,
                tonesDetected = 0,
                flashDelayMs = SLIDER_DEFAULT,
                error = null
            )
        }

        startCalibrationLoop()
    }

    private fun startCalibrationLoop() {
        stopCalibrationLoop()
        _state.update { it.copy(calibrationActive = true) }

        calibrationLoopJob = viewModelScope.launch {
            val detectionChannel = Channel<Pair<Long, Float>>(Channel.UNLIMITED)

            val pitchJob = launch {
                pitchProcessor.pitchFlow().collect { result ->
                    if (result.isValid && result.frequency > 0f) {
                        detectionChannel.trySend(System.currentTimeMillis() to result.frequency)
                    }
                }
            }

            delay(300)

            try {
                while (isActive) {
                    val playTime = System.currentTimeMillis()
                    _state.update { it.copy(toneStartTimeMs = playTime) }

                    melodySynthesizer.noteOn(0, MIDI_NOTE, MIDI_VELOCITY)
                    delay(TONE_ON_MS)
                    melodySynthesizer.noteOff(0, MIDI_NOTE)

                    _state.update { it.copy(tonesPlayed = _state.value.tonesPlayed + 1) }

                    val deadline = playTime + MAX_DETECTION_WINDOW_MS
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining > 0) delay(remaining)

                    val localDetections = mutableListOf<Pair<Long, Float>>()
                    while (true) {
                        localDetections.add(detectionChannel.tryReceive().getOrNull() ?: break)
                    }

                    val detection = localDetections.firstOrNull { (time, freq) ->
                        time >= playTime &&
                            time <= playTime + MAX_DETECTION_WINDOW_MS &&
                            kotlin.math.abs(freq - TARGET_FREQ) < FREQ_TOLERANCE
                    }

                    if (detection != null) {
                        val roundTrip = (detection.first - playTime).toInt().coerceIn(0, 1000)
                        roundTripMeasurements.add(roundTrip)
                        _state.update { it.copy(tonesDetected = roundTripMeasurements.size) }
                    }

                    val elapsed = System.currentTimeMillis() - playTime
                    val gap = (TONE_ON_MS + TONE_OFF_MS) - elapsed
                    if (gap > 0) delay(gap)
                }
            } finally {
                pitchJob.cancel()
                melodySynthesizer.allNotesOff()
                detectionChannel.close()
            }
        }
    }

    private fun stopCalibrationLoop() {
        calibrationLoopJob?.cancel()
        calibrationLoopJob = null
        if (melodySynthesizer.isReady) {
            melodySynthesizer.allNotesOff()
        }
        _state.update { it.copy(calibrationActive = false) }
    }

    fun updateFlashDelay(delayMs: Int) {
        val clamped = (delayMs / SLIDER_STEP * SLIDER_STEP).coerceIn(0, SLIDER_MAX)
        _state.update { it.copy(flashDelayMs = clamped) }
    }

    fun saveAndFinish() {
        val s = _state.value
        val inputDevice = s.inputDevice ?: return
        val outputDevice = s.outputDevice ?: return

        stopCalibrationLoop()

        val audioDelayMs = if (roundTripMeasurements.size >= MIN_DETECTIONS) {
            val sorted = roundTripMeasurements.sorted()
            sorted[sorted.size / 2]
        } else {
            s.flashDelayMs
        }

        val visualDelayMs = s.flashDelayMs

        _state.update {
            it.copy(
                computedAudioDelayMs = audioDelayMs,
                computedVisualDelayMs = visualDelayMs
            )
        }

        viewModelScope.launch {
            audioDelayRepository.saveCalibration(
                deviceKey = inputDevice.deviceKey,
                audioDelayMs = audioDelayMs,
                visualDelayMs = visualDelayMs
            )
            audioDelayRepository.saveCalibration(
                deviceKey = outputDevice.deviceKey,
                audioDelayMs = audioDelayMs,
                visualDelayMs = visualDelayMs
            )
            _state.update { it.copy(phase = CalibrationPhase.DONE, saved = true) }
        }
    }

    fun stopCalibration() {
        calibrationJob?.cancel()
        stopCalibrationLoop()
        goToInstructions()
    }

    override fun onCleared() {
        super.onCleared()
        calibrationJob?.cancel()
        calibrationLoopJob?.cancel()
        if (melodySynthesizer.isReady) {
            melodySynthesizer.allNotesOff()
        }
    }
}
