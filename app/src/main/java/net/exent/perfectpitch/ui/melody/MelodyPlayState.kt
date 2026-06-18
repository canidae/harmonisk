package net.exent.perfectpitch.ui.melody

import net.exent.perfectpitch.midi.MelodyTrack
import net.exent.perfectpitch.midi.VoiceInfo
import net.exent.perfectpitch.music.MelodyScore
import net.exent.perfectpitch.music.NoteScore
import net.exent.perfectpitch.music.PitchResult

const val COUNTDOWN_BARS = 2

enum class AccompanimentMode { ALL, SELECTED_ONLY, NONE }

/** Active session tracking state. */
data class MelodyGameState(
    val phase: PlayPhase = PlayPhase.NOT_STARTED,
    /** Primary melody track used for scoring (first track when loaded from multi-track MIDI). */
    val melodyTrack: MelodyTrack? = null,
    /** All tracks loaded for this melody; first entry is the scoring track. */
    val melodyTracks: List<MelodyTrack> = emptyList(),
    /** Split voice info for multi-voice melodies (empty when monophonic). */
    val voiceInfos: List<VoiceInfo> = emptyList(),
    /** Which voice the user has selected to sing (index into voiceInfos). */
    val activeVoiceIndex: Int = 0,
    /** Whether to play all voices, only the selected one, or none. */
    val accompanimentMode: AccompanimentMode = AccompanimentMode.ALL,
    val currentTimeSeconds: Double = 0.0,
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
    val noteCentsPositions: Map<Int, List<Float>> = emptyMap(),
    val notePlayProgress: Map<Int, Float> = emptyMap(),
    val noteFirstDetectionStart: Map<Int, Float> = emptyMap(),
    // Non-zero during a mid-song countdown resume: the game-time value at which scoring starts
    val countdownResumeTarget: Double = 0.0,
    // Metronome
    val metronomeEnabled: Boolean = false,
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
