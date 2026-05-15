package com.perfectpitch.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.perfectpitch.audio.AudioDeviceMonitor
import com.perfectpitch.audio.AudioOutputDevice
import com.perfectpitch.audio.MelodySynthesizer
import com.perfectpitch.data.AudioDelayProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val melodySynthesizer: MelodySynthesizer,
    private val audioDeviceMonitor: AudioDeviceMonitor,
    private val audioDelayProfileRepository: AudioDelayProfileRepository
) : ViewModel() {

    companion object {
        const val BPM = 120
        const val BEAT_INTERVAL_MS = 60_000L / BPM  // 500ms
        const val TOTAL_TAPS = 16
        const val WARMUP_TAPS = 4
        const val MIDI_NOTE_C4 = 60
        const val MIDI_VELOCITY = 80
        const val NOTE_DURATION_MS = 100L
    }

    data class CalibrationState(
        val device: AudioOutputDevice? = null,
        val phase: CalibrationPhase = CalibrationPhase.READY,
        val tapsCollected: Int = 0,
        val currentBeat: Int = 0,
        val resultDelayMs: Int? = null,
        val editedDelayMs: String = "",
        val saved: Boolean = false
    )

    enum class CalibrationPhase {
        READY,
        PLAYING,
        RESULT
    }

    private val _state = MutableStateFlow(CalibrationState())
    val state: StateFlow<CalibrationState> = _state.asStateFlow()

    private var metronomeJob: Job? = null
    private var beatTimes = mutableListOf<Long>()
    private var tapDeltas = mutableListOf<Long>()

    init {
        viewModelScope.launch {
            audioDeviceMonitor.currentDevice.collect { device ->
                _state.update { it.copy(device = device) }
            }
        }
    }

    fun initSynthesizer(context: android.content.Context) {
        if (melodySynthesizer.isReady) return
        viewModelScope.launch {
            try {
                melodySynthesizer.open(context)
            } catch (_: Exception) { }
        }
    }

    fun startCalibration() {
        if (_state.value.phase == CalibrationPhase.PLAYING) return

        beatTimes.clear()
        tapDeltas.clear()

        _state.update {
            it.copy(
                phase = CalibrationPhase.PLAYING,
                tapsCollected = 0,
                currentBeat = 0,
                resultDelayMs = null,
                editedDelayMs = "",
                saved = false
            )
        }

        metronomeJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()

            // Play enough beats for TOTAL_TAPS + a few extra for late tappers
            val totalBeats = TOTAL_TAPS + 4
            for (beat in 0 until totalBeats) {
                if (!isActive) break

                val targetTime = startTime + beat * BEAT_INTERVAL_MS
                val now = System.currentTimeMillis()
                val waitMs = targetTime - now
                if (waitMs > 0) delay(waitMs)

                val beatTime = System.currentTimeMillis()
                beatTimes.add(beatTime)

                _state.update { it.copy(currentBeat = beat + 1) }

                // Play note
                if (melodySynthesizer.isReady) {
                    melodySynthesizer.noteOn(0, MIDI_NOTE_C4, MIDI_VELOCITY)
                    delay(NOTE_DURATION_MS)
                    melodySynthesizer.noteOff(0, MIDI_NOTE_C4)
                }

                if (tapDeltas.size >= TOTAL_TAPS) break
            }

            // Wait a moment for final taps
            delay(BEAT_INTERVAL_MS)
            finishCalibration()
        }
    }

    fun onTap() {
        if (_state.value.phase != CalibrationPhase.PLAYING) return
        if (tapDeltas.size >= TOTAL_TAPS) return

        val tapTime = System.currentTimeMillis()
        if (beatTimes.isEmpty()) return

        val nearestBeat = beatTimes.minByOrNull { kotlin.math.abs(it - tapTime) } ?: return
        val delta = tapTime - nearestBeat

        // Accept taps within ±400ms of a beat
        if (kotlin.math.abs(delta) <= 400) {
            tapDeltas.add(delta)
            _state.update { it.copy(tapsCollected = tapDeltas.size) }

            if (tapDeltas.size >= TOTAL_TAPS) {
                metronomeJob?.cancel()
                viewModelScope.launch { finishCalibration() }
            }
        }
    }

    private fun finishCalibration() {
        if (_state.value.phase == CalibrationPhase.RESULT) return

        if (tapDeltas.size < WARMUP_TAPS + 1) {
            _state.update { it.copy(phase = CalibrationPhase.READY) }
            return
        }

        // Discard warmup taps, take median of the rest
        val usableDeltas = tapDeltas.drop(WARMUP_TAPS)
        val medianMs = median(usableDeltas).toInt().coerceIn(-200, 500)

        _state.update {
            it.copy(
                phase = CalibrationPhase.RESULT,
                resultDelayMs = medianMs,
                editedDelayMs = medianMs.toString()
            )
        }
    }

    fun updateEditedDelay(text: String) {
        _state.update { it.copy(editedDelayMs = text) }
    }

    fun saveDelay() {
        val device = _state.value.device ?: return
        val delayMs = _state.value.editedDelayMs.toIntOrNull()
            ?: _state.value.resultDelayMs
            ?: return

        viewModelScope.launch {
            audioDelayProfileRepository.setDelayForDevice(device.deviceKey, delayMs)
            _state.update { it.copy(saved = true) }
        }
    }

    fun stopCalibration() {
        metronomeJob?.cancel()
        if (melodySynthesizer.isReady) {
            melodySynthesizer.allNotesOff()
        }
        _state.update { it.copy(phase = CalibrationPhase.READY) }
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        } else {
            sorted[sorted.size / 2]
        }
    }

    override fun onCleared() {
        super.onCleared()
        metronomeJob?.cancel()
    }
}
