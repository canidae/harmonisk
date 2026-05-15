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
    val waitMode: Boolean = false,
    val tempoMultiplier: Float = 1.0f,
    val melodyScore: MelodyScore? = null,
    val isListening: Boolean = false
)

enum class PlayPhase {
    NOT_STARTED,
    COUNTDOWN,
    PLAYING,
    PAUSED,
    FINISHED
}
