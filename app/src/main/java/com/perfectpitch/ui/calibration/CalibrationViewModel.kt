package com.perfectpitch.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.perfectpitch.audio.*
import com.perfectpitch.data.AudioDelayProfileRepository
import com.perfectpitch.data.AudioInputDelayRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val melodySynthesizer: MelodySynthesizer,
    private val audioDeviceMonitor: AudioDeviceMonitor,
    private val audioCaptureService: AudioCaptureService,
    private val audioInputDelayRepository: AudioInputDelayRepository,
    private val audioDelayProfileRepository: AudioDelayProfileRepository,
    private val pitchProcessor: PitchProcessor
) : ViewModel() {

    companion object {
        /** Metronome BPM for input calibration. */
        const val METRONOME_BPM = 90
        /** Milliseconds per metronome beat. */
        const val BEAT_INTERVAL_MS = (60_000.0 / METRONOME_BPM).toLong() // ~667ms
        /** Number of warm-up beats (not counted for calibration). */
        const val WARMUP_BEATS = 4
        /** Number of measured beats for calibration. */
        const val MEASURED_BEATS = 8
        /** Total beats in the input calibration sequence. */
        const val TOTAL_BEATS = WARMUP_BEATS + MEASURED_BEATS
        /** Maximum window to match a sound detection to a beat (±ms). */
        const val INPUT_DETECTION_WINDOW_MS = 250L

        // Output calibration constants
        const val OUTPUT_NUM_TONES = 8
        const val OUTPUT_TONE_SPACING_MS = 600L
        const val OUTPUT_TONE_DURATION_MS = 200L
        const val OUTPUT_MIDI_NOTE = 69 // A4 = 440 Hz
        const val OUTPUT_MIDI_VELOCITY = 100
        const val OUTPUT_MAX_DETECTION_WINDOW_MS = 500L
        const val OUTPUT_TARGET_FREQ = 440f
        const val OUTPUT_FREQ_TOLERANCE = 30f
    }

    /**
     * Two-phase calibration flow:
     *
     * INIT → INPUT_INSTRUCTIONS → INPUT_CALIBRATING → INPUT_RESULT
     *      → OUTPUT_OFFER → OUTPUT_CALIBRATING → OUTPUT_RESULT → DONE
     *
     * Output phase can be skipped from OUTPUT_OFFER.
     */
    enum class CalibrationPhase {
        INIT,
        INPUT_INSTRUCTIONS,
        INPUT_CALIBRATING,
        INPUT_RESULT,
        OUTPUT_OFFER,
        OUTPUT_CALIBRATING,
        OUTPUT_RESULT,
        DONE
    }

    data class CalibrationState(
        val phase: CalibrationPhase = CalibrationPhase.INIT,
        val synthReady: Boolean = false,
        val synthError: String? = null,
        // Current devices
        val inputDevice: AudioInputDevice? = null,
        val outputDevice: AudioOutputDevice? = null,
        // Input calibration progress
        val currentBeat: Int = 0,
        val isWarmup: Boolean = true,
        val beatsDetected: Int = 0,
        /** Wall-clock time when input calibration started (for UI animation). */
        val calibrationStartTimeMs: Long = 0L,
        // Input calibration result
        val inputDelayMs: Int? = null,
        val editedInputDelay: String = "",
        // Output calibration progress
        val tonesPlayed: Int = 0,
        val tonesDetected: Int = 0,
        // Output calibration result
        val outputDelayMs: Int? = null,
        val editedOutputDelay: String = "",
        // Status
        val saved: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(CalibrationState())
    val state: StateFlow<CalibrationState> = _state.asStateFlow()

    private var calibrationJob: Job? = null
    private var currentInputDelayMs: Int = 0

    init {
        viewModelScope.launch {
            audioInputDelayRepository.migrateIfNeeded()
        }

        viewModelScope.launch {
            combine(
                audioDeviceMonitor.currentDevice,
                audioDeviceMonitor.currentInputDevice
            ) { output, input -> Pair(output, input) }
                .collect { (output, input) ->
                    _state.update { it.copy(outputDevice = output, inputDevice = input) }
                }
        }

        viewModelScope.launch {
            audioInputDelayRepository.activeDelay.collect { delayMs ->
                currentInputDelayMs = delayMs
            }
        }
    }

    fun initSynthesizer(context: android.content.Context) {
        if (melodySynthesizer.isReady) {
            _state.update { it.copy(synthReady = true) }
            goToInputInstructions()
            return
        }
        viewModelScope.launch {
            try {
                melodySynthesizer.open(context)
                _state.update { it.copy(synthReady = true, synthError = null) }
                goToInputInstructions()
            } catch (e: Exception) {
                _state.update { it.copy(synthError = e.message ?: "Failed to initialize audio") }
            }
        }
    }

    private fun goToInputInstructions() {
        _state.update { it.copy(phase = CalibrationPhase.INPUT_INSTRUCTIONS, error = null) }
    }

    // --- Input calibration: metronome sync ---

    fun startInputCalibration() {
        _state.update {
            it.copy(
                phase = CalibrationPhase.INPUT_CALIBRATING,
                currentBeat = 0,
                isWarmup = true,
                beatsDetected = 0,
                error = null
            )
        }

        calibrationJob = viewModelScope.launch {
            val offsets = runInputCalibration()

            if (offsets != null && offsets.isNotEmpty()) {
                val sorted = offsets.sorted()
                val median = sorted[sorted.size / 2]
                // Positive median means detections arrive after the beat → input delay is too low
                // Negative means detections arrive before → input delay is too high
                val currentDelay = currentInputDelayMs
                val adjustedDelay = (currentDelay + median.toInt()).coerceIn(0, 500)

                _state.update {
                    it.copy(
                        phase = CalibrationPhase.INPUT_RESULT,
                        inputDelayMs = adjustedDelay,
                        editedInputDelay = adjustedDelay.toString()
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        phase = CalibrationPhase.INPUT_INSTRUCTIONS,
                        error = "Could not detect enough sounds. Try making louder or clearer sounds (clap, hum, or play a note) in sync with each pulse."
                    )
                }
            }
        }
    }

    /**
     * Runs the metronome input calibration sequence.
     *
     * Returns a list of timing offsets (ms): positive = detection was late, negative = early.
     * Returns null if not enough beats were detected.
     */
    private suspend fun runInputCalibration(): List<Long>? {
        val detections = mutableListOf<Pair<Long, Float>>()

        val pitchJob = viewModelScope.launch {
            pitchProcessor.pitchFlow().collect { result ->
                if (result.isValid && result.frequency > 0f) {
                    detections.add(System.currentTimeMillis() to result.frequency)
                }
            }
        }

        delay(300) // Mic settling

        val offsets = mutableListOf<Long>()
        val startTime = System.currentTimeMillis()

        // Each cycle = 1 beat (bar falling) + 1 beat (silence) = 2 * BEAT_INTERVAL_MS
        val cycleMs = BEAT_INTERVAL_MS * 2

        _state.update { it.copy(calibrationStartTimeMs = startTime) }

        try {
            for (beat in 0 until TOTAL_BEATS) {
                if (!currentCoroutineContext().isActive) break

                // Beat hits the line at: startTime + beat * cycleMs + BEAT_INTERVAL_MS
                // (bar falls for BEAT_INTERVAL_MS, then silence for BEAT_INTERVAL_MS)
                val beatTime = startTime + beat.toLong() * cycleMs + BEAT_INTERVAL_MS
                val isWarmup = beat < WARMUP_BEATS

                _state.update {
                    it.copy(
                        currentBeat = beat + 1,
                        isWarmup = isWarmup
                    )
                }

                // Wait until beat time (when bar hits the line)
                val waitMs = beatTime - System.currentTimeMillis()
                if (waitMs > 0) delay(waitMs)

                // For measured beats, find the nearest detection within the window
                if (!isWarmup) {
                    // Wait for the full detection window after the beat
                    delay(INPUT_DETECTION_WINDOW_MS)

                    val windowStart = beatTime - INPUT_DETECTION_WINDOW_MS
                    val windowEnd = beatTime + INPUT_DETECTION_WINDOW_MS

                    // Find the detection closest to the beat time
                    val nearestDetection = detections.filter { (time, _) ->
                        time in windowStart..windowEnd
                    }.minByOrNull { (time, _) ->
                        kotlin.math.abs(time - beatTime)
                    }

                    if (nearestDetection != null) {
                        val offset = nearestDetection.first - beatTime
                        offsets.add(offset)
                        _state.update { it.copy(beatsDetected = offsets.size) }
                    }

                    // Wait for the silence gap
                    val elapsed = System.currentTimeMillis() - beatTime
                    val remainingGap = BEAT_INTERVAL_MS - elapsed
                    if (remainingGap > 0) delay(remainingGap)
                } else {
                    // Warmup beat — wait for the silence gap
                    val elapsed = System.currentTimeMillis() - beatTime
                    val remainingGap = BEAT_INTERVAL_MS - elapsed
                    if (remainingGap > 0) delay(remainingGap)
                }
            }
        } finally {
            pitchJob.cancel()
        }

        // Need at least 3 measured beats for a reliable median
        return if (offsets.size >= 3) offsets else null
    }

    fun updateEditedInputDelay(text: String) {
        _state.update { it.copy(editedInputDelay = text) }
    }

    fun saveInputDelayAndContinue() {
        val delayMs = _state.value.editedInputDelay.toIntOrNull()
            ?: _state.value.inputDelayMs ?: return
        val device = _state.value.inputDevice ?: return

        viewModelScope.launch {
            if (device.type == AudioInputDeviceType.BUILTIN) {
                // For built-in mic: compute and store offset relative to measured base
                val measuredPipeline = audioCaptureService.measuredPipelineDelayMs.value
                val measuredBase = if (measuredPipeline != null) {
                    (measuredPipeline + AudioCaptureService.ALGORITHM_OFFSET_MS).toInt()
                } else {
                    0
                }
                val offset = delayMs - measuredBase
                audioInputDelayRepository.setBuiltinOffset(offset)
            } else {
                audioInputDelayRepository.setDelayForDevice(device.deviceKey, delayMs)
            }

            _state.update { it.copy(phase = CalibrationPhase.OUTPUT_OFFER) }
        }
    }

    // --- Output calibration: auto play & detect ---

    fun startOutputCalibration() {
        if (!melodySynthesizer.isReady) return

        _state.update {
            it.copy(
                phase = CalibrationPhase.OUTPUT_CALIBRATING,
                tonesPlayed = 0,
                tonesDetected = 0,
                error = null
            )
        }

        calibrationJob = viewModelScope.launch {
            val outputDelayMs = measureOutputDelay()

            if (outputDelayMs != null) {
                _state.update {
                    it.copy(
                        phase = CalibrationPhase.OUTPUT_RESULT,
                        outputDelayMs = outputDelayMs,
                        editedOutputDelay = outputDelayMs.toString()
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        phase = CalibrationPhase.OUTPUT_OFFER,
                        error = "Could not detect enough tones. Ensure the microphone can hear the speaker and the environment is quiet."
                    )
                }
            }
        }
    }

    /**
     * Measures output delay by playing tones and detecting them.
     * output_delay = detection_time - play_time - input_delay
     *
     * Returns the median output delay in ms, or null if not enough tones detected.
     */
    private suspend fun measureOutputDelay(): Int? {
        val detections = mutableListOf<Pair<Long, Float>>()

        val pitchJob = viewModelScope.launch {
            pitchProcessor.pitchFlow().collect { result ->
                if (result.isValid && result.frequency > 0f) {
                    detections.add(System.currentTimeMillis() to result.frequency)
                }
            }
        }

        delay(300) // Mic settling

        val outputDelays = mutableListOf<Long>()
        val inputDelay = currentInputDelayMs.toLong()

        try {
            for (i in 0 until OUTPUT_NUM_TONES) {
                if (!currentCoroutineContext().isActive) break

                val playTime = System.currentTimeMillis()
                melodySynthesizer.noteOn(0, OUTPUT_MIDI_NOTE, OUTPUT_MIDI_VELOCITY)
                delay(OUTPUT_TONE_DURATION_MS)
                melodySynthesizer.noteOff(0, OUTPUT_MIDI_NOTE)

                _state.update { it.copy(tonesPlayed = i + 1) }

                val deadline = playTime + OUTPUT_MAX_DETECTION_WINDOW_MS
                val remaining = deadline - System.currentTimeMillis()
                if (remaining > 0) delay(remaining)

                val detection = detections.firstOrNull { (time, freq) ->
                    time >= playTime &&
                        time <= playTime + OUTPUT_MAX_DETECTION_WINDOW_MS &&
                        kotlin.math.abs(freq - OUTPUT_TARGET_FREQ) < OUTPUT_FREQ_TOLERANCE
                }

                if (detection != null) {
                    // output_delay = round_trip - input_delay
                    val roundTrip = detection.first - playTime
                    val outputDelay = roundTrip - inputDelay
                    outputDelays.add(outputDelay)
                    _state.update { it.copy(tonesDetected = outputDelays.size) }
                }

                val elapsed = System.currentTimeMillis() - playTime
                val gap = OUTPUT_TONE_SPACING_MS - elapsed
                if (gap > 0) delay(gap)
            }
        } finally {
            pitchJob.cancel()
            melodySynthesizer.allNotesOff()
        }

        if (outputDelays.size < 3) return null

        // Use median for robustness against outliers
        val sorted = outputDelays.sorted()
        return sorted[sorted.size / 2].toInt().coerceIn(0, 500)
    }

    fun updateEditedOutputDelay(text: String) {
        _state.update { it.copy(editedOutputDelay = text) }
    }

    fun saveOutputDelayAndFinish() {
        val delayMs = _state.value.editedOutputDelay.toIntOrNull()
            ?: _state.value.outputDelayMs ?: return
        val device = _state.value.outputDevice ?: return

        viewModelScope.launch {
            audioDelayProfileRepository.setDelayForDevice(device.deviceKey, delayMs)
            _state.update { it.copy(phase = CalibrationPhase.DONE, saved = true) }
        }
    }

    fun skipOutputCalibration() {
        _state.update { it.copy(phase = CalibrationPhase.DONE, saved = true) }
    }

    fun stopCalibration() {
        calibrationJob?.cancel()
        if (melodySynthesizer.isReady) {
            melodySynthesizer.allNotesOff()
        }
        goToInputInstructions()
    }

    override fun onCleared() {
        super.onCleared()
        calibrationJob?.cancel()
    }
}
