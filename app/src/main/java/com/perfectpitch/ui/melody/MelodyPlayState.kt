package com.perfectpitch.ui.melody

import com.perfectpitch.midi.MelodyTrack
import com.perfectpitch.music.MelodyScore
import com.perfectpitch.music.NoteScore
import com.perfectpitch.music.PitchResult

data class MelodyPlayState(
    val phase: PlayPhase = PlayPhase.NOT_STARTED,
    val melodyTrack: MelodyTrack? = null,
    val currentTimeSeconds: Double = 0.0,
    val countdownValue: Int = 3,
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
    // View controls
    val fallSpeedPxPerSec: Float = 150f,
    val songTimeOffsetSeconds: Double = 0.0,
    val viewMinMidi: Float = 60f,
    val viewMaxMidi: Float = 72f,
    // Continuous pitch trace recorded during playback
    val pitchTrace: List<PitchFrame> = emptyList()
)

data class PitchFrame(
    val timeSeconds: Double,
    val midiNote: Int,
    val centsOff: Float,
    val frequency: Float
)

enum class PlayPhase {
    NOT_STARTED,
    COUNTDOWN,
    PLAYING,
    PAUSED,
    WAITING_FOR_NOTE,
    FINISHED
}

enum class NoteResultState {
    ACTIVE, PERFECT, GREAT, GOOD, OK, MISS
}
