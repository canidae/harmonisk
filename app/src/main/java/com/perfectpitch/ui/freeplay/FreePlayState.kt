package com.perfectpitch.ui.freeplay

import com.perfectpitch.music.PitchResult
import com.perfectpitch.ui.components.PitchFrame

enum class NoteEditMode { NONE, EDIT }

enum class PianoVisibility { NONE, UPPER_ONLY, LOWER_ONLY, BOTH }

data class FreePlayState(
    val pitchResult: PitchResult = PitchResult.SILENT,
    val isListening: Boolean = false,
    val isRecording: Boolean = true,
    val playedNotes: List<PlayedNote> = emptyList(),
    val activeNote: PlayedNote? = null,
    val viewMinMidi: Float = 60f,
    val viewMaxMidi: Float = 72f,
    val fallSpeedPxPerSec: Float = 150f,
    val timeOffsetMs: Long = 0,
    val pitchTrace: List<PitchFrame> = emptyList(),
    val beatsPerBar: Int = 4,
    val bpm: Float = 120f,
    val minNoteDivision: Int = 16,
    val snapEnabled: Boolean = true,
    // Time base for session-relative bar numbers and loop positions.
    val sessionStartMs: Long = 0L,
    val activeVoiceIndex: Int = 0,
    val voiceCount: Int = 1,
    val loopStartBar: Int? = null,
    val loopEndBar: Int? = null,
    val isLooping: Boolean = false,
    val editMode: NoteEditMode = NoteEditMode.NONE,
    val selectedNoteId: String? = null,
    // MIDI state
    val midiAvailable: Boolean = false,
    val midiInputEnabled: Boolean = false,
    val midiOutputEnabled: Boolean = false,
    val midiInputDeviceName: String? = null,
    val midiOutputDeviceName: String? = null,
    val isReplaying: Boolean = false,
    // Piano visibility
    val pianoVisibility: PianoVisibility = PianoVisibility.UPPER_ONLY,
    // Meter denominator (time signature note value: 2, 4, 8, 16, 32)
    val barDivision: Int = 4
)

data class PlayedNote(
    val noteName: String,
    val octave: Int,
    val midiNumber: Int,
    val startTimeMs: Long,
    val endTimeMs: Long? = null,
    val averageCentsOff: Float = 0f,
    val centsHistory: List<Float> = emptyList(),
    val id: String = java.util.UUID.randomUUID().toString(),
    val voiceIndex: Int = 0
) {
    val isActive: Boolean get() = endTimeMs == null
}
