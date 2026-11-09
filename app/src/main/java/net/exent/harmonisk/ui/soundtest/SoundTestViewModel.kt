package net.exent.harmonisk.ui.soundtest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.exent.harmonisk.audio.MidiCue
import net.exent.harmonisk.audio.MidiCuePlayer
import net.exent.harmonisk.audio.MidiCues
import javax.inject.Inject

@HiltViewModel
class SoundTestViewModel @Inject constructor(
    private val cues: MidiCuePlayer
) : ViewModel() {
    private var allJob: Job? = null

    fun prepare() = cues.prepare()

    fun play(cue: MidiCue) {
        allJob?.cancel()
        allJob = null
        cues.stopAll()
        cues.play(cue)
    }

    fun playAll() {
        allJob?.cancel()
        cues.stopAll()
        allJob = viewModelScope.launch {
            ALL.forEach { cue ->
                cues.play(cue)
                delay(cue.durationMs + 700L)
            }
        }
    }

    fun stop() {
        allJob?.cancel()
        allJob = null
        cues.stopAll()
    }

    override fun onCleared() {
        cues.stopAll()
    }

    private companion object {
        val ALL = listOf(
            MidiCues.TurnChime,
            MidiCues.DiceRattle,
            MidiCues.Applause,
            MidiCues.Whistle,
            MidiCues.Faerie,
            MidiCues.CastleFanfare,
            MidiCues.Fanfare,
            MidiCues.WhackIntro,
            MidiCues.SongIntro
        )
    }
}
