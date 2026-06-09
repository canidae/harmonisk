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
        const val TONE_SPACING_MS = 800L
        const val TONE_DURATION_MS = 300L
        const val MIDI_NOTE = 69 // A4 = 440 Hz
        const val MIDI_VELOCITY = 100
        const val MAX_DETECTION_WINDOW_MS = 600L
        const val TARGET_FREQ = 440f
        const val FREQ_TOLERANCE = 30f

        const val PULSE_ON_MS = 500L
        const val PULSE_OFF_MS = 500L
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
        val inputDevice: AudioInputDevice? = null,
        val outputDevice: AudioOutputDevice? = null,
        // Phase 1
        val tonesPlayed: Int = 0,
        val tonesDetected: Int = 0,
        val totalDelayMs: Int = 0,
        // Phase 2: split as percentage (0–100 integer)
        val splitPercent: Int = 50,
        val splitActive: Boolean = false,
        /** Epoch ms when the current tone cycle started playing. Updated each cycle. */
        val toneStartTimeMs: Long = 0L,
        // Status
        val saved: Boolean = false,
        val error: String? = null
    ) {
        val outputDelayMs: Int get() = (totalDelayMs * splitPercent / 100f).toInt()
        val inputDelayMs: Int get() = totalDelayMs - outputDelayMs
    }

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
                val outputDevice = _state.value.outputDevice
                val inputDevice = _state.value.inputDevice
                val initialSplit = computeInitialSplitPercent(totalMs, outputDevice, inputDevice)

                _state.update {
                    it.copy(
                        phase = CalibrationPhase.ADJUSTING_SPLIT,
                        totalDelayMs = totalMs,
                        splitPercent = initialSplit
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

    private suspend fun measureRoundTrip(): Int? {
        // Channel is the single producer/consumer point — no shared-state race.
        val detectionChannel = Channel<Pair<Long, Float>>(Channel.UNLIMITED)
        val localDetections = mutableListOf<Pair<Long, Float>>()
        val roundTrips = mutableListOf<Long>()

        try {
            coroutineScope {
                // Child coroutine: collect pitch detections and forward to channel.
                val pitchJob = launch {
                    pitchProcessor.pitchFlow().collect { result ->
                        if (result.isValid && result.frequency > 0f) {
                            detectionChannel.trySend(System.currentTimeMillis() to result.frequency)
                        }
                    }
                }

                delay(300)

                try {
                    for (i in 0 until NUM_TONES) {
                        if (!isActive) break

                        val playTime = System.currentTimeMillis()
                        melodySynthesizer.noteOn(0, MIDI_NOTE, MIDI_VELOCITY)
                        delay(TONE_DURATION_MS)
                        melodySynthesizer.noteOff(0, MIDI_NOTE)

                        _state.update { it.copy(tonesPlayed = i + 1) }

                        val deadline = playTime + MAX_DETECTION_WINDOW_MS
                        val remaining = deadline - System.currentTimeMillis()
                        if (remaining > 0) delay(remaining)

                        // Drain any new detections from the channel into the local list.
                        while (true) {
                            localDetections.add(detectionChannel.tryReceive().getOrNull() ?: break)
                        }

                        val detection = localDetections.firstOrNull { (time, freq) ->
                            time >= playTime &&
                                time <= playTime + MAX_DETECTION_WINDOW_MS &&
                                kotlin.math.abs(freq - TARGET_FREQ) < FREQ_TOLERANCE
                        }

                        if (detection != null) {
                            val roundTrip = detection.first - playTime
                            roundTrips.add(roundTrip)
                            _state.update { it.copy(tonesDetected = roundTrips.size) }
                        }

                        val cutoff = playTime + MAX_DETECTION_WINDOW_MS
                        localDetections.removeAll { (time, _) -> time <= cutoff }

                        val elapsed = System.currentTimeMillis() - playTime
                        val gap = TONE_SPACING_MS - elapsed
                        if (gap > 0) delay(gap)
                    }
                } finally {
                    pitchJob.cancel()
                    melodySynthesizer.allNotesOff()
                }
            }
        } finally {
            detectionChannel.close()
        }

        if (roundTrips.size < 3) return null

        val sorted = roundTrips.sorted()
        return sorted[sorted.size / 2].toInt().coerceIn(0, 1000)
    }

    private suspend fun computeInitialSplitPercent(
        totalMs: Int,
        outputDevice: AudioOutputDevice?,
        inputDevice: AudioInputDevice?
    ): Int {
        if (outputDevice != null) {
            val profile = audioDelayRepository.getProfile(outputDevice.deviceKey).first()
            if (profile != null) {
                return (profile.splitPercent * 100).toInt().coerceIn(0, 100)
            }
        }
        if (inputDevice != null) {
            val profile = audioDelayRepository.getProfile(inputDevice.deviceKey).first()
            if (profile != null) {
                return (profile.splitPercent * 100).toInt().coerceIn(0, 100)
            }
        }
        return (AudioDelayRepository.DEFAULT_OUTPUT_SPLIT * 100).toInt()
    }

    fun updateSplitPercent(percent: Int) {
        _state.update { it.copy(splitPercent = percent.coerceIn(0, 100)) }
    }

    // Tone cycle: play for PULSE_ON_MS, then wait outputDelay + PULSE_OFF_MS before the next.
    private fun startSplitLoop() {
        stopSplitLoop()
        _state.update { it.copy(splitActive = true) }

        splitLoopJob = viewModelScope.launch {
            while (isActive) {
                val outputDelay = _state.value.outputDelayMs.toLong()

                _state.update { it.copy(toneStartTimeMs = System.currentTimeMillis()) }
                melodySynthesizer.noteOn(0, MIDI_NOTE, MIDI_VELOCITY)
                delay(PULSE_ON_MS)
                melodySynthesizer.noteOff(0, MIDI_NOTE)

                val gap = outputDelay + PULSE_OFF_MS
                if (gap > 0) delay(gap)
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

    fun saveAndFinish() {
        val s = _state.value
        val inputDevice = s.inputDevice ?: return
        val outputDevice = s.outputDevice ?: return
        val splitFraction = s.splitPercent / 100f

        stopSplitLoop()

        viewModelScope.launch {
            audioDelayRepository.saveCalibration(
                deviceKey = inputDevice.deviceKey,
                totalDelayMs = s.totalDelayMs,
                splitPercent = splitFraction
            )
            audioDelayRepository.saveCalibration(
                deviceKey = outputDevice.deviceKey,
                totalDelayMs = s.totalDelayMs,
                splitPercent = splitFraction
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
