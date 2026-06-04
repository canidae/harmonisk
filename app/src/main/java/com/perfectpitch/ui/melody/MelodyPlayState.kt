package com.perfectpitch.ui.melody

import com.perfectpitch.midi.MelodyTrack
import com.perfectpitch.music.MelodyScore
import com.perfectpitch.music.NoteScore
import com.perfectpitch.music.PitchResult

/** Active session tracking state. */
data class MelodyGameState(
    val phase: PlayPhase = PlayPhase.NOT_STARTED,
    val melodyTrack: MelodyTrack? = null,
    val currentTimeSeconds: Double = 0.0,
    val countdownDurationSeconds: Double = 3.0,
    val noteResults: Map<Int, NoteResultState> = emptyMap(),
    val noteScores: List<NoteScore> = emptyList(),
    val currentNoteIndex: Int = -1,
    val pitchResult: PitchResult = PitchResult.SILENT,
    val waitMode: Boolean = true,
    val allowWrongNotes: Boolean = false,
    val tempoMultiplier: Float = 1.0f,
    val transposeSemitones: Int = 0,
    val melodyScore: MelodyScore? = null,
    val isListening: Boolean = false,
    val playMelodyAudio: Boolean = false,
    val noteCentsHistory: Map<Int, List<Float>> = emptyMap(),
    val notePlayProgress: Map<Int, Float> = emptyMap(),
    // Non-zero during a mid-song countdown resume: the game-time value at which scoring starts
    val countdownResumeTarget: Double = 0.0,
    // Metronome
    val metronomeEnabled: Boolean = false
) {
    /** Countdown display beat number, counting 1..beatsPerBar repeatedly. */
    val countdownValue: Int
        get() {
            if (phase != PlayPhase.COUNTDOWN) return 0
            val track = melodyTrack ?: return (-currentTimeSeconds).toInt()
            val tick = track.secondsToTick(0.0)
            val bpm = track.bpmAt(tick)
            val beatsPerBar = track.timeSignatureAt(tick).numerator
            val intervalSeconds = 60.0 / bpm
            val elapsed = currentTimeSeconds + countdownDurationSeconds
            val currentBeat = (elapsed / intervalSeconds).toLong()
            return (currentBeat % beatsPerBar).toInt() + 1
        }

    /**
     * True when the view is already scrolled to (or before) the first note.
     * Used in FINISHED state to hide the "Scroll to start" button once there.
     */
    fun isAtScrollStart(songTimeOffsetSeconds: Double): Boolean {
        val track = melodyTrack ?: return true
        val firstNoteStart = track.notes.firstOrNull()?.let { track.noteStartSeconds(it) } ?: 0.0
        return currentTimeSeconds + songTimeOffsetSeconds <= firstNoteStart + 0.05
    }
}

/** Rendering and scroll parameters. */
data class MelodyViewState(
    val fallSpeedPxPerSec: Float = 150f,
    val songTimeOffsetSeconds: Double = 0.0,
    val viewMinMidi: Float = 60f,
    val viewMaxMidi: Float = 72f
)

/** MIDI device availability state. */
data class MelodyDeviceState(
    val midiAvailable: Boolean = false,
    val midiInputEnabled: Boolean = false,
    val midiOutputEnabled: Boolean = false
)

enum class PlayPhase {
    NOT_STARTED,
    READY,
    COUNTDOWN,
    PLAYING,
    PAUSED,
    WAITING_FOR_NOTE,
    FINISHED
}

enum class NoteResultState {
    ACTIVE, PERFECT, GREAT, GOOD, OK, MISS
}
