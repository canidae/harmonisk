package net.exent.harmonisk.ui.metronome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.exent.harmonisk.audio.SoundFontSynthesizer
import net.exent.harmonisk.data.SettingsRepository
import javax.inject.Inject

@HiltViewModel
class MetronomeViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val soundFontSynthesizer: SoundFontSynthesizer
) : ViewModel() {

    private val _state = MutableStateFlow(MetronomeState())
    val state: StateFlow<MetronomeState> = _state.asStateFlow()

    private var tickJob: Job? = null
    private var synthJob: Job? = null

    init {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val beats = settings.metronomeBeatsPerBar.coerceIn(1, METRONOME_MAX_BEATS)
            _state.update {
                it.copy(
                    bpm = settings.metronomeBpm.coerceIn(METRONOME_MIN_BPM, METRONOME_MAX_BPM),
                    beatsPerBar = beats,
                    accentBeat = settings.metronomeAccentBeat.coerceIn(0, beats - 1),
                    mutedBeats = settings.metronomeMutedBeats.filter { it < beats }.toSet(),
                    soundEnabled = settings.metronomeSoundEnabled
                )
            }
            openSynthesizer()
        }
    }

    fun setBpm(bpm: Int) {
        _state.update { it.copy(bpm = bpm.coerceIn(METRONOME_MIN_BPM, METRONOME_MAX_BPM)) }
    }

    fun saveBpm() {
        val bpm = _state.value.bpm
        persist { settingsRepository.updateMetronomeBpm(bpm) }
    }

    fun adjustBpm(delta: Int) {
        setBpm(_state.value.bpm + delta)
        saveBpm()
    }

    fun setBeatsPerBar(beats: Int) {
        val clamped = beats.coerceIn(1, METRONOME_MAX_BEATS)
        val previous = _state.value.mutedBeats
        val muted = previous.filter { it < clamped }.toSet()
        _state.update {
            it.copy(
                beatsPerBar = clamped,
                accentBeat = it.accentBeat.coerceAtMost(clamped - 1),
                currentBeat = it.currentBeat.coerceAtMost(clamped - 1),
                mutedBeats = muted
            )
        }
        persist { settingsRepository.updateMetronomeBeatsPerBar(clamped) }
        if (muted != previous) persist { settingsRepository.updateMetronomeMutedBeats(muted) }
    }

    fun toggleMutedBeat(beat: Int) {
        val muted = _state.value.mutedBeats
        val updated = if (beat in muted) muted - beat else muted + beat
        _state.update { it.copy(mutedBeats = updated) }
        persist { settingsRepository.updateMetronomeMutedBeats(updated) }
    }

    fun setAccentBeat(beat: Int) {
        val clamped = beat.coerceIn(0, _state.value.beatsPerBar - 1)
        _state.update { it.copy(accentBeat = clamped) }
        persist { settingsRepository.updateMetronomeAccentBeat(clamped) }
    }

    fun toggleSound() {
        val enabled = !_state.value.soundEnabled
        _state.update { it.copy(soundEnabled = enabled) }
        persist { settingsRepository.updateMetronomeSoundEnabled(enabled) }
    }

    fun toggleRunning() {
        if (_state.value.isRunning) stop() else start()
    }

    private fun start() {
        if (tickJob?.isActive == true) return
        openSynthesizer()
        _state.update { it.copy(isRunning = true, currentBeat = 0) }
        tickJob = viewModelScope.launch {
            var beatIndex = 0L
            var nextNanos = System.nanoTime()
            while (isActive) {
                val current = _state.value
                val beat = (beatIndex % current.beatsPerBar).toInt()
                val accented = beat == current.accentBeat.coerceIn(0, current.beatsPerBar - 1)
                _state.update { it.copy(currentBeat = beat, pulse = it.pulse + 1) }
                if (beat !in current.mutedBeats) click(accented)
                beatIndex++
                nextNanos += (60_000_000_000.0 / current.bpm).toLong()
                val remainingMs = (nextNanos - System.nanoTime()) / 1_000_000
                if (remainingMs > 0) delay(remainingMs) else nextNanos = System.nanoTime()
            }
        }
    }

    fun stop() {
        tickJob?.cancel()
        tickJob = null
        soundFontSynthesizer.noteOff(SoundFontSynthesizer.DRUM_CHANNEL, BEAT_NOTE)
        soundFontSynthesizer.noteOff(SoundFontSynthesizer.DRUM_CHANNEL, ACCENT_NOTE)
        _state.update { it.copy(isRunning = false, currentBeat = 0) }
    }

    private fun click(accented: Boolean) {
        if (!_state.value.soundEnabled || !soundFontSynthesizer.isReady) return
        val note = if (accented) ACCENT_NOTE else BEAT_NOTE
        soundFontSynthesizer.noteOn(SoundFontSynthesizer.DRUM_CHANNEL, note, if (accented) 112 else 80)
        viewModelScope.launch {
            delay(CLICK_LENGTH_MS)
            soundFontSynthesizer.noteOff(SoundFontSynthesizer.DRUM_CHANNEL, note)
        }
    }

    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch { withContext(NonCancellable) { block() } }
    }

    private fun openSynthesizer() {
        if (soundFontSynthesizer.isReady || synthJob?.isActive == true) return
        synthJob = viewModelScope.launch {
            try {
                soundFontSynthesizer.open()
            } catch (_: Exception) {
            }
        }
    }

    override fun onCleared() {
        tickJob?.cancel()
        soundFontSynthesizer.close()
    }

    companion object {
        private const val BEAT_NOTE = 85
        private const val ACCENT_NOTE = 86
        private const val CLICK_LENGTH_MS = 50L
    }
}
