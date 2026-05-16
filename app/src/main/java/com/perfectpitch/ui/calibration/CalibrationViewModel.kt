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
    private val audioInputDelayRepository: AudioInputDelayRepository,
    private val audioDelayProfileRepository: AudioDelayProfileRepository,
    private val pitchProcessor: PitchProcessor
) : ViewModel() {

    companion object {
        const val NUM_TONES = 8
        const val TONE_SPACING_MS = 600L
        const val TONE_DURATION_MS = 200L
        const val MIDI_NOTE = 69 // A4 = 440 Hz
        const val MIDI_VELOCITY = 100
        const val MAX_DETECTION_WINDOW_MS = 500L
        const val TARGET_FREQ = 440f
        const val FREQ_TOLERANCE = 30f
    }

    /**
     * The calibration flow:
     *
     * INIT → checks for baseline and external devices
     *
     * If no baseline exists:
     *   BASELINE_NEEDED → user disconnects external devices
     *   BASELINE_READY → user presses Start
     *   BASELINE_CALIBRATING → tones play
     *   BASELINE_RESULT → shows computed input delay, user can edit
     *
     * If external output device detected:
     *   DEVICE_OUTPUT_INSTRUCTIONS → user reconnects external speaker
     *   DEVICE_OUTPUT_READY → user presses Start
     *   DEVICE_OUTPUT_CALIBRATING → tones play
     *   DEVICE_OUTPUT_RESULT → shows computed output delay
     *
     * If external input device detected:
     *   DEVICE_INPUT_INSTRUCTIONS → user reconnects external mic
     *   DEVICE_INPUT_READY → user presses Start
     *   DEVICE_INPUT_CALIBRATING → tones play
     *   DEVICE_INPUT_RESULT → shows computed input delay
     *
     * DONE → all calibration complete
     */
    enum class CalibrationStep {
        INIT,
        BASELINE_NEEDED,
        BASELINE_READY,
        BASELINE_CALIBRATING,
        BASELINE_RESULT,
        DEVICE_OUTPUT_INSTRUCTIONS,
        DEVICE_OUTPUT_READY,
        DEVICE_OUTPUT_CALIBRATING,
        DEVICE_OUTPUT_RESULT,
        DEVICE_INPUT_INSTRUCTIONS,
        DEVICE_INPUT_READY,
        DEVICE_INPUT_CALIBRATING,
        DEVICE_INPUT_RESULT,
        DONE
    }

    data class CalibrationState(
        val step: CalibrationStep = CalibrationStep.INIT,
        val synthReady: Boolean = false,
        val synthError: String? = null,
        val hasBaseline: Boolean = false,
        // Current devices when calibration screen opened
        val outputDevice: AudioOutputDevice? = null,
        val inputDevice: AudioInputDevice? = null,
        val hasExternalOutput: Boolean = false,
        val hasExternalInput: Boolean = false,
        // Remembers device names for instructions
        val externalOutputName: String = "",
        val externalInputName: String = "",
        // Calibration progress
        val tonesPlayed: Int = 0,
        val tonesDetected: Int = 0,
        // Baseline result
        val baselineRoundTripMs: Int? = null,
        val baselineInputDelayMs: Int? = null,
        val editedBaselineInputDelay: String = "",
        // Device output result
        val deviceOutputRoundTripMs: Int? = null,
        val deviceOutputDelayMs: Int? = null,
        val editedOutputDelay: String = "",
        // Device input result
        val deviceInputRoundTripMs: Int? = null,
        val deviceInputDelayMs: Int? = null,
        val editedInputDelay: String = "",
        // Status
        val saved: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(CalibrationState())
    val state: StateFlow<CalibrationState> = _state.asStateFlow()

    private var calibrationJob: Job? = null

    init {
        // Track device changes
        viewModelScope.launch {
            combine(
                audioDeviceMonitor.currentDevice,
                audioDeviceMonitor.currentInputDevice,
                audioInputDelayRepository.hasBaseline
            ) { output, input, baseline ->
                Triple(output, input, baseline)
            }.collect { (output, input, baseline) ->
                _state.update {
                    it.copy(
                        outputDevice = output,
                        inputDevice = input,
                        hasBaseline = baseline
                    )
                }
            }
        }
    }

    fun initSynthesizer(context: android.content.Context) {
        if (melodySynthesizer.isReady) {
            _state.update { it.copy(synthReady = true) }
            determineFirstStep()
            return
        }
        viewModelScope.launch {
            try {
                melodySynthesizer.open(context)
                _state.update { it.copy(synthReady = true, synthError = null) }
                determineFirstStep()
            } catch (e: Exception) {
                _state.update { it.copy(synthError = e.message ?: "Failed to initialize audio") }
            }
        }
    }

    private fun determineFirstStep() {
        viewModelScope.launch {
            val hasBaseline = audioInputDelayRepository.getBaselineInputDelay() != null
            val output = audioDeviceMonitor.currentDevice.value
            val input = audioDeviceMonitor.currentInputDevice.value
            val hasExtOutput = output.type != AudioOutputDeviceType.SPEAKER
            val hasExtInput = input.type != AudioInputDeviceType.BUILTIN

            _state.update {
                it.copy(
                    hasBaseline = hasBaseline,
                    hasExternalOutput = hasExtOutput,
                    hasExternalInput = hasExtInput,
                    externalOutputName = if (hasExtOutput) output.name else "",
                    externalInputName = if (hasExtInput) input.name else ""
                )
            }

            if (!hasBaseline) {
                if (hasExtOutput || hasExtInput) {
                    _state.update { it.copy(step = CalibrationStep.BASELINE_NEEDED) }
                } else {
                    _state.update { it.copy(step = CalibrationStep.BASELINE_READY) }
                }
            } else if (hasExtOutput) {
                _state.update { it.copy(step = CalibrationStep.DEVICE_OUTPUT_READY) }
            } else if (hasExtInput) {
                _state.update { it.copy(step = CalibrationStep.DEVICE_INPUT_READY) }
            } else {
                // Built-in only and baseline exists — offer recalibration
                _state.update { it.copy(step = CalibrationStep.BASELINE_READY) }
            }
        }
    }

    /** User confirmed external devices are disconnected — proceed to baseline. */
    fun confirmBaselineReady() {
        _state.update { it.copy(step = CalibrationStep.BASELINE_READY, error = null) }
    }

    /** Start the active calibration measurement. */
    fun startCalibration() {
        val step = _state.value.step
        val calibratingStep = when (step) {
            CalibrationStep.BASELINE_READY -> CalibrationStep.BASELINE_CALIBRATING
            CalibrationStep.DEVICE_OUTPUT_READY -> CalibrationStep.DEVICE_OUTPUT_CALIBRATING
            CalibrationStep.DEVICE_INPUT_READY -> CalibrationStep.DEVICE_INPUT_CALIBRATING
            else -> return
        }

        if (!melodySynthesizer.isReady) return

        _state.update {
            it.copy(
                step = calibratingStep,
                tonesPlayed = 0,
                tonesDetected = 0,
                error = null
            )
        }

        calibrationJob = viewModelScope.launch {
            val roundTripMs = measureRoundTrip()

            if (roundTripMs != null) {
                when (calibratingStep) {
                    CalibrationStep.BASELINE_CALIBRATING -> handleBaselineResult(roundTripMs)
                    CalibrationStep.DEVICE_OUTPUT_CALIBRATING -> handleDeviceOutputResult(roundTripMs)
                    CalibrationStep.DEVICE_INPUT_CALIBRATING -> handleDeviceInputResult(roundTripMs)
                    else -> {}
                }
            } else {
                // Failed — go back to ready state for current step
                val readyStep = when (calibratingStep) {
                    CalibrationStep.BASELINE_CALIBRATING -> CalibrationStep.BASELINE_READY
                    CalibrationStep.DEVICE_OUTPUT_CALIBRATING -> CalibrationStep.DEVICE_OUTPUT_READY
                    CalibrationStep.DEVICE_INPUT_CALIBRATING -> CalibrationStep.DEVICE_INPUT_READY
                    else -> CalibrationStep.INIT
                }
                _state.update {
                    it.copy(
                        step = readyStep,
                        error = "Could not detect enough tones. Make sure the microphone can hear the speaker and the environment is quiet."
                    )
                }
            }
        }
    }

    private suspend fun measureRoundTrip(): Int? {
        val delays = mutableListOf<Long>()
        val detections = mutableListOf<Pair<Long, Float>>()

        val pitchJob = viewModelScope.launch {
            pitchProcessor.pitchFlow().collect { result ->
                if (result.isValid && result.frequency > 0f) {
                    detections.add(System.currentTimeMillis() to result.frequency)
                }
            }
        }

        delay(300) // Mic settling

        try {
            for (i in 0 until NUM_TONES) {
                if (!currentCoroutineContext().isActive) break

                val playTime = System.currentTimeMillis()
                melodySynthesizer.noteOn(0, MIDI_NOTE, MIDI_VELOCITY)
                delay(TONE_DURATION_MS)
                melodySynthesizer.noteOff(0, MIDI_NOTE)

                _state.update { it.copy(tonesPlayed = i + 1) }

                val deadline = playTime + MAX_DETECTION_WINDOW_MS
                val remaining = deadline - System.currentTimeMillis()
                if (remaining > 0) delay(remaining)

                val detection = detections.firstOrNull { (time, freq) ->
                    time >= playTime &&
                        time <= playTime + MAX_DETECTION_WINDOW_MS &&
                        kotlin.math.abs(freq - TARGET_FREQ) < FREQ_TOLERANCE
                }

                if (detection != null) {
                    delays.add(detection.first - playTime)
                    _state.update { it.copy(tonesDetected = delays.size) }
                }

                val elapsed = System.currentTimeMillis() - playTime
                val gap = TONE_SPACING_MS - elapsed
                if (gap > 0) delay(gap)
            }
        } finally {
            pitchJob.cancel()
            melodySynthesizer.allNotesOff()
        }

        if (delays.size < 2) return null

        val sorted = delays.sorted()
        val median = if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        } else {
            sorted[sorted.size / 2]
        }
        return median.toInt().coerceIn(0, 1000)
    }

    private fun handleBaselineResult(roundTripMs: Int) {
        val inputDelay = (roundTripMs - AudioInputDelayRepository.BUILTIN_SPEAKER_DELAY_MS)
            .coerceIn(0, 500)
        _state.update {
            it.copy(
                step = CalibrationStep.BASELINE_RESULT,
                baselineRoundTripMs = roundTripMs,
                baselineInputDelayMs = inputDelay,
                editedBaselineInputDelay = inputDelay.toString()
            )
        }
    }

    private suspend fun handleDeviceOutputResult(roundTripMs: Int) {
        val baselineInput = audioInputDelayRepository.getBaselineInputDelay() ?: 0
        val outputDelay = (roundTripMs - baselineInput).coerceIn(0, 500)
        _state.update {
            it.copy(
                step = CalibrationStep.DEVICE_OUTPUT_RESULT,
                deviceOutputRoundTripMs = roundTripMs,
                deviceOutputDelayMs = outputDelay,
                editedOutputDelay = outputDelay.toString()
            )
        }
    }

    private fun handleDeviceInputResult(roundTripMs: Int) {
        val inputDelay = (roundTripMs - AudioInputDelayRepository.BUILTIN_SPEAKER_DELAY_MS)
            .coerceIn(0, 500)
        _state.update {
            it.copy(
                step = CalibrationStep.DEVICE_INPUT_RESULT,
                deviceInputRoundTripMs = roundTripMs,
                deviceInputDelayMs = inputDelay,
                editedInputDelay = inputDelay.toString()
            )
        }
    }

    // --- Edit helpers ---

    fun updateEditedBaselineDelay(text: String) {
        _state.update { it.copy(editedBaselineInputDelay = text) }
    }

    fun updateEditedOutputDelay(text: String) {
        _state.update { it.copy(editedOutputDelay = text) }
    }

    fun updateEditedInputDelay(text: String) {
        _state.update { it.copy(editedInputDelay = text) }
    }

    // --- Save & proceed ---

    /** Save baseline result and advance to next step if needed. */
    fun saveBaselineAndContinue() {
        val delayMs = _state.value.editedBaselineInputDelay.toIntOrNull()
            ?: _state.value.baselineInputDelayMs ?: return

        viewModelScope.launch {
            audioInputDelayRepository.setBaselineInputDelay(delayMs)
            _state.update { it.copy(hasBaseline = true) }

            // Advance to device calibration if external devices present
            val s = _state.value
            if (s.hasExternalOutput) {
                _state.update { it.copy(step = CalibrationStep.DEVICE_OUTPUT_INSTRUCTIONS) }
            } else if (s.hasExternalInput) {
                _state.update { it.copy(step = CalibrationStep.DEVICE_INPUT_INSTRUCTIONS) }
            } else {
                _state.update { it.copy(step = CalibrationStep.DONE, saved = true) }
            }
        }
    }

    /** User reconnected external output device — proceed to calibrate. */
    fun confirmDeviceOutputReady() {
        _state.update { it.copy(step = CalibrationStep.DEVICE_OUTPUT_READY, error = null) }
    }

    /** Save output delay result and advance. */
    fun saveOutputDelayAndContinue() {
        val delayMs = _state.value.editedOutputDelay.toIntOrNull()
            ?: _state.value.deviceOutputDelayMs ?: return
        val device = _state.value.outputDevice ?: return

        viewModelScope.launch {
            audioDelayProfileRepository.setDelayForDevice(device.deviceKey, delayMs)

            val s = _state.value
            if (s.hasExternalInput) {
                _state.update { it.copy(step = CalibrationStep.DEVICE_INPUT_INSTRUCTIONS) }
            } else {
                _state.update { it.copy(step = CalibrationStep.DONE, saved = true) }
            }
        }
    }

    /** User reconnected external input device — proceed to calibrate. */
    fun confirmDeviceInputReady() {
        _state.update { it.copy(step = CalibrationStep.DEVICE_INPUT_READY, error = null) }
    }

    /** Save input delay result and finish. */
    fun saveInputDelayAndFinish() {
        val delayMs = _state.value.editedInputDelay.toIntOrNull()
            ?: _state.value.deviceInputDelayMs ?: return
        val device = _state.value.inputDevice ?: return

        viewModelScope.launch {
            audioInputDelayRepository.setDelayForDevice(device.deviceKey, delayMs)
            _state.update { it.copy(step = CalibrationStep.DONE, saved = true) }
        }
    }

    fun stopCalibration() {
        calibrationJob?.cancel()
        if (melodySynthesizer.isReady) {
            melodySynthesizer.allNotesOff()
        }
        determineFirstStep()
    }

    override fun onCleared() {
        super.onCleared()
        calibrationJob?.cancel()
    }
}
