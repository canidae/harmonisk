package net.exent.perfectpitch.ui.melody

import net.exent.perfectpitch.midi.MelodyTrack
import net.exent.perfectpitch.music.MelodyScore
import net.exent.perfectpitch.music.NoteScore
import net.exent.perfectpitch.music.PitchResult
import net.exent.perfectpitch.music.ViolinPosition

enum class AccompanimentMode { ALL, EXCEPT, SELECTED_ONLY, NONE }

/** Active session tracking state. */
data class MelodyGameState(
    val phase: PlayPhase = PlayPhase.NOT_STARTED,
    /** Primary melody track used for scoring (the active voice). */
    val melodyTrack: MelodyTrack? = null,
    /** One track per voice/staff; each entry is indexed by its voice number. */
    val melodyTracks: List<MelodyTrack> = emptyList(),
    /** Which voice the user has selected to sing (index into melodyTracks). */
    val activeVoiceIndex: Int = 0,
    /** Whether to play all voices, only the selected one, or none. */
    val accompanimentMode: AccompanimentMode = AccompanimentMode.ALL,
    val currentTimeSeconds: Double = 0.0,
    val noteResults: Map<Int, NoteResultState> = emptyMap(),
    val noteScores: List<NoteScore> = emptyList(),
    val currentNoteIndex: Int = -1,
    val pitchResult: PitchResult = PitchResult.SILENT,
    val waitMode: Boolean = true,
    val transposeSemitones: Int = 0,
    val melodyScore: MelodyScore? = null,
    val noteCentsHistory: Map<Int, List<Float>> = emptyMap(),
    val noteCentsPositions: Map<Int, List<Float>> = emptyMap(),
    val notePlayProgress: Map<Int, Float> = emptyMap(),
    val noteFirstDetectionStart: Map<Int, Float> = emptyMap(),
    // Non-zero during a mid-song countdown resume: the game-time value at which scoring starts
    val countdownResumeTarget: Double = 0.0,
    // Metronome
    val metronomeEnabled: Boolean = false,
    val metronomeTrack: MelodyTrack? = null,
    val currentLevel: Int = 1,
    val hideNoteBars: Boolean = false
) {
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
    val fallSpeedPxPerSec: Float = 75f,
    val songTimeOffsetSeconds: Double = 0.0,
    val viewMinMidi: Float = 60f,
    val viewMaxMidi: Float = 72f,
    /** Recommended violin fingerings per active melody note (null = not playable in first position). */
    val violinPositions: List<ViolinPosition?> = emptyList()
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
    PLAYING,
    PAUSED,
    WAITING_FOR_NOTE,
    FINISHED
}

enum class NoteResultState {
    ACTIVE, PERFECT, GREAT, GOOD, OK, MISS
}
