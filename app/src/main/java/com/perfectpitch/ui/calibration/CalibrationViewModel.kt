package com.perfectpitch.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.perfectpitch.audio.*
import com.perfectpitch.data.AudioDelayRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
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
        // Round-trip measurement (Phase 1)
        const val NUM_TONES = 8
        const val TONE_SPACING_MS = 800L
        const val TONE_DURATION_MS = 300L
        const val MIDI_NOTE = 69 // A4 = 440 Hz
        const val MIDI_VELOCITY = 100
        const val MAX_DETECTION_WINDOW_MS = 600L
        const val TARGET_FREQ = 440f
        const val FREQ_TOLERANCE = 30f

        // Split adjustment (Phase 2)
        const val PULSE_ON_MS = 500L
        const val PULSE_OFF_MS = 500L
        const val PULSE_CYCLE_MS = PULSE_ON_MS + PULSE_OFF_MS
    }

    enum class CalibrationPhase {
        INIT,
        INSTRUCTIONS,
        MEASURING_ROUNDTRIP,
        ADJUSTING_SPLIT,
        DONE
    }

    data class CalibrationState(
        val phase: CalibrationPhase = CalibrationPhase.INIT,
        val synthReady: Boolean = false,
        val synthError: String? = null,
        // Current devices
        val inputDevice: AudioInputDevice? = null,
        val outputDevice: AudioOutputDevice? = null,
        // Phase 1: round-trip measurement
        val tonesPlayed: Int = 0,
        val tonesDetected: Int = 0,
        val totalDelayMs: Int = 0,
        // Phase 2: split adjustment
        /** Current output delay set by slider (ms). */
        val outputDelayMs: Int = 0,
        /** Derived: totalDelayMs - outputDelayMs. */
        val inputDelayMs: Int = 0,
        /** Whether the split tone loop is currently playing. */
        val splitActive: Boolean = false,
        /** Wall-clock time when split loop started (for UI pulse animation). */
        val splitStartTimeMs: Long = 0L,
        // Status
        val saved: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(CalibrationState())
    val state: StateFlow<CalibrationState> = _state.asStateFlow()

    private var calibrationJob: Job? = null
    private var splitLoopJob: Job? = null

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
                melodySynthesizer.open(context)
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

    // --- Phase 1: Measure total round-trip delay ---

    fun startRoundTripMeasurement() {
        if (!melodySynthesizer.isReady) return

        _state.update {
            it.copy(
                phase = CalibrationPhase.MEASURING_ROUNDTRIP,
                tonesPlayed = 0,
                tonesDetected = 0,
                error = null
            )
        }

        calibrationJob = viewModelScope.launch {
            val totalMs = measureRoundTrip()

            if (totalMs != null) {
                // Compute initial split.
                // If we have a stored delay for one device, use that as the pre-fill.
                val outputDevice = _state.value.outputDevice
                val inputDevice = _state.value.inputDevice
                val initialOutput = computeInitialOutputDelay(totalMs, outputDevice, inputDevice)
                val initialInput = totalMs - initialOutput

                _state.update {
                    it.copy(
                        phase = CalibrationPhase.ADJUSTING_SPLIT,
                        totalDelayMs = totalMs,
                        outputDelayMs = initialOutput,
                        inputDelayMs = initialInput
                    )
                }

                startSplitLoop()
            } else {
                _state.update {
                    it.copy(
                        phase = CalibrationPhase.INSTRUCTIONS,
                        error = "Could not detect enough tones. Ensure the environment is quiet and the microphone can hear the speaker."
                    )
                }
            }
        }
    }

    /**
     * Plays [NUM_TONES] tones and measures the round-trip time for each (play→detect).
     * Returns the median round-trip in ms, or null if not enough tones were detected.
     */
    private suspend fun measureRoundTrip(): Int? {
        val detections = mutableListOf<Pair<Long, Float>>()

        val pitchJob = viewModelScope.launch {
            pitchProcessor.pitchFlow().collect { result ->
                if (result.isValid && result.frequency > 0f) {
                    detections.add(System.currentTimeMillis() to result.frequency)
                }
            }
        }

        delay(300) // Mic settling

        val roundTrips = mutableListOf<Long>()

        try {
            for (i in 0 until NUM_TONES) {
                if (!currentCoroutineContext().isActive) break

                val playTime = System.currentTimeMillis()
                melodySynthesizer.noteOn(0, MIDI_NOTE, MIDI_VELOCITY)
                delay(TONE_DURATION_MS)
                melodySynthesizer.noteOff(0, MIDI_NOTE)

                _state.update { it.copy(tonesPlayed = i + 1) }

                // Wait for detection window to close
                val deadline = playTime + MAX_DETECTION_WINDOW_MS
                val remaining = deadline - System.currentTimeMillis()
                if (remaining > 0) delay(remaining)

                // Find the first matching detection after playTime
                val detection = detections.firstOrNull { (time, freq) ->
                    time >= playTime &&
                        time <= playTime + MAX_DETECTION_WINDOW_MS &&
                        kotlin.math.abs(freq - TARGET_FREQ) < FREQ_TOLERANCE
                }

                if (detection != null) {
                    val roundTrip = detection.first - playTime
                    roundTrips.add(roundTrip)
                    _state.update { it.copy(tonesDetected = roundTrips.size) }
                }

                // Clear used detections to avoid double-counting
                val cutoff = playTime + MAX_DETECTION_WINDOW_MS
                detections.removeAll { (time, _) -> time <= cutoff }

                // Wait for next tone spacing
                val elapsed = System.currentTimeMillis() - playTime
                val gap = TONE_SPACING_MS - elapsed
                if (gap > 0) delay(gap)
            }
        } finally {
            pitchJob.cancel()
            melodySynthesizer.allNotesOff()
        }

        if (roundTrips.size < 3) return null

        val sorted = roundTrips.sorted()
        return sorted[sorted.size / 2].toInt().coerceIn(0, 1000)
    }

    /**
     * Computes the initial output delay for the slider.
     * If one device has a stored delay, pre-fill using that.
     * Otherwise, use [AudioDelayRepository.DEFAULT_OUTPUT_SPLIT].
     */
    private suspend fun computeInitialOutputDelay(
        totalMs: Int,
        outputDevice: AudioOutputDevice?,
        inputDevice: AudioInputDevice?
    ): Int {
        // Try to use stored output delay
        if (outputDevice != null) {
            val profile = audioDelayRepository.getProfile(outputDevice.deviceKey).first()
            if (profile != null) {
                return profile.outputDelayMs.coerceIn(0, totalMs)
            }
        }
        // Try to use stored input delay to derive output
        if (inputDevice != null) {
            val profile = audioDelayRepository.getProfile(inputDevice.deviceKey).first()
            if (profile != null) {
                return (totalMs - profile.inputDelayMs).coerceIn(0, totalMs)
            }
        }
        // Default: even split
        return (totalMs * AudioDelayRepository.DEFAULT_OUTPUT_SPLIT).toInt()
    }

    // --- Phase 2: Split adjustment with audio+visual loop ---

    /**
     * Called when the user moves the slider.
     * Updates output/input split and restarts the tone loop with new timing.
     */
    fun updateSplitSlider(outputDelayMs: Int) {
        val total = _state.value.totalDelayMs
        val clamped = outputDelayMs.coerceIn(0, total)
        _state.update {
            it.copy(
                outputDelayMs = clamped,
                inputDelayMs = total - clamped
            )
        }
    }

    /**
     * Starts the tone + visual pulse loop for split adjustment.
     * Plays a tone `outputDelayMs` before each visual pulse.
     */
    private fun startSplitLoop() {
        stopSplitLoop()
        _state.update {
            it.copy(
                splitActive = true,
                splitStartTimeMs = System.currentTimeMillis()
            )
        }

        splitLoopJob = viewModelScope.launch {
            while (isActive) {
                val outputDelay = _state.value.outputDelayMs.toLong()

                // Play tone first (outputDelay ms before the visual pulse)
                melodySynthesizer.noteOn(0, MIDI_NOTE, MIDI_VELOCITY)
                delay(PULSE_ON_MS)
                melodySynthesizer.noteOff(0, MIDI_NOTE)

                // After the tone ends, we need to wait for the visual pulse to start,
                // then the visual will be on for PULSE_ON_MS, then off for PULSE_OFF_MS.
                // But the visual pulse starts `outputDelay` after the tone started.
                //
                // Timeline per cycle:
                //   t=0:               tone starts
                //   t=PULSE_ON_MS:     tone ends
                //   t=outputDelay:     visual pulse starts
                //   t=outputDelay+PULSE_ON_MS: visual pulse ends
                //   t=outputDelay+PULSE_CYCLE_MS: next cycle starts
                //
                // Wait between tone end and next cycle:
                val cycleTotal = outputDelay + PULSE_CYCLE_MS
                val elapsed = PULSE_ON_MS
                val remaining = cycleTotal - elapsed
                if (remaining > 0) delay(remaining) else delay(PULSE_OFF_MS)
            }
        }
    }

    private fun stopSplitLoop() {
        splitLoopJob?.cancel()
        splitLoopJob = null
        if (melodySynthesizer.isReady) {
            melodySynthesizer.allNotesOff()
        }
        _state.update { it.copy(splitActive = false) }
    }

    // --- Save and finish ---

    fun saveAndFinish() {
        val s = _state.value
        val inputDevice = s.inputDevice ?: return
        val outputDevice = s.outputDevice ?: return

        stopSplitLoop()

        viewModelScope.launch {
            // Save for input device
            audioDelayRepository.saveCalibration(
                deviceKey = inputDevice.deviceKey,
                inputDelayMs = s.inputDelayMs,
                outputDelayMs = s.outputDelayMs,
                totalDelayMs = s.totalDelayMs
            )
            // Save for output device (same calibration data, keyed differently)
            audioDelayRepository.saveCalibration(
                deviceKey = outputDevice.deviceKey,
                inputDelayMs = s.inputDelayMs,
                outputDelayMs = s.outputDelayMs,
                totalDelayMs = s.totalDelayMs
            )

            _state.update { it.copy(phase = CalibrationPhase.DONE, saved = true) }
        }
    }

    fun stopCalibration() {
        calibrationJob?.cancel()
        stopSplitLoop()
        goToInstructions()
    }

    override fun onCleared() {
        super.onCleared()
        calibrationJob?.cancel()
        splitLoopJob?.cancel()
        if (melodySynthesizer.isReady) {
            melodySynthesizer.allNotesOff()
        }
    }
}
